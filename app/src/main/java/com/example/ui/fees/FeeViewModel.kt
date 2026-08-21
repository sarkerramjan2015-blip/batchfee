package com.batchfee.edu.ui.fees

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.audit.StaffActivityLogger
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.data.repository.FeeCollectionRepository
import com.batchfee.edu.data.repository.FinancialOperationPendingException
import com.batchfee.edu.domain.appendInstituteSignature
import com.batchfee.edu.domain.loadInstituteSignature
import com.batchfee.edu.domain.MonthlyDueCalculator
import com.batchfee.edu.domain.ComputedMonthDue
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DueFeeDetail(
    val feeId: String,
    val studentId: String,
    val studentName: String,
    val studentPhone: String?,
    val batchName: String,
    val feePeriod: String,
    val dueAmount: Double,
    val totalAmount: Double,
    val paidAmount: Double,
    val dueDateMs: Long,
    val status: String,
    val studentStatus: String
)

data class MonthWiseDue(
    val monthLabel: String,
    val studentCount: Int,
    val totalDue: Double
)

/**
 * A single Room snapshot for the due report.  Keeping all four sources in the
 * same snapshot prevents a late student/batch sync from leaving the report
 * behind the dashboard total.
 */
private data class DueFeeDataSnapshot(
    val fees: List<FeeEntity>,
    val students: List<com.batchfee.edu.data.models.StudentEntity>,
    val batches: List<com.batchfee.edu.data.models.BatchEntity>,
    val billingEnrollments: List<com.batchfee.edu.data.models.BatchStudentEntity>
)

private data class DueFeeReport(
    val details: List<DueFeeDetail>,
    val totalDue: Double,
    val monthWiseDues: List<MonthWiseDue>
)

class FeeViewModel(private val db: AppDatabase) : ViewModel() {
    private val feeRepository = FeeCollectionRepository(db)
    private val monthlyRepairAttemptedStudentIds = mutableSetOf<String>()

    private val _feeList = MutableStateFlow<List<FeeEntity>>(emptyList())
    val feeList = _feeList.asStateFlow()

    private val _dueFeeList = MutableStateFlow<List<FeeEntity>>(emptyList())
    val dueFeeList = _dueFeeList.asStateFlow()

    private val _totalCollected = MutableStateFlow(0.0)
    val totalCollected = _totalCollected.asStateFlow()

    private val _dueFeesWithDetails = MutableStateFlow<List<DueFeeDetail>>(emptyList())
    val dueFeesWithDetails = _dueFeesWithDetails.asStateFlow()

    private val _totalDueAmount = MutableStateFlow(0.0)
    val totalDueAmount = _totalDueAmount.asStateFlow()

