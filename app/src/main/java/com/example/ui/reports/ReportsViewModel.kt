package com.example.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.firestore.InstituteCacheRefreshManager
import com.example.data.models.PaymentEntity
import com.example.data.models.StudentEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class TodayPaymentItem(
    val payment: PaymentEntity,
    val student: StudentEntity?
)

class ReportsViewModel(private val db: AppDatabase) : ViewModel() {
    private val _studentCount = MutableStateFlow(0)
    val studentCount = _studentCount.asStateFlow()

    private val _todayPayments = MutableStateFlow<List<TodayPaymentItem>>(emptyList())
    val todayPayments = _todayPayments.asStateFlow()

    private val _todayTotal = MutableStateFlow(0.0)
    val todayTotal = _todayTotal.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    val todayLabel = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(
        Calendar.getInstance().time
    )

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            _isLoading.value = true
            InstituteCacheRefreshManager.refreshIfStale(db, instId)

            launch {
                db.studentDao().countStudents(instId).collect {
                    _studentCount.value = it
                }
            }

            launch {
                val startOfToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                combine(
                    db.paymentDao().getRecentPayments(instId),
                    db.studentDao().getStudentsByInstitute(instId)
                ) { payments, students ->
                    val todayOnly = payments.filter { it.paymentDateMs >= startOfToday }
                    todayOnly.map { p ->
                        TodayPaymentItem(
                            payment = p,
                            student = students.find { s -> s.id == p.studentId }
                        )
                    }
                }.collect { items ->
                    _todayPayments.value = items.sortedByDescending { it.payment.paymentDateMs }
                    _todayTotal.value = items.sumOf { it.payment.amount }
                    _isLoading.value = false
                }
            }
        }
    }
}

class ReportsViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReportsViewModel::class.java)) return ReportsViewModel(db) as T
        throw IllegalArgumentException()
    }
}
