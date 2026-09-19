package com.batchfee.edu.ui.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.audit.StaffActivityLogger
import com.batchfee.edu.data.firestore.ExpenseSyncHelper
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.models.ExpenseEntity
import com.batchfee.edu.domain.SessionManager
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
    private val mutationsInProgress = mutableSetOf<String>()
    private val _expenses = MutableStateFlow<List<ExpenseEntity>>(emptyList())
    val expenses = _expenses.asStateFlow()

    private val _summary = MutableStateFlow(ExpenseSummary())
    val summary = _summary.asStateFlow()

    init { loadExpenses() }

    private fun loadExpenses() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
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

    /** Loads one expense for the edit screen. */
    suspend fun getExpense(expenseId: String): ExpenseEntity? = withContext(Dispatchers.IO) {
        val instId = SessionManager.currentInstituteId.value ?: return@withContext null
        db.expenseDao().getExpenseById(expenseId, instId)
    }

    /**
     * Edits an existing expense in place. The original id, institute, creator
     * and creation timestamp are preserved; only editable fields change.
     */
    fun updateExpense(
        expenseId: String,
        title: String, category: String, amount: Double, expenseDateMs: Long,
        paymentMethod: String?, description: String?,
        onSuccess: () -> Unit, onError: (String) -> Unit = {}
    ) {
        val instId = SessionManager.currentInstituteId.value
        if (instId == null) { onError("No institute selected. Please log in again."); return }
        if (title.isBlank()) { onError("Title is required."); return }
        if (category.isBlank()) { onError("Category is required."); return }
        if (amount <= 0) { onError("Amount must be greater than 0."); return }
        val mutationKey = "update:$expenseId"
        if (!synchronized(mutationsInProgress) { mutationsInProgress.add(mutationKey) }) {
            onError("This expense is already being saved.")
            return
        }
        viewModelScope.launch {
            try {
                val existing = withContext(Dispatchers.IO) { db.expenseDao().getExpenseById(expenseId, instId) }
                    ?: run { onError("This expense no longer exists."); return@launch }
                val updated = existing.copy(
                    title = title.trim(),
                    category = category,
                    amount = amount,
                    expenseDateMs = expenseDateMs,
                    paymentMethod = paymentMethod?.trim()?.takeIf { it.isNotEmpty() },
                    description = description?.trim()?.takeIf { it.isNotEmpty() },
                    updatedAtMs = System.currentTimeMillis()
                )
                withContext(Dispatchers.IO) {
                    ExpenseSyncHelper.upsertExpense(updated)
                    db.expenseDao().updateExpense(updated)
                }
                StaffActivityLogger.logCompletedAction(
                    db, "expense_updated", "expenses", "Updated expense ${updated.title} · BDT ${amount.toLong()}"
                )
                onSuccess()
            } catch (e: Exception) {
                onError("Failed to update: ${e.message}")
            } finally {
                synchronized(mutationsInProgress) { mutationsInProgress.remove(mutationKey) }
            }
        }
    }

    /** Permanently deletes an expense locally and from the institute cloud collection. */
    fun deleteExpense(
        expense: ExpenseEntity,
        onSuccess: () -> Unit, onError: (String) -> Unit = {}
    ) {
        val mutationKey = "delete:${expense.id}"
        if (!synchronized(mutationsInProgress) { mutationsInProgress.add(mutationKey) }) {
            onError("This expense is already being deleted.")
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ExpenseSyncHelper.deleteExpense(expense.id, expense.instituteId)
                    db.expenseDao().deleteExpense(expense.instituteId, expense.id)
                }
                StaffActivityLogger.logCompletedAction(
                    db, "expense_deleted", "expenses", "Deleted expense ${expense.title} · BDT ${expense.amount.toLong()}"
                )
                onSuccess()
            } catch (e: Exception) {
                onError("Failed to delete: ${e.message}")
            } finally {
                synchronized(mutationsInProgress) { mutationsInProgress.remove(mutationKey) }
            }
        }
    }

    fun addExpense(
        title: String, category: String, amount: Double, expenseDateMs: Long,
        paymentMethod: String?, description: String?,
        expenseId: String = UUID.randomUUID().toString(),
        onSuccess: () -> Unit, onError: (String) -> Unit = {}
    ) {
        val instId = SessionManager.currentInstituteId.value
        if (instId == null) { onError("No institute selected. Please log in again."); return }
        val currentUserId = SessionManager.currentUserId.value
        if (currentUserId == null) { onError("No user session found. Please log in again."); return }
        if (title.isBlank()) { onError("Title is required."); return }
        if (category.isBlank()) { onError("Category is required."); return }
        if (amount <= 0) { onError("Amount must be greater than 0."); return }
        val mutationKey = "create:$expenseId"
        if (!synchronized(mutationsInProgress) { mutationsInProgress.add(mutationKey) }) {
            onError("This expense is already being saved.")
            return
        }

        val expense = ExpenseEntity(
            id = expenseId, instituteId = instId,
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
                StaffActivityLogger.logCompletedAction(
                    db, "expense_created", "expenses", "Recorded expense ${expense.title} · BDT ${amount.toLong()}"
                )
                onSuccess()
            } catch (e: Exception) {
                onError("Failed to save: ${e.message}")
            } finally {
                synchronized(mutationsInProgress) { mutationsInProgress.remove(mutationKey) }
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

