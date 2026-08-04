package com.batchfee.edu.ui.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.SalarySyncHelper
import com.batchfee.edu.data.models.SalaryEntity
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

class SalaryViewModel(private val db: AppDatabase) : ViewModel() {
    private val _salaries = MutableStateFlow<List<SalaryEntity>>(emptyList())
    val salaries = _salaries.asStateFlow()

    private val _activeStaff = MutableStateFlow<List<StaffEntity>>(emptyList())
    val activeStaff = _activeStaff.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            db.salaryDao().getSalariesByInstitute(instId).collect { list ->
                _salaries.value = list
            }
        }
        viewModelScope.launch {
            db.staffDao().getActiveStaff(instId).collect { list ->
                _activeStaff.value = list
            }
        }
    }

    fun generateSalary(
        staffId: String,
        salaryMonth: String,
        basicSalary: Double,
        bonusAmount: Double,
        deductionAmount: Double,
        advanceAmount: Double,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val net = basicSalary + bonusAmount - (deductionAmount + advanceAmount)
        if (net < 0) { onError("Net salary cannot be negative."); return }

        viewModelScope.launch {
            val existing = db.salaryDao().getSalariesByInstitute(instId).firstOrNull()?.any {
                it.staffId == staffId && it.salaryMonth == salaryMonth && it.cancelledAtMs == null
            } ?: false
            if (existing) { onError("Salary already exists for ${staffId} in $salaryMonth."); return@launch }

            val entity = SalaryEntity(
                id = UUID.randomUUID().toString(),
                instituteId = instId,
                staffId = staffId,
                salaryMonth = salaryMonth,
                basicSalary = basicSalary,
                bonusAmount = bonusAmount,
                deductionAmount = deductionAmount,
                advanceAmount = advanceAmount,
                netSalary = net,
                paymentMethod = null,
                paymentDateMs = null,
                status = "unpaid",
                salarySlipNumber = "SLP-${UUID.randomUUID().toString().take(8)}",
                note = null,
                createdAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis(),
                cancelledAtMs = null
            )
            SalarySyncHelper.upsertSalary(entity)
            db.salaryDao().insertSalary(entity)
            onSuccess()
        }
    }

    fun markAsPaid(salaryId: String, paymentMethod: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            val salary = db.salaryDao().getSalaryById(salaryId, instId)
            if (salary != null && salary.status == "unpaid") {
                val updated = salary.copy(
                    status = "paid",
                    paymentMethod = paymentMethod,
                    paymentDateMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis()
                )
                SalarySyncHelper.upsertSalary(updated)
                db.salaryDao().updateSalary(updated)
            }
        }
    }
}

class SalaryViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SalaryViewModel::class.java)) return SalaryViewModel(db) as T
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

