package com.example.ui.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.models.BatchEntity
import com.example.data.models.StaffEntity
import com.example.data.models.UserEntity
import com.example.domain.PasswordHasher
import com.example.domain.SessionManager
import com.example.domain.StaffPermissions
import com.example.data.firestore.InstituteSyncHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class StaffViewModel(private val db: AppDatabase) : ViewModel() {
    private val _staffList = MutableStateFlow<List<StaffEntity>>(emptyList())
    val staffList = _staffList.asStateFlow()

    private val _selectedStaff = MutableStateFlow<StaffEntity?>(null)
    val selectedStaff = _selectedStaff.asStateFlow()

    private val _batches = MutableStateFlow<List<BatchEntity>>(emptyList())
    val batches = _batches.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadStaff()
        loadBatches()
    }

    private fun loadStaff() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            _isLoading.value = true
            db.staffDao().getStaffByInstitute(instId).collect { list ->
                _staffList.value = list
                _isLoading.value = false
            }
        }
    }

    private fun loadBatches() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            db.batchDao().getBatchesByInstitute(instId).collect { list ->
                _batches.value = list
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
            delay(250)
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
        staffCode: String,
        roleTitle: String,
        phone: String,
        email: String?,
        monthlySalary: Double,
        permissions: Set<String>,
        assignedBatchIds: Set<String>,
        password: String,
        status: String = "active",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!SessionManager.isAdmin()) {
            onError("Only admins can create staff accounts.")
            return
        }

        val instId = SessionManager.currentInstituteId.value ?: run {
            onError("Institute session was not found.")
            return
        }
        val loginId = staffCode.trim().uppercase()
        val name = fullName.trim()
        val role = roleTitle.trim()
        val cleanPassword = password.trim()

        when {
            name.isBlank() -> onError("Staff name is required.")
            loginId.isBlank() -> onError("Staff login ID is required.")
            role.isBlank() -> onError("Role title is required.")
            monthlySalary < 0 -> onError("Monthly salary cannot be negative.")
            cleanPassword.length < 4 -> onError("Password must be at least 4 characters.")
            else -> viewModelScope.launch {
                val existingLogin = db.userDao().getUserByEmail(loginId)
                if (existingLogin != null) {
                    onError("This staff login ID is already used.")
                    return@launch
                }

                val staffId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val permissionCsv = StaffPermissions.toCsv(permissions)
                val batchCsv = assignedBatchIds.sorted().joinToString(",").takeIf { it.isNotBlank() }
                val staff = StaffEntity(
                    id = staffId,
                    instituteId = instId,
                    staffCode = loginId,
                    fullName = name,
                    photoUri = null,
                    roleTitle = role,
                    phone = phone.trim().takeIf { it.isNotBlank() },
                    email = email?.trim()?.takeIf { it.isNotBlank() },
                    address = null,
                    joiningDateMs = now,
                    monthlySalary = monthlySalary,
                    assignedBatchIds = batchCsv,
                    status = status,
                    notes = null,
                    permissions = permissionCsv,
                    createdAtMs = now,
                    updatedAtMs = now,
                    archivedAtMs = null
                )
                db.staffDao().insertStaff(staff)
                db.userDao().insertUser(
                    UserEntity(
                        id = staffId,
                        instituteId = instId,
                        name = name,
                        email = loginId,
                        passwordHash = PasswordHasher.hash(cleanPassword),
                        role = "Staff",
                        createdAtMs = now
                    )
                )
                try {
                    val count = withContext(Dispatchers.IO) {
                        db.staffDao().getStaffByInstituteAsList(instId).size
                    }
                    InstituteSyncHelper.updateStaffCount(instId, count)
                } catch (_: Exception) { }
                onSuccess()
            }
        }
    }

    fun updateStaff(
        staffId: String,
        fullName: String,
        staffCode: String,
        roleTitle: String,
        phone: String,
        email: String?,
        monthlySalary: Double,
        permissions: Set<String>,
        assignedBatchIds: Set<String>,
        status: String,
        password: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!SessionManager.isAdmin()) {
            onError("Only admins can update staff accounts.")
            return
        }

        val loginId = staffCode.trim().uppercase()
        val name = fullName.trim()
        val role = roleTitle.trim()
        val cleanPassword = password?.trim().orEmpty()

        when {
            name.isBlank() -> onError("Staff name is required.")
            loginId.isBlank() -> onError("Staff login ID is required.")
            role.isBlank() -> onError("Role title is required.")
            monthlySalary < 0 -> onError("Monthly salary cannot be negative.")
            cleanPassword.isNotBlank() && cleanPassword.length < 4 -> onError("Password must be at least 4 characters.")
            else -> viewModelScope.launch {
                val instId = SessionManager.currentInstituteId.value ?: run {
                    onError("Institute session was not found.")
                    return@launch
                }
                val existing = db.staffDao().getStaffByIdOnce(staffId, instId) ?: run {
                    onError("Staff profile was not found.")
                    return@launch
                }
                val existingLogin = db.userDao().getUserByEmail(loginId)
                if (existingLogin != null && existingLogin.id != staffId) {
                    onError("This staff login ID is already used.")
                    return@launch
                }

                val permissionCsv = StaffPermissions.toCsv(permissions)
                val batchCsv = assignedBatchIds.sorted().joinToString(",").takeIf { it.isNotBlank() }
                val updated = existing.copy(
                    staffCode = loginId,
                    fullName = name,
                    roleTitle = role,
                    phone = phone.trim().takeIf { it.isNotBlank() },
                    email = email?.trim()?.takeIf { it.isNotBlank() },
                    monthlySalary = monthlySalary,
                    assignedBatchIds = batchCsv,
                    permissions = permissionCsv,
                    status = status,
                    updatedAtMs = System.currentTimeMillis()
                )
                db.staffDao().updateStaff(updated)

                val currentUser = db.userDao().getUserById(staffId)
                if (currentUser == null) {
                    if (cleanPassword.isBlank()) {
                        onError("Set a password to activate this staff login.")
                        return@launch
                    }
                    db.userDao().insertUser(
                        UserEntity(
                            id = staffId,
                            instituteId = instId,
                            name = name,
                            email = loginId,
                            passwordHash = PasswordHasher.hash(cleanPassword),
                            role = "Staff",
                            createdAtMs = System.currentTimeMillis()
                        )
                    )
                } else {
                    db.userDao().updateUser(
                        currentUser.copy(
                            instituteId = instId,
                            name = name,
                            email = loginId,
                            passwordHash = if (cleanPassword.isBlank()) currentUser.passwordHash else PasswordHasher.hash(cleanPassword),
                            role = "Staff"
                        )
                    )
                }

                if (SessionManager.currentUserId.value == staffId) {
                    SessionManager.updateStaffPermissions(permissionCsv)
                }
                onSuccess()
            }
        }
    }

    fun archiveStaff(staffId: String, onSuccess: () -> Unit) {
        if (!SessionManager.isAdmin()) return
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            db.staffDao().archiveStaff(instId, staffId, System.currentTimeMillis())
            try {
                val count = withContext(Dispatchers.IO) {
                    db.staffDao().getStaffByInstituteAsList(instId).size
                }
                InstituteSyncHelper.updateStaffCount(instId, count)
            } catch (_: Exception) { }
            onSuccess()
        }
    }
}

class StaffViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StaffViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StaffViewModel(db) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
