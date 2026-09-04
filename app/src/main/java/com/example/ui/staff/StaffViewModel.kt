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
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.InstituteSyncHelper
import com.batchfee.edu.data.repository.EntitledCreationRepository
import com.batchfee.edu.data.repository.SafeDeletionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class StaffViewModel(private val db: AppDatabase) : ViewModel() {
    private val entitledCreationRepository = EntitledCreationRepository()
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
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
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
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            db.batchDao().getBatchesByInstitute(instId).collect { list ->
                _batches.value = list
            }
        }
    }

    fun loadStaffById(staffId: String) {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
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
        photoUri: String?,
        roleTitle: String,
        phone: String,
        email: String?,
        monthlySalary: Double,
        staffCategory: String,
        salaryType: String,
        perClassRate: Double,
        perHourRate: Double,
        subjects: String?,
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
        val normalizedCategory = staffCategory.normalizedStaffCategory()
        val normalizedSalaryType = salaryType.normalizedSalaryType()
        val cleanSubjects = subjects?.normalizeSubjects()

        when {
            name.isBlank() -> onError("Staff name is required.")
            loginId.isBlank() -> onError("Staff login ID is required.")
            staffEmail == null -> onError("Email is required.")
            role.isBlank() -> onError("Role title is required.")
            monthlySalary < 0 -> onError("Monthly salary cannot be negative.")
            normalizedSalaryType == "per_class" && perClassRate <= 0 -> onError("Enter a valid per-class rate.")
            normalizedSalaryType == "per_hour" && perHourRate <= 0 -> onError("Enter a valid per-hour rate.")
            normalizedCategory == "teacher" && cleanSubjects == null -> onError("Add at least one subject for this teacher.")
            cleanPassword.length < 6 -> onError("Password must be at least 6 characters.")
            else -> viewModelScope.launch {
                val existingLogin = db.userDao().getUserByEmail(staffEmail)
                if (existingLogin != null) {
                    onError("A staff account with this email already exists.")
                    return@launch
                }

                val now = System.currentTimeMillis()
                val permissionCsv = StaffPermissions.toCsv(permissions)
                val batchCsv = assignedBatchIds.sorted().joinToString(",").takeIf { it.isNotBlank() }
                val pendingStaff = StaffEntity(
                    id = "pending",
                    instituteId = instId,
                    staffCode = loginId,
                    fullName = name,
                    photoUri = photoUri,
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
                    archivedAtMs = null,
                    staffCategory = normalizedCategory,
                    salaryType = normalizedSalaryType,
                    perClassRate = perClassRate.coerceAtLeast(0.0),
                    perHourRate = perHourRate.coerceAtLeast(0.0),
                    subjects = cleanSubjects
                )
                val firebaseUid: String
                try {
                    firebaseUid = entitledCreationRepository.provisionStaff(pendingStaff, cleanPassword)
                } catch (error: Exception) {
                    onError("Staff account could not be created: ${error.localizedMessage ?: "subscription or connection problem"}")
                    return@launch
                }
                val staff = pendingStaff.copy(id = firebaseUid)
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
                onSuccess(firebaseUid, loginId, cleanPassword, staffEmail)
            }
        }
    }

    fun updateStaff(
        staffId: String,
        fullName: String,
        staffCode: String,
        photoUri: String?,
        roleTitle: String,
        phone: String,
        email: String?,
        monthlySalary: Double,
        staffCategory: String,
        salaryType: String,
        perClassRate: Double,
        perHourRate: Double,
        subjects: String?,
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
        val normalizedCategory = staffCategory.normalizedStaffCategory()
        val normalizedSalaryType = salaryType.normalizedSalaryType()
        val cleanSubjects = subjects?.normalizeSubjects()

        when {
            name.isBlank() -> onError("Staff name is required.")
            loginId.isBlank() -> onError("Staff login ID is required.")
            role.isBlank() -> onError("Role title is required.")
            monthlySalary < 0 -> onError("Monthly salary cannot be negative.")
            normalizedSalaryType == "per_class" && perClassRate <= 0 -> onError("Enter a valid per-class rate.")
            normalizedSalaryType == "per_hour" && perHourRate <= 0 -> onError("Enter a valid per-hour rate.")
            normalizedCategory == "teacher" && cleanSubjects == null -> onError("Add at least one subject for this teacher.")
            cleanPassword.isNotBlank() && cleanPassword.length < 6 -> onError("Password must be at least 6 characters.")
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
                    photoUri = photoUri,
                    roleTitle = role,
                    phone = phone.trim().takeIf { it.isNotBlank() },
                    email = staffEmail,
                    monthlySalary = monthlySalary,
                    assignedBatchIds = batchCsv,
                    permissions = permissionCsv,
                    status = status,
                    staffCategory = normalizedCategory,
                    salaryType = normalizedSalaryType,
                    perClassRate = perClassRate.coerceAtLeast(0.0),
                    perHourRate = perHourRate.coerceAtLeast(0.0),
                    subjects = cleanSubjects,
                    updatedAtMs = System.currentTimeMillis()
                )
                try {
                    entitledCreationRepository.updateStaff(
                        updated,
                        cleanPassword.takeIf { it.isNotBlank() }
                    )
                    db.staffDao().updateStaff(updated)
                } catch (error: Exception) {
                    onError(error.localizedMessage ?: "Could not update the staff account.")
                    return@launch
                }

                // Keep the local offline login cache aligned with the trusted update.
                val currentUser = db.userDao().getUserById(staffId)
                if (currentUser == null) {
                    if (staffEmail == null) {
                        onError("Email is required to create staff login.")
                        return@launch
                    }
                    db.userDao().insertUser(
                        UserEntity(
                            id = staffId,
                            instituteId = instId,
                            name = name,
                            email = staffEmail,
                            passwordHash = cleanPassword.takeIf { it.isNotBlank() }
                                ?.let(PasswordHasher::hash)
                                .orEmpty(),
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
                            passwordHash = cleanPassword.takeIf { it.isNotBlank() }
                                ?.let(PasswordHasher::hash)
                                ?: currentUser.passwordHash,
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

    /** Updates only the photo after a new staff account has received its Firebase UID. */
    fun updateStaffPhoto(
        staffId: String,
        photoUri: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!SessionManager.isAdmin()) {
            onError("Only admins can update staff photos.")
            return
        }
        viewModelScope.launch {
            val instituteId = SessionManager.currentInstituteId.value ?: run {
                onError("Institute session was not found.")
                return@launch
            }
            val existing = db.staffDao().getStaffByIdOnce(staffId, instituteId) ?: run {
                onError("Staff profile was not found after account creation.")
                return@launch
            }
            val updated = existing.copy(photoUri = photoUri, updatedAtMs = System.currentTimeMillis())
            try {
                entitledCreationRepository.updateStaff(updated)
                db.staffDao().updateStaff(updated)
                onSuccess()
            } catch (error: Exception) {
                onError(error.message ?: "Could not save staff photo.")
            }
        }
    }

    fun archiveStaff(staffId: String, onSuccess: () -> Unit) {
        if (!SessionManager.isAdmin()) return
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            val staff = db.staffDao().getStaffByIdOnce(staffId, instId) ?: return@launch
            try {
                SafeDeletionRepository(db).archiveStaff(staff, "Staff archived from staff management")
                onSuccess()
            } catch (_: Exception) { }
        }
    }

    fun restoreStaff(staffId: String, onSuccess: () -> Unit) {
        if (!SessionManager.isAdmin()) return
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            try {
                SafeDeletionRepository(db).restoreStaff(instId, staffId, "Staff restored from staff management")
                onSuccess()
            } catch (_: Exception) { }
        }
    }
}

private fun String.normalizedStaffCategory(): String =
    if (trim().equals("teacher", ignoreCase = true)) "teacher" else "administration"

private fun String.normalizedSalaryType(): String = when (trim().lowercase()) {
    "per_class" -> "per_class"
    "per_hour" -> "per_hour"
    else -> "monthly"
}

private fun String.normalizeSubjects(): String? =
    split(",")
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase() }
        .joinToString(", ")
        .takeIf { it.isNotBlank() }

class StaffViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StaffViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StaffViewModel(db) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

