package com.example.ui.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.firestore.ExpenseSyncHelper
import com.example.data.firestore.InstituteCacheRefreshManager
import com.example.data.models.ExpenseEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID

data class ExpenseSummary(
    val todayExpense: Double = 0.0,
    val monthExpense: Double = 0.0,
    val lifetimeExpense: Double = 0.0
)

class ExpenseViewModel(private val db: AppDatabase) : ViewModel() {
    private val _expenses = MutableStateFlow<List<ExpenseEntity>>(emptyList())
    val expenses = _expenses.asStateFlow()

    private val _summary = MutableStateFlow(ExpenseSummary())
    val summary = _summary.asStateFlow()

    init { loadExpenses() }

    private fun loadExpenses() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
            db.expenseDao().getExpensesByInstitute(instId).collect { list ->
                _expenses.value = list
                computeSummary(list)
            }
        }
    }

    private fun computeSummary(list: List<ExpenseEntity>) {
        val now = Calendar.getInstance()
        val startOfDay = now.clone() as Calendar
        startOfDay.set(Calendar.HOUR_OF_DAY, 0)
        startOfDay.set(Calendar.MINUTE, 0)
        startOfDay.set(Calendar.SECOND, 0)
        startOfDay.set(Calendar.MILLISECOND, 0)

        val startOfMonth = now.clone() as Calendar
        startOfMonth.set(Calendar.DAY_OF_MONTH, 1)
        startOfMonth.set(Calendar.HOUR_OF_DAY, 0)
        startOfMonth.set(Calendar.MINUTE, 0)
        startOfMonth.set(Calendar.SECOND, 0)
        startOfMonth.set(Calendar.MILLISECOND, 0)

        val today = list.filter { it.expenseDateMs >= startOfDay.timeInMillis }.sumOf { it.amount }
        val month = list.filter { it.expenseDateMs >= startOfMonth.timeInMillis }.sumOf { it.amount }
        val lifetime = list.sumOf { it.amount }
        _summary.value = ExpenseSummary(today, month, lifetime)
    }

    fun addExpense(
        title: String, category: String, amount: Double, expenseDateMs: Long,
        paymentMethod: String?, description: String?,
        onSuccess: () -> Unit, onError: (String) -> Unit = {}
    ) {
        val instId = SessionManager.currentInstituteId.value
        if (instId == null) { onError("No institute selected. Please log in again."); return }
        val currentUserId = SessionManager.currentUserId.value
        if (currentUserId == null) { onError("No user session found. Please log in again."); return }
        if (title.isBlank()) { onError("Title is required."); return }
        if (category.isBlank()) { onError("Category is required."); return }
        if (amount <= 0) { onError("Amount must be greater than 0."); return }

        val expense = ExpenseEntity(
            id = UUID.randomUUID().toString(), instituteId = instId,
            category = category, title = title.trim(), amount = amount,
            expenseDateMs = expenseDateMs,
            paymentMethod = paymentMethod?.trim()?.takeIf { it.isNotEmpty() },
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            attachmentUri = null, createdByUserId = currentUserId,
            createdAtMs = System.currentTimeMillis(), updatedAtMs = System.currentTimeMillis(),
            archivedAtMs = null
        )
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ExpenseSyncHelper.upsertExpense(expense)
                    db.expenseDao().insertExpense(expense)
                }
                onSuccess()
            } catch (e: Exception) {
                onError("Failed to save: ${e.message}")
            }
        }
    }
}

class ExpenseViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ExpenseViewModel::class.java)) return ExpenseViewModel(db) as T
        throw IllegalArgumentException()
    }
}
