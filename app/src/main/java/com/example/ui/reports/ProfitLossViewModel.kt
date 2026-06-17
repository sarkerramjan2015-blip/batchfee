package com.example.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.firestore.InstituteCacheRefreshManager
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfitLossViewModel(private val db: AppDatabase) : ViewModel() {
    private val _totalIncome = MutableStateFlow(0.0)
    val totalIncome = _totalIncome.asStateFlow()

    private val _totalExpense = MutableStateFlow(0.0)
    val totalExpense = _totalExpense.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
        }
        
        viewModelScope.launch {
            db.paymentDao().getRecentPayments(instId).collect { payments ->
                val income = payments.filter { it.status == "completed" }.sumOf { it.amount }
                _totalIncome.value = income
            }
        }
        
        viewModelScope.launch {
            db.expenseDao().getExpensesByInstitute(instId).collect { expenses ->
                val exp = expenses.sumOf { it.amount }
                _totalExpense.value = exp
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
