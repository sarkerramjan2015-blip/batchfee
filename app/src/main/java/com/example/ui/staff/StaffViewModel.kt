package com.example.ui.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.models.StaffEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class StaffViewModel(private val db: AppDatabase) : ViewModel() {
    private val _staffList = MutableStateFlow<List<StaffEntity>>(emptyList())
    val staffList = _staffList.asStateFlow()

    private val _selectedStaff = MutableStateFlow<StaffEntity?>(null)
    val selectedStaff = _selectedStaff.asStateFlow()

    // Search query state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadStaff()
    }

    private fun loadStaff() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            db.staffDao().getStaffByInstitute(instId).collect { list ->
                _staffList.value = list
                _isLoading.value = false
            }
        }
    }

    fun loadStaffById(staffId: String) {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            db.staffDao().getStaffById(staffId, instId).collect { staff ->
                _selectedStaff.value = staff
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            if (query.isBlank()) {
                db.staffDao().getStaffByInstitute(instId).collect { _staffList.value = it }
            } else {
                db.staffDao().searchStaff(instId, query).collect { _staffList.value = it }
            }
        }
    }

    fun addStaff(
        fullName: String,
        roleTitle: String,
        phone: String,
        monthlySalary: Double,
        permissions: String? = null, // comma-separated permission flags
        assignedBatchIds: String? = null,
        onSuccess: () -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        if (fullName.isBlank() || roleTitle.isBlank() || monthlySalary < 0) return

        val staffCode = "STF-${UUID.randomUUID().toString().take(8)}"
        val staff = StaffEntity(
            id = UUID.randomUUID().toString(),
            instituteId = instId,
            staffCode = staffCode,
            fullName = fullName,
            photoUri = null,
            roleTitle = roleTitle,
            phone = phone,
            email = null,
            address = null,
            joiningDateMs = System.currentTimeMillis(),
            monthlySalary = monthlySalary,
            assignedBatchIds = assignedBatchIds,
            status = "active",
            notes = null,
            permissions = permissions,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            archivedAtMs = null
        )
        viewModelScope.launch {
            db.staffDao().insertStaff(staff)
            onSuccess()
        }
    }

    // Update existing staff — admin only
    fun updateStaff(
        staffId: String,
        fullName: String,
        roleTitle: String,
        phone: String,
        monthlySalary: Double,
        permissions: String?,
        status: String? = null,
        onSuccess: () -> Unit
    ) {
        if (!SessionManager.isAdmin()) return
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            val existing = db.staffDao().getStaffByIdOnce(staffId, instId) ?: return@launch
            val updated = existing.copy(
                fullName = fullName,
                roleTitle = roleTitle,
                phone = phone,
                monthlySalary = monthlySalary,
                permissions = permissions,
                status = status ?: existing.status,
                updatedAtMs = System.currentTimeMillis()
            )
            db.staffDao().updateStaff(updated)
            onSuccess()
        }
    }

    // Archive (soft-delete) staff — admin only
    fun archiveStaff(staffId: String, onSuccess: () -> Unit) {
        if (!SessionManager.isAdmin()) return
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            db.staffDao().archiveStaff(instId, staffId, System.currentTimeMillis())
            onSuccess()
        }
    }
}

class StaffViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StaffViewModel::class.java)) {
            return StaffViewModel(db) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
