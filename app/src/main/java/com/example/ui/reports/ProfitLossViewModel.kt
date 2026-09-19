package com.batchfee.edu.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.OtherIncomeSyncHelper
import com.batchfee.edu.data.models.OtherIncomeEntity
import com.batchfee.edu.data.audit.StaffActivityLogger
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class ProfitLossViewModel(private val db: AppDatabase) : ViewModel() {
    private val _totalIncome = MutableStateFlow(0.0)
    val totalIncome = _totalIncome.asStateFlow()

    private val _totalExpense = MutableStateFlow(0.0)
    val totalExpense = _totalExpense.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()
    private var feeIncome = 0.0
    private var otherIncome = 0.0

    init { loadData() }

    private fun loadData() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
        }
        viewModelScope.launch {
            db.paymentDao().getRecentPayments(instId).collect { payments ->
                feeIncome = payments.filter { it.status == "completed" }.sumOf { it.amount }
                _totalIncome.value = feeIncome + otherIncome
                _isLoading.value = false
            }
        }
        viewModelScope.launch {
            db.otherIncomeDao().getIncomeByInstitute(instId).collect { incomeRows ->
                otherIncome = incomeRows.sumOf { it.amount }
                _totalIncome.value = feeIncome + otherIncome
                _isLoading.value = false
            }
        }
        viewModelScope.launch {
            db.expenseDao().getExpensesByInstitute(instId).collect { expenses ->
                _totalExpense.value = expenses.sumOf { it.amount }
                _isLoading.value = false
            }
        }
    }

    fun addOtherIncome(title: String, category: String, amount: Double, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val instituteId = SessionManager.currentInstituteId.value
            ?: return onError("No institute selected. Please sign in again.")
        val userId = SessionManager.currentUserId.value
            ?: return onError("No user session found. Please sign in again.")
        if (title.isBlank() || amount <= 0.0) return onError("Enter an income title and a valid amount.")
        val now = System.currentTimeMillis()
        val income = OtherIncomeEntity(
            id = UUID.randomUUID().toString(), instituteId = instituteId,
            category = category.ifBlank { "Other" }, title = title.trim(), amount = amount,
            incomeDateMs = now, paymentMethod = null, note = null, createdByUserId = userId,
            createdAtMs = now, updatedAtMs = now
        )
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    OtherIncomeSyncHelper.upsertIncome(income)
                    db.otherIncomeDao().insertIncome(income)
                }
                StaffActivityLogger.logCompletedAction(db, "other_income_created", "finance", "Recorded income ${income.title} · BDT ${amount.toLong()}")
                onSuccess()
            } catch (error: Exception) {
                onError(error.message ?: "Could not save income.")
            }
        }
    }
}

class ProfitLossViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProfitLossViewModel::class.java)) return ProfitLossViewModel(db) as T
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

