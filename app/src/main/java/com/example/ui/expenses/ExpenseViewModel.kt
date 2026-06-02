package com.example.ui.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.models.ExpenseEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class ExpenseViewModel(private val db: AppDatabase) : ViewModel() {
    private val _expenses = MutableStateFlow<List<ExpenseEntity>>(emptyList())
    val expenses = _expenses.asStateFlow()

    init {
        loadExpenses()
    }

    private fun loadExpenses() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            db.expenseDao().getExpensesByInstitute(instId).collect { list ->
                _expenses.value = list
            }
        }
    }

    fun addExpense(
        title: String,
        category: String,
        amount: Double,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val currentUserId = SessionManager.currentUserId.value ?: return
        if (title.isBlank()) { onError("Expense title is required."); return }
        if (category.isBlank()) { onError("Category is required."); return }
        if (amount <= 0) { onError("Amount must be greater than 0."); return }

        val expense = ExpenseEntity(
            id = UUID.randomUUID().toString(),
            instituteId = instId,
            category = category,
            title = title,
            amount = amount,
            expenseDateMs = System.currentTimeMillis(),
            paymentMethod = null,
            description = null,
            attachmentUri = null,
            createdByUserId = currentUserId,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            archivedAtMs = null
        )
        viewModelScope.launch {
            db.expenseDao().insertExpense(expense)
            onSuccess()
        }
    }
}

class ExpenseViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ExpenseViewModel::class.java)) return ExpenseViewModel(db) as T
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
