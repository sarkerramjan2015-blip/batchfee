package com.example.ui.batches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.models.BatchEntity
import com.example.data.models.FeeEntity
import com.example.data.models.PaymentEntity
import com.example.data.models.StudentEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// Holds a student + their fee status in a single batch
data class BatchStudentWithFee(
    val student: StudentEntity,
    val fee: FeeEntity? // null = no fee created yet for this student in this batch
) {
    val feeStatus: String get() = fee?.status ?: "no_fee"
    val paidAmount: Double get() = fee?.paidAmount ?: 0.0
    val dueAmount: Double get() = fee?.dueAmount ?: 0.0
    val totalAmount: Double get() = fee?.totalAmount ?: 0.0

    /** Returns how many months this student is behind (e.g. 3 = 3 months due). */
    fun monthsDue(monthlyFee: Double): Int {
        if (monthlyFee <= 0 || dueAmount <= 0) return 0
        return kotlin.math.ceil(dueAmount / monthlyFee).toInt()
    }

    /** Checks if the fee period matches the current month (e.g. "May 2026" matches May 2026). */
    fun isCurrentMonth(): Boolean {
        val f = fee ?: return false
        return f.feePeriod.equals(currentMonthPeriod(), ignoreCase = true)
    }
}

/** Returns "MMM yyyy" for current date (e.g. "May 2026"). */
fun currentMonthPeriod(): String {
    val cal = Calendar.getInstance()
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${names[cal.get(Calendar.MONTH)]} ${cal.get(Calendar.YEAR)}"
}

class BatchPaymentViewModel(private val db: AppDatabase) : ViewModel() {
    private val _batch = MutableStateFlow<BatchEntity?>(null)
    val batch = _batch.asStateFlow()

    private val _studentsWithFee = MutableStateFlow<List<BatchStudentWithFee>>(emptyList())
    val studentsWithFee = _studentsWithFee.asStateFlow()

    private val _totalCollected = MutableStateFlow(0.0)
    val totalCollected = _totalCollected.asStateFlow()

    private val _totalExpected = MutableStateFlow(0.0)
    val totalExpected = _totalExpected.asStateFlow()

    private val _paidCount = MutableStateFlow(0)
    val paidCount = _paidCount.asStateFlow()

    private val _dueCount = MutableStateFlow(0)
    val dueCount = _dueCount.asStateFlow()

    private val _totalEnrolled = MutableStateFlow(0)
    val totalEnrolled = _totalEnrolled.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // ── This-month stats (computed from studentsWithFee) ──────
    private val _paidThisMonthCount = MutableStateFlow(0)
    val paidThisMonthCount = _paidThisMonthCount.asStateFlow()

    private val _dueThisMonthCount = MutableStateFlow(0)
    val dueThisMonthCount = _dueThisMonthCount.asStateFlow()

    // ── Sent-message tracking (prevents duplicate sends) ─────
    private val _sentMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val sentMessageIds = _sentMessageIds.asStateFlow()

    // Per-student send-in-progress
    private val _sendingMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val sendingMessageIds = _sendingMessageIds.asStateFlow()

    // Send-all in progress flag
    private val _isSendingAll = MutableStateFlow(false)
    val isSendingAll = _isSendingAll.asStateFlow()

    fun loadBatchDetail(batchId: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        _isLoading.value = true
        viewModelScope.launch {
            launch {
                db.batchDao().getBatchById(batchId, instId).collect { _batch.value = it }
            }
            launch {
                combine(
                    db.batchStudentDao().getStudentsForBatch(batchId, instId),
                    db.feeDao().getFeesByBatch(batchId, instId)
                ) { students, fees ->
                    students.map { s ->
                        val studentFee = fees.firstOrNull { f -> f.studentId == s.id }
                        BatchStudentWithFee(s, studentFee)
                    }
                }.collect { combined ->
                    _studentsWithFee.value = combined
                    _totalEnrolled.value = combined.size
                    _paidCount.value = combined.count { it.feeStatus == "paid" }
                    _dueCount.value = combined.count { it.dueAmount > 0 }
                    _totalExpected.value = combined.sumOf { it.totalAmount }
                    _totalCollected.value = combined.sumOf { it.paidAmount }
                    _paidThisMonthCount.value = combined.count { it.isCurrentMonth() && it.feeStatus == "paid" }
                    _dueThisMonthCount.value = combined.count { it.isCurrentMonth() && it.dueAmount > 0 }
                    _isLoading.value = false
                }
            }
        }
    }

    fun markSending(studentId: String) {
        _sendingMessageIds.value = _sendingMessageIds.value + studentId
    }

    fun markSent(studentId: String) {
        _sendingMessageIds.value = _sendingMessageIds.value - studentId
        _sentMessageIds.value = _sentMessageIds.value + studentId
    }

    fun markMessageError(studentId: String) {
        _sendingMessageIds.value = _sendingMessageIds.value - studentId
    }

    fun markSendingAll() { _isSendingAll.value = true }

    fun markSentAll() {
        _isSendingAll.value = false
        val dueIds = _studentsWithFee.value.filter { it.dueAmount > 0 }.map { it.student.id }.toSet()
        _sentMessageIds.value = _sentMessageIds.value + dueIds
    }

    fun markSendAllError() { _isSendingAll.value = false }
}

class BatchPaymentViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BatchPaymentViewModel::class.java)) return BatchPaymentViewModel(db) as T
        throw IllegalArgumentException()
    }
}
