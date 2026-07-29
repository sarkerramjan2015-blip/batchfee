package com.batchfee.edu.ui.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.AuditLogSyncHelper
import com.batchfee.edu.data.models.AuditLogEntity
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.data.models.UserEntity
import com.batchfee.edu.domain.PasswordHasher
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.StaffPermissions
import com.batchfee.edu.data.firebase.FirebaseAuthApi
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.InstituteSyncHelper
import com.batchfee.edu.data.firestore.StaffSyncHelper
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

    private val _archivedStaffList = MutableStateFlow<List<StaffEntity>>(emptyList())
    val archivedStaffList = _archivedStaffList.asStateFlow()

    private val _selectedStaff = MutableStateFlow<StaffEntity?>(null)
    val selectedStaff = _selectedStaff.asStateFlow()

    private val _batches = MutableStateFlow<List<BatchEntity>>(emptyList())
    val batches = _batches.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _activityLogs = MutableStateFlow<List<AuditLogEntity>>(emptyList())
    val activityLogs = _activityLogs.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadStaff()
        loadArchivedStaff()
        loadBatches()
    }

    private fun logActivity(userId: String, action: String, module: String, description: String) {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            val log = AuditLogEntity(
                id = UUID.randomUUID().toString(),
                instituteId = instId,
                userId = userId,
                action = action,
                module = module,
                description = description,
                oldValue = null,
                newValue = null,
                createdAtMs = System.currentTimeMillis()
            )
            db.auditLogDao().insertAuditLog(log)
            launch { AuditLogSyncHelper.upsertAuditLog(log) }
        }
    }

    fun loadActivityLogs(staffId: String) {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            db.auditLogDao().getAuditLogsByUser(instId, staffId).collect { logs ->
                _activityLogs.value = logs
            }
        }
    }

    private fun loadStaff() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            _isLoading.value = true
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
            db.staffDao().getStaffByInstitute(instId).collect { list ->
                _staffList.value = list
                _isLoading.value = false
            }
        }
    }

    private fun loadArchivedStaff() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            db.staffDao().getArchivedStaffByInstitute(instId).collect { list ->
                _archivedStaffList.value = list
            }
        }
    }

    private fun loadBatches() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
            db.batchDao().getBatchesByInstitute(instId).collect { list ->
                _batches.value = list
            }
        }
    }

    fun loadStaffById(staffId: String) {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
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
        onSuccess: (staffId: String, loginId: String, staffPassword: String, staffEmail: String) -> Unit,
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
        val staffEmail = email?.trim()?.takeIf { it.isNotBlank() }
        val cleanPassword = password.trim()

        when {
            name.isBlank() -> onError("Staff name is required.")
            loginId.isBlank() -> onError("Staff login ID is required.")
            staffEmail == null -> onError("Email is required.")
            role.isBlank() -> onError("Role title is required.")
            monthlySalary < 0 -> onError("Monthly salary cannot be negative.")
            cleanPassword.length < 4 -> onError("Password must be at least 4 characters.")
            else -> viewModelScope.launch {
                val existingLogin = db.userDao().getUserByEmail(staffEmail)
                if (existingLogin != null) {
                    onError("A staff account with this email already exists.")
                    return@launch
                }

                // Create Firebase Auth account via REST API (does NOT sign anyone out)
                val firebaseUid: String
                try {
                    firebaseUid = FirebaseAuthApi.createUser(staffEmail, cleanPassword)
                } catch (e: FirebaseAuthApi.SignUpException) {
                    onError(e.firebaseMessage)
                    return@launch
                } catch (e: Exception) {
                    onError("Failed to create staff account. Check connection and try again.")
                    return@launch
                }

                val now = System.currentTimeMillis()
                val permissionCsv = StaffPermissions.toCsv(permissions)
                val batchCsv = assignedBatchIds.sorted().joinToString(",").takeIf { it.isNotBlank() }
                val staff = StaffEntity(
                    id = firebaseUid,
                    instituteId = instId,
                    staffCode = loginId,
                    fullName = name,
                    photoUri = null,
                    roleTitle = role,
                    phone = phone.trim().takeIf { it.isNotBlank() },
                    email = staffEmail,
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
                        id = firebaseUid,
                        instituteId = instId,
                        name = name,
                        email = staffEmail,
                        passwordHash = PasswordHasher.hash(cleanPassword),
                        role = "Staff",
                        createdAtMs = now
                    )
                )
                // Sync to Firestore (non-blocking — best-effort)
                launch { StaffSyncHelper.createStaff(staff) }
                launch {
                    try {
                        val count = withContext(Dispatchers.IO) {
                            db.staffDao().getStaffByInstituteAsList(instId).size
                        }
                        InstituteSyncHelper.updateStaffCount(instId, count)
                    } catch (_: Exception) { }
                }
                onSuccess(firebaseUid, loginId, cleanPassword, staffEmail)
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
        val staffEmail = email?.trim()?.takeIf { it.isNotBlank() }
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
                if (staffEmail != null && staffEmail != existing.email) {
                    val existingLogin = db.userDao().getUserByEmail(staffEmail)
                    if (existingLogin != null && existingLogin.id != staffId) {
                        onError("A staff account with this email already exists.")
                        return@launch
                    }
                }

                val permissionCsv = StaffPermissions.toCsv(permissions)
                val batchCsv = assignedBatchIds.sorted().joinToString(",").takeIf { it.isNotBlank() }
                val updated = existing.copy(
                    staffCode = loginId,
                    fullName = name,
                    roleTitle = role,
                    phone = phone.trim().takeIf { it.isNotBlank() },
                    email = staffEmail,
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
                            email = staffEmail ?: loginId,
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
                            email = staffEmail ?: currentUser.email,
                            passwordHash = if (cleanPassword.isBlank()) currentUser.passwordHash else PasswordHasher.hash(cleanPassword),
                            role = "Staff"
                        )
                    )
                }

                if (SessionManager.currentUserId.value == staffId) {
                    SessionManager.updateStaffPermissions(permissionCsv)
                }
                // Sync to Firestore (non-blocking)
                launch { StaffSyncHelper.updateStaff(updated) }
                onSuccess()
            }
        }
    }

    fun archiveStaff(staffId: String, onSuccess: () -> Unit) {
        if (!SessionManager.isAdmin()) return
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            val archivedAt = System.currentTimeMillis()
            db.staffDao().archiveStaff(instId, staffId, archivedAt)
            launch { StaffSyncHelper.archiveStaff(instId, staffId) }
            onSuccess()
        }
    }

    fun restoreStaff(staffId: String, onSuccess: () -> Unit) {
        if (!SessionManager.isAdmin()) return
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            db.staffDao().restoreStaff(instId, staffId)
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

