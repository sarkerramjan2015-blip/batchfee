package com.batchfee.edu.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.models.PaymentEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class PaymentItem(
    val payment: PaymentEntity,
    val student: StudentEntity?
)

data class DayGroup(
    val label: String,        // "Mon, 28 Jul 2026"
    val dateMs: Long,
    val total: Double,
    val count: Int,
    val payments: List<PaymentItem> = emptyList()
)

data class MonthGroup(
    val label: String,        // "Jul 2026"
    val monthKey: String,     // "202607"
    val total: Double,
    val count: Int,
    val days: List<DayGroup> = emptyList()
)

class ReportsViewModel(private val db: AppDatabase, private val period: String = "today") : ViewModel() {
    private val _studentCount = MutableStateFlow(0)
    val studentCount = _studentCount.asStateFlow()

    private val _grandTotal = MutableStateFlow(0.0)
    val grandTotal = _grandTotal.asStateFlow()

    // Today: flat list of payments
    private val _payments = MutableStateFlow<List<PaymentItem>>(emptyList())
    val payments = _payments.asStateFlow()

    // Month: list of days
    private val _dayGroups = MutableStateFlow<List<DayGroup>>(emptyList())
    val dayGroups = _dayGroups.asStateFlow()

    // Lifetime: list of months
    private val _monthGroups = MutableStateFlow<List<MonthGroup>>(emptyList())
    val monthGroups = _monthGroups.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // Drill-down state
    private val _selectedMonth = MutableStateFlow<MonthGroup?>(null)
    val selectedMonth = _selectedMonth.asStateFlow()
    private val _selectedDay = MutableStateFlow<DayGroup?>(null)
    val selectedDay = _selectedDay.asStateFlow()

    val periodLabel: String get() = when (period) {
        "month" -> "This Month"
        "lifetime" -> "Lifetime"
        else -> "Today"
    }

    init { loadData() }

    private fun loadData() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            _isLoading.value = true
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)

            launch { db.studentDao().countStudents(instId).collect { _studentCount.value = it } }

            combine(
                db.paymentDao().getRecentPayments(instId),
                db.studentDao().getStudentsByInstitute(instId)
            ) { payments, students ->
                try {
                    buildData(payments, students)
                } catch (_: Exception) {
                    // Ignore — corrupt data won't crash
                }
            }.collect { _isLoading.value = false }
        }
    }

    private fun buildData(allPayments: List<PaymentEntity>, allStudents: List<StudentEntity>) {
        val studentMap = allStudents.associateBy { it.id }
        val now = Calendar.getInstance() ?: return
        val todayStartMs = now.clone() as Calendar
        todayStartMs.set(Calendar.HOUR_OF_DAY, 0); todayStartMs.set(Calendar.MINUTE, 0); todayStartMs.set(Calendar.SECOND, 0); todayStartMs.set(Calendar.MILLISECOND, 0)
        val todayStart = todayStartMs.timeInMillis
        val monthStartMs = now.clone() as Calendar
        monthStartMs.set(Calendar.DAY_OF_MONTH, 1); monthStartMs.set(Calendar.HOUR_OF_DAY, 0); monthStartMs.set(Calendar.MINUTE, 0); monthStartMs.set(Calendar.SECOND, 0); monthStartMs.set(Calendar.MILLISECOND, 0)
        val monthStart = monthStartMs.timeInMillis

        val sdfDay = SimpleDateFormat("EEE, dd MMM", Locale.ENGLISH)
        val sdfMonth = SimpleDateFormat("MMM yyyy", Locale.ENGLISH)
        val sdfMonthKey = SimpleDateFormat("yyyyMM", Locale.ENGLISH)

        // Reversed payments are audit history, not collected income.
        val completedPayments = allPayments.filter { it.status == "completed" }
        val filtered = when (period) {
            "month" -> completedPayments.filter { it.paymentDateMs > 0 && it.paymentDateMs >= monthStart }
            "lifetime" -> completedPayments.filter { it.paymentDateMs > 0 }
            else -> completedPayments.filter { it.paymentDateMs > 0 && it.paymentDateMs >= todayStart }
        }

        val items = filtered.sortedByDescending { it.paymentDateMs }.map { p ->
            PaymentItem(p, studentMap[p.studentId])
        }

        when (period) {
            "today" -> {
                _payments.value = items
                _grandTotal.value = items.sumOf { it.payment.amount }
            }
            "month" -> {
                val dayMap = linkedMapOf<Long, MutableList<PaymentItem>>()
                items.forEach { item ->
                    val dayStart = Calendar.getInstance().apply {
                        timeInMillis = item.payment.paymentDateMs
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    dayMap.getOrPut(dayStart) { mutableListOf() }.add(item)
                }
                val groups = dayMap.map { (dayMs, list) ->
                    DayGroup(sdfDay.format(dayMs), dayMs, list.sumOf { it.payment.amount }, list.size, list)
                }.sortedByDescending { it.dateMs }
                _dayGroups.value = groups
                _grandTotal.value = groups.sumOf { it.total }
            }
            "lifetime" -> {
                val monthMap = linkedMapOf<String, MutableList<PaymentItem>>()
                items.forEach { item ->
                    val mk = sdfMonthKey.format(item.payment.paymentDateMs)
                    monthMap.getOrPut(mk) { mutableListOf() }.add(item)
                }
                val groups = monthMap.map { (mk, list) ->
                    val sample = list.first().payment.paymentDateMs
                    // Build day groups for this month
                    val dayMap = linkedMapOf<Long, MutableList<PaymentItem>>()
                    list.forEach { item ->
                        val dayStart = Calendar.getInstance().apply {
                            timeInMillis = item.payment.paymentDateMs
                            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        dayMap.getOrPut(dayStart) { mutableListOf() }.add(item)
                    }
                    val days = dayMap.map { (dm, dl) ->
                        DayGroup(sdfDay.format(dm), dm, dl.sumOf { it.payment.amount }, dl.size, dl)
                    }.sortedByDescending { it.dateMs }
                    MonthGroup(sdfMonth.format(sample), mk, list.sumOf { it.payment.amount }, list.size, days)
                }.sortedByDescending { it.monthKey }
                _monthGroups.value = groups
                _grandTotal.value = groups.sumOf { it.total }
            }
        }
    }

    fun drillIntoMonth(month: MonthGroup) { _selectedMonth.value = month }
    fun drillIntoDay(day: DayGroup) { _selectedDay.value = day }
    fun goBack() {
        if (_selectedDay.value != null) _selectedDay.value = null
        else _selectedMonth.value = null
    }

    val isDrilledIn: Boolean get() = _selectedMonth.value != null
    val drillTitle: String get() = _selectedDay.value?.label ?: _selectedMonth.value?.label ?: periodLabel
}

class ReportsViewModelFactory(private val db: AppDatabase, private val period: String = "today") : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReportsViewModel::class.java)) return ReportsViewModel(db, period) as T
        throw IllegalArgumentException()
    }
}
