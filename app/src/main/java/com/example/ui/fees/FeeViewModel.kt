package com.example.ui.fees

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.models.FeeEntity
import com.example.data.repository.FeeCollectionRepository
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

class FeeViewModel(private val db: AppDatabase) : ViewModel() {
    private val feeRepository = FeeCollectionRepository(db)

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

    private suspend fun enrichDueFees(instId: String, fees: List<FeeEntity>) {
        val allStudents = db.studentDao().getStudentsByInstituteOnce(instId).associateBy { it.id }
        val allBatches = db.batchDao().getBatchesByInstituteOnce(instId).associateBy { it.id }
        var total = 0.0
        val details = fees.map { fee ->
            val student = allStudents[fee.studentId]
            val batchName = fee.batchId?.let { allBatches[it]?.name } ?: ""
            total += fee.dueAmount
            DueFeeDetail(
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
                status = fee.status,
                studentStatus = student?.status ?: "active"
            )
        }
        _dueFeesWithDetails.value = details
        _totalDueAmount.value = total
        _monthWiseDues.value = buildMonthWiseDues(details)
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
        val dateLabel = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        val msg = "Dear Parent, $studentName has a pending fee of BDT ${"%.0f".format(dueAmount)} for $feePeriod as of $dateLabel. Please pay at your earliest convenience. - BatchFee"
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

    fun createFee(
        studentId: String,
        batchId: String?,
        feePeriod: String,
        feeType: String,
        dueDateMs: Long,
        baseAmount: Double,
        discount: Double,
        lateFee: Double
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
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
            } catch (_: IllegalArgumentException) {
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
                onSuccess(result.paymentId)
            } catch (e: IllegalArgumentException) {
                onError(e.message ?: "Payment rejected.")
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
                onSuccess(result.paymentId)
            } catch (e: IllegalArgumentException) {
                onError(e.message ?: "Payment rejected.")
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
