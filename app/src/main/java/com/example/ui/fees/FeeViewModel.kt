package com.example.ui.fees

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.models.FeeEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ── Enriched pending-due data for the Collection Fee dashboard ──
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
    val status: String
)

data class MonthWiseDue(
    val monthLabel: String,
    val studentCount: Int,
    val totalDue: Double
)

class FeeViewModel(private val db: AppDatabase) : ViewModel() {
    private val _feeList = MutableStateFlow<List<FeeEntity>>(emptyList())
    val feeList = _feeList.asStateFlow()

    private val _dueFeeList = MutableStateFlow<List<FeeEntity>>(emptyList())
    val dueFeeList = _dueFeeList.asStateFlow()
    
    private val _totalCollected = MutableStateFlow(0.0)
    val totalCollected = _totalCollected.asStateFlow()

    // ── Enriched due fees with student + batch details ──────
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
            launch {
                db.feeDao().getAllFees(instId).collect { _feeList.value = it }
            }
            launch {
                db.feeDao().getDueFees(instId).collect { fees ->
                    _dueFeeList.value = fees
                    enrichDueFees(instId, fees)
                }
            }
            launch {
                db.feeDao().getTotalCollected(instId).collect { _totalCollected.value = it ?: 0.0 }
            }
        }
    }

    // ── Enrich due fees with student name/phone and batch name ──
    private suspend fun enrichDueFees(instId: String, fees: List<FeeEntity>) {
        val details = mutableListOf<DueFeeDetail>()
        var total = 0.0
        for (fee in fees) {
            val student = db.studentDao().getStudentById(fee.studentId, instId).firstOrNull()
            var batchName = ""
            fee.batchId?.let { bid ->
                db.batchDao().getBatchById(bid, instId).firstOrNull()?.let { batchName = it.name }
            }
            details.add(DueFeeDetail(
                feeId = fee.id,
                studentId = fee.studentId,
                studentName = student?.fullName ?: "Unknown",
                studentPhone = student?.phone,
                batchName = batchName,
                feePeriod = fee.feePeriod,
                dueAmount = fee.dueAmount,
                totalAmount = fee.totalAmount,
                paidAmount = fee.paidAmount,
                dueDateMs = fee.dueDateMs,
                status = fee.status
            ))
            total += fee.dueAmount
        }
        _dueFeesWithDetails.value = details
        _totalDueAmount.value = total
        _monthWiseDues.value = buildMonthWiseDues(details)
    }

    // ── Month-wise grouping ─────────────────────────────────
    private fun buildMonthWiseDues(details: List<DueFeeDetail>): List<MonthWiseDue> {
        val grouped = details.groupBy { it.feePeriod }
            .map { (month, list) ->
                MonthWiseDue(
                    monthLabel = month,
                    studentCount = list.distinctBy { it.studentId }.size,
                    totalDue = list.sumOf { it.dueAmount }
                )
            }
            .sortedBy { it.monthLabel }
        return grouped
    }

    // ── SMS / WhatsApp notification launchers ───────────────
    fun sendDueNotification(
        context: Context,
        studentName: String,
        phone: String?,
        dueAmount: Double,
        feePeriod: String,
        channel: String
    ) {
        val dateLabel = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        val msg = "Dear Parent, ${studentName} has a pending fee of BDT ${"%.0f".format(dueAmount)} for ${feePeriod} as of ${dateLabel}. Please pay at your earliest convenience. \u2013 BatchFee"
        try {
            when (channel) {
                "whatsapp" -> {
                    val number = phone?.replace("+", "")?.replace(" ", "")?.replace("-", "")
                    val encoded = URLEncoder.encode(msg, "UTF-8")
                    val url = if (!number.isNullOrBlank()) "https://wa.me/$number?text=$encoded"
                    else "https://wa.me/?text=$encoded"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                "sms" -> {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phone ?: ""}"))
                            .apply { putExtra("sms_body", msg) }
                    )
                }
            }
        } catch (_: Exception) { }
    }

    fun createFee(studentId: String, batchId: String?, feePeriod: String, feeType: String, dueDateMs: Long, baseAmount: Double, discount: Double, lateFee: Double) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val total = baseAmount - discount + lateFee
        if (total < 0) return
        val fee = FeeEntity(
            id = UUID.randomUUID().toString(),
            instituteId = instId,
            studentId = studentId,
            batchId = batchId,
            feePeriod = feePeriod,
            feeType = feeType,
            dueDateMs = dueDateMs,
            baseAmount = baseAmount,
            discountAmount = discount,
            lateFeeAmount = lateFee,
            totalAmount = total,
            paidAmount = 0.0,
            dueAmount = total,
            status = "unpaid",
            note = null,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            cancelledAtMs = null
        )
        viewModelScope.launch {
            db.feeDao().insertFee(fee)
        }
    }

    fun collectPayment(feeId: String, amount: Double, paymentMethod: String, note: String?, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val userId = SessionManager.currentUserId.value ?: return
        if (amount <= 0.0) {
            onError("Amount must be greater than zero.")
            return
        }
        viewModelScope.launch {
            val fee = db.feeDao().getFeeById(feeId, instId)
            if (fee == null) {
                onError("Fee not found.")
                return@launch
            }
            if (fee.cancelledAtMs != null) {
                onError("Fee is cancelled.")
                return@launch
            }
            if (amount > fee.dueAmount) {
                onError("Payment amount exceeds due amount.")
                return@launch
            }

            val paymentId = UUID.randomUUID().toString()
            val receiptNumber = "REC-${System.currentTimeMillis()}"
            val payment = com.example.data.models.PaymentEntity(
                id = paymentId,
                instituteId = instId,
                feeId = feeId,
                studentId = fee.studentId,
                amount = amount,
                paymentMethod = paymentMethod,
                transactionId = null,
                receiptNumber = receiptNumber,
                paymentDateMs = System.currentTimeMillis(),
                collectedByUserId = userId,
                status = "completed",
                note = note,
                createdAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis()
            )
            db.paymentDao().insertPayment(payment)

            val newPaid = fee.paidAmount + amount
            val newDue = fee.totalAmount - newPaid
            val newStatus = if (newDue <= 0.0) "paid" else "partially_paid"

            val updatedFee = fee.copy(
                paidAmount = newPaid,
                dueAmount = newDue,
                status = newStatus,
                updatedAtMs = System.currentTimeMillis()
            )
            db.feeDao().updateFee(updatedFee)

            val receipt = com.example.data.models.ReceiptEntity(
                id = UUID.randomUUID().toString(),
                instituteId = instId,
                paymentId = paymentId,
                feeId = fee.id,
                studentId = fee.studentId,
                receiptNumber = receiptNumber,
                receiptDateMs = System.currentTimeMillis(),
                totalAmount = fee.totalAmount,
                paidAmount = newPaid,
                dueAmount = newDue,
                paymentMethod = paymentMethod,
                receiptText = "Payment of $amount received.",
                createdAtMs = System.currentTimeMillis()
            )
            db.receiptDao().insertReceipt(receipt)

            onSuccess(paymentId)
        }
    }

    fun updateFeeAndCollectPayment(
        feeId: String,
        newBaseAmount: Double,
        discountPercent: Double,
        collectedAmount: Double,
        paymentMethod: String,
        feePeriod: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val userId = SessionManager.currentUserId.value ?: return
        if (collectedAmount <= 0.0) {
            onError("Amount must be greater than zero.")
            return
        }
        viewModelScope.launch {
            val fee = db.feeDao().getFeeById(feeId, instId)
            if (fee == null) { onError("Fee not found."); return@launch }
            if (fee.cancelledAtMs != null) { onError("Fee is cancelled."); return@launch }

            val discountAmount = newBaseAmount * discountPercent / 100.0
            val totalAmount = newBaseAmount - discountAmount
            val alreadyPaid = fee.paidAmount
            val dueAfterDiscount = totalAmount - alreadyPaid

            if (collectedAmount > dueAfterDiscount + 0.001) {
                onError("Payment amount exceeds due amount.")
                return@launch
            }

            val newPaid = alreadyPaid + collectedAmount
            val newDue = totalAmount - newPaid
            val newStatus = if (newDue <= 0.001) "paid" else "partially_paid"

            val updatedFee = fee.copy(
                baseAmount = newBaseAmount,
                discountAmount = discountAmount,
                totalAmount = totalAmount,
                paidAmount = newPaid,
                dueAmount = newDue.coerceAtLeast(0.0),
                status = newStatus,
                feePeriod = feePeriod,
                updatedAtMs = System.currentTimeMillis()
            )
            db.feeDao().updateFee(updatedFee)

            val paymentId = UUID.randomUUID().toString()
            val receiptNumber = "REC-${System.currentTimeMillis()}"
            val payment = com.example.data.models.PaymentEntity(
                id = paymentId,
                instituteId = instId,
                feeId = feeId,
                studentId = fee.studentId,
                amount = collectedAmount,
                paymentMethod = paymentMethod,
                transactionId = null,
                receiptNumber = receiptNumber,
                paymentDateMs = System.currentTimeMillis(),
                collectedByUserId = userId,
                status = "completed",
                note = null,
                createdAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis()
            )
            db.paymentDao().insertPayment(payment)

            val receipt = com.example.data.models.ReceiptEntity(
                id = UUID.randomUUID().toString(),
                instituteId = instId,
                paymentId = paymentId,
                feeId = fee.id,
                studentId = fee.studentId,
                receiptNumber = receiptNumber,
                receiptDateMs = System.currentTimeMillis(),
                totalAmount = totalAmount,
                paidAmount = newPaid,
                dueAmount = newDue.coerceAtLeast(0.0),
                paymentMethod = paymentMethod,
                receiptText = buildString {
                    append("Receipt: $receiptNumber\n")
                    append("Period: $feePeriod\n")
                    append("Base: BDT $newBaseAmount\n")
                    append("Discount (${discountPercent.toInt()}%): BDT ${"%.2f".format(discountAmount)}\n")
                    append("Payable: BDT ${"%.2f".format(totalAmount)}\n")
                    append("Paid: BDT ${"%.2f".format(newPaid)}\n")
                    append("Due: BDT ${"%.2f".format(newDue.coerceAtLeast(0.0))}\n")
                    append("Collected Now: BDT ${"%.2f".format(collectedAmount)}")
                },
                createdAtMs = System.currentTimeMillis()
            )
            db.receiptDao().insertReceipt(receipt)

            onSuccess(paymentId)
        }
    }
}

class FeeViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FeeViewModel::class.java)) return FeeViewModel(db) as T
        throw IllegalArgumentException()
    }
}