    private val _monthWiseDues = MutableStateFlow<List<MonthWiseDue>>(emptyList())
    val monthWiseDues = _monthWiseDues.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            launch {
                combine(
                    db.feeDao().getAllFees(instId),
                    db.studentDao().getStudentsByInstitute(instId),
                    db.batchDao().getBatchesByInstitute(instId),
                    db.batchStudentDao().getBillingEnrollmentsForInstitute(instId)
                ) { fees, students, batches, billingEnrollments ->
                    DueFeeDataSnapshot(
                        fees = fees,
                        students = students,
                        batches = batches,
                        billingEnrollments = billingEnrollments
                    )
                }.collectLatest { snapshot ->
                    // Do all aggregation off the UI thread. collectLatest
                    // ensures an older Room snapshot can never publish after
                    // a newer Firebase sync has arrived.
                    val report = withContext(Dispatchers.Default) {
                        calculateDueFeeReport(snapshot)
                    }
                    _feeList.value = snapshot.fees
                    _dueFeeList.value = snapshot.fees.filter { it.dueAmount > 0.0 }
                    _dueFeesWithDetails.value = report.details
                    _totalDueAmount.value = report.totalDue
                    _monthWiseDues.value = report.monthWiseDues
                    scheduleLegacyMonthlyFeeReconciliation(instId, snapshot)
                }
            }
            launch {
                db.feeDao().getTotalCollected(instId).collect { _totalCollected.value = it ?: 0.0 }
            }
        }
    }

    private fun calculateDueFeeReport(snapshot: DueFeeDataSnapshot): DueFeeReport {
        val fees = snapshot.fees
        val allStudents = snapshot.students.associateBy { it.id }
        val allBatches = snapshot.batches.associateBy { it.id }
        val enrollmentsByStudent = snapshot.billingEnrollments.groupBy { it.studentId }
        fun isEligibleMonthlyFee(fee: FeeEntity): Boolean {
            val student = allStudents[fee.studentId] ?: return false
            return enrollmentsByStudent[student.id].orEmpty()
                .filter { it.batchId == fee.batchId }
                .any { enrollment ->
                    MonthlyDueCalculator.isMonthlyFeeWithinEnrollmentWindow(
                        feePeriod = fee.feePeriod,
                        studentAdmissionDateMs = student.admissionDateMs,
                        enrollmentJoinedAtMs = enrollment.joinedAtMs,
                        firstMonthFeePeriod = enrollment.firstMonthFeePeriod,
                        billingEndedAtMs = enrollment.leftAtMs
                    )
                }
        }
        var total = 0.0
        val details = mutableListOf<DueFeeDetail>()

        // A real fee record is already an owner-approved charge. Show every
        // outstanding one-time fee immediately, not only after its month ends.
        fees.filter {
            !MonthlyDueCalculator.isMonthlyFeeType(it.feeType)
            && it.dueAmount > 0.0
        }.forEach { fee ->
            val student = allStudents[fee.studentId] ?: return@forEach
            val batchName = fee.batchId?.let { allBatches[it]?.name } ?: ""
            total += fee.dueAmount
            details += DueFeeDetail(
                feeId = fee.id, studentId = fee.studentId,
                studentName = student.fullName, studentPhone = student.phone,
                batchName = batchName, feePeriod = fee.feePeriod,
                dueAmount = fee.dueAmount, totalAmount = fee.totalAmount,
                paidAmount = fee.paidAmount, dueDateMs = fee.dueDateMs,
                status = fee.status, studentStatus = student.status
            )
        }

        // Pre-created installment records (including an advance that covers
        // several months) keep their original amount and fee ID. They become
        // due only when the final month in the saved period has completed.
        fees.filter {
            it.dueAmount > 0.0 &&
                MonthlyDueCalculator.isMonthlyInstallmentDue(it.feeType, it.feePeriod) &&
                isEligibleMonthlyFee(it)
        }.forEach { fee ->
            val student = allStudents[fee.studentId] ?: return@forEach
            val batchName = fee.batchId?.let { allBatches[it]?.name } ?: ""
            total += fee.dueAmount
            details += DueFeeDetail(
                feeId = fee.id, studentId = fee.studentId,
                studentName = student.fullName, studentPhone = student.phone,
                batchName = batchName, feePeriod = fee.feePeriod,
                dueAmount = fee.dueAmount, totalAmount = fee.totalAmount,
                paidAmount = fee.paidAmount, dueDateMs = fee.dueDateMs,
                status = fee.status, studentStatus = student.status
            )
        }

        // Monthly dues include a removed enrollment through its last completed
        // month, so a batch shift can never hide an old balance.
        allStudents.values.forEach { student ->
            enrollmentsByStudent[student.id].orEmpty().forEach { enrollment ->
                val batch = allBatches[enrollment.batchId] ?: return@forEach
                if (batch.monthlyFeeAmount <= 0.0) return@forEach
                val billingStartMs = MonthlyDueCalculator.effectiveBillingStartMs(
                    student.admissionDateMs,
                    enrollment.joinedAtMs,
                    enrollment.firstMonthFeePeriod
                )
                val batchFees = fees.filter {
                    it.studentId == student.id && it.batchId == batch.id &&
                        MonthlyDueCalculator.isMonthlyFeeType(it.feeType)
                }
                val items = MonthlyDueCalculator.computeMonthlyOutstandingItems(
                    admissionDateMs = billingStartMs,
                    monthlyFeeAmount = batch.monthlyFeeAmount,
                    batchId = batch.id,
                    batchName = batch.name,
                    existingMonthlyFees = batchFees,
                    firstMonthFeePeriod = enrollment.firstMonthFeePeriod,
                    firstMonthFeeAmount = enrollment.firstMonthFeeAmount,
                    customMonthlyFeeAmount = enrollment.customMonthlyFeeAmount,
                    customFeeEffectiveFromPeriod = enrollment.customFeeEffectiveFromPeriod,
                    billingEndedAtMs = enrollment.leftAtMs
                )
                items.forEach { item ->
                    total += item.outstanding
                    details += DueFeeDetail(
                        feeId = "",
                        studentId = student.id,
                        studentName = student.fullName,
                        studentPhone = student.phone,
                        batchName = item.batchName,
                        feePeriod = item.period,
                        dueAmount = item.outstanding,
                        totalAmount = item.monthlyFeeAmount,
                        paidAmount = item.paidAmount,
                        dueDateMs = 0L,
                        status = if (item.paidAmount > 0.0) "partially_paid" else "unpaid",
                        studentStatus = student.status
                    )
                }

                val admissionAlreadyCreated = fees.any { fee ->
                    fee.studentId == student.id && fee.batchId == batch.id &&
                        fee.feeType.equals("admission_fee", ignoreCase = true)
                }
                if (enrollment.status.equals("active", ignoreCase = true) &&
                    batch.admissionFeeAmount > 0.0 && !admissionAlreadyCreated) {
                    total += batch.admissionFeeAmount
                    details += DueFeeDetail(
                        feeId = "", studentId = student.id,
                        studentName = student.fullName, studentPhone = student.phone,
                        batchName = batch.name, feePeriod = "Admission",
                        dueAmount = batch.admissionFeeAmount,
                        totalAmount = batch.admissionFeeAmount,
                        paidAmount = 0.0, dueDateMs = 0L,
                        status = "unpaid", studentStatus = student.status
                    )
                }
            }
        }

        return DueFeeReport(
            details = details,
            totalDue = total,
            monthWiseDues = buildMonthWiseDues(details)
        )
    }

    /**
     * Old demo/legacy rows can predate both the student's admission and their
     * batch join. The UI never counts them, then this owner-authorized repair
     * removes only fully unpaid invalid rows from the cloud and Room.
     */
    private fun scheduleLegacyMonthlyFeeReconciliation(
        instituteId: String,
        snapshot: DueFeeDataSnapshot
    ) {
        val studentsById = snapshot.students.associateBy { it.id }
        val enrollmentsByStudent = snapshot.billingEnrollments.groupBy { it.studentId }
        val candidateStudentIds = snapshot.fees.asSequence()
            .filter { it.dueAmount > 0.0 && MonthlyDueCalculator.isMonthlyFeeType(it.feeType) }
            .mapNotNull { fee ->
                val student = studentsById[fee.studentId] ?: return@mapNotNull null
                val validForAnyEnrollment = enrollmentsByStudent[student.id].orEmpty()
                    .filter { it.batchId == fee.batchId }
                    .any { enrollment ->
                        MonthlyDueCalculator.isMonthlyFeeWithinEnrollmentWindow(
                            feePeriod = fee.feePeriod,
                            studentAdmissionDateMs = student.admissionDateMs,
                            enrollmentJoinedAtMs = enrollment.joinedAtMs,
                            firstMonthFeePeriod = enrollment.firstMonthFeePeriod,
                            billingEndedAtMs = enrollment.leftAtMs
                        )
                    }
                fee.studentId.takeUnless { validForAnyEnrollment }
            }
            .toSet()

        candidateStudentIds.forEach { studentId ->
            if (!monthlyRepairAttemptedStudentIds.add(studentId)) return@forEach
            viewModelScope.launch {
                runCatching {
                    feeRepository.reconcileInvalidMonthlyFees(instituteId, studentId)
                }
            }
        }
    }

    private fun buildMonthWiseDues(details: List<DueFeeDetail>): List<MonthWiseDue> {
        val monthOrder = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"
        )
        return details.groupBy { it.feePeriod }
            .map { (month, list) ->
                MonthWiseDue(
                    monthLabel = month,
                    studentCount = list.distinctBy { it.studentId }.size,
                    totalDue = list.sumOf { it.dueAmount }
                )
            }
            .sortedWith(compareBy { item ->
                val parts = item.monthLabel.split("\\s+".toRegex())
                val year = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val monthIdx = monthOrder.indexOfFirst {
                    parts.firstOrNull().orEmpty().equals(it, ignoreCase = true)
                }.let { if (it >= 0) it else Int.MAX_VALUE }
                year * 100 + monthIdx
            })
    }

    fun sendDueNotification(
        context: Context,
        studentName: String,
        phone: String?,
        dueAmount: Double,
        feePeriod: String,
        channel: String
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val dateLabel = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        viewModelScope.launch {
            val instituteSignature = loadInstituteSignature(db, instId)
            val msg = appendInstituteSignature(
                "Dear Parent, $studentName has a pending fee of BDT ${"%.0f".format(dueAmount)} for $feePeriod as of $dateLabel. Please pay at your earliest convenience.",
                instituteSignature
            )
            try {
            when (channel) {
                "whatsapp" -> {
                    val number = phone?.replace("+", "")?.replace(" ", "")?.replace("-", "")
                    val encoded = URLEncoder.encode(msg, "UTF-8")
                    val url = if (!number.isNullOrBlank()) {
                        "https://wa.me/$number?text=$encoded"
                    } else {
                        "https://wa.me/?text=$encoded"
                    }
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                "sms" -> {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phone ?: ""}"))
                            .apply { putExtra("sms_body", msg) }
                    )
                }
            }
            } catch (_: Exception) {
            }
        }
    }

    fun createFee(
        studentId: String,
        batchId: String?,
        feePeriod: String,
        feeType: String,
        dueDateMs: Long,
        baseAmount: Double,
        discount: Double,
        lateFee: Double,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: run {
            onError("No active institute session.")
            return
        }
        viewModelScope.launch {
            try {
                feeRepository.createFee(
                    instituteId = instId,
                    studentId = studentId,
                    batchId = batchId,
                    feePeriod = feePeriod,
                    feeType = feeType,
                    dueDateMs = dueDateMs,
                    baseAmount = baseAmount,
                    discountAmount = discount,
                    lateFeeAmount = lateFee
                )
                StaffActivityLogger.logCompletedAction(
                    db, "fee_created", "fees", "Created a fee record for $feePeriod"
                )
                onSuccess()
            } catch (_: IllegalArgumentException) {
                onError("Unable to create fee. Please check the values and try again.")
            } catch (e: Exception) {
                onError(e.message ?: "Unable to create fee.")
            }
        }
    }

    fun collectPayment(
        feeId: String,
        amount: Double,
        paymentMethod: String,
        note: String?,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: run {
            onError("No active institute session.")
            return
        }
        val userId = SessionManager.currentUserId.value ?: run {
            onError("No active user session.")
            return
        }
        if (amount <= 0.0) {
            onError("Amount must be greater than zero.")
            return
        }
        viewModelScope.launch {
            try {
                val result = feeRepository.collectPayment(
                    instituteId = instId,
                    collectedByUserId = userId,
                    feeId = feeId,
                    amount = amount,
                    paymentMethod = paymentMethod,
                    note = note
                )
                StaffActivityLogger.logCompletedAction(
                    db, "payment_collected", "fees", "Collected BDT ${amount.toLong()} by $paymentMethod"
                )
                onSuccess(result.paymentId)
            } catch (e: FinancialOperationPendingException) {
                onError(e.message ?: "Payment is pending reconciliation. Do not retry it.")
            } catch (e: IllegalArgumentException) {
                onError(e.message ?: "Payment rejected.")
            } catch (e: Exception) {
                onError("Payment failed. Please try again.")
            }
        }
    }

    fun updateFeeAndCollectPayment(
        feeId: String,
        newBaseAmount: Double,
        discountPercent: Double,
        collectedAmount: Double,
        paymentMethod: String,
        feePeriod: String,
        note: String? = null,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: run {
            onError("No active institute session.")
            return
        }
        val userId = SessionManager.currentUserId.value ?: run {
            onError("No active user session.")
            return
        }
        if (collectedAmount <= 0.0) {
            onError("Amount must be greater than zero.")
            return
        }
        viewModelScope.launch {
            try {
                val result = feeRepository.updateFeeAndCollectPayment(
                    instituteId = instId,
                    collectedByUserId = userId,
                    feeId = feeId,
                    newBaseAmount = newBaseAmount,
                    discountPercent = discountPercent,
                    collectedAmount = collectedAmount,
                    paymentMethod = paymentMethod,
                    feePeriod = feePeriod,
                    note = note
                )
                StaffActivityLogger.logCompletedAction(
                    db, "payment_collected", "fees", "Updated a fee and collected BDT ${collectedAmount.toLong()} by $paymentMethod"
                )
                onSuccess(result.paymentId)
            } catch (e: FinancialOperationPendingException) {
                onError(e.message ?: "Payment is pending reconciliation. Do not retry it.")
            } catch (e: IllegalArgumentException) {
                onError(e.message ?: "Payment rejected.")
            } catch (e: Exception) {
                onError("Payment failed. Please try again.")
            }
        }
    }
}

class FeeViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FeeViewModel::class.java)) {
            return FeeViewModel(db) as T
        }
        throw IllegalArgumentException()
    }
}

