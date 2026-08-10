package com.batchfee.edu.ui.students

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.InstituteRefreshScope
import com.batchfee.edu.data.firestore.StudentSyncHelper
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.data.firestore.InstituteSyncHelper
import com.batchfee.edu.data.firestore.BatchStudentSyncHelper
import com.batchfee.edu.data.models.BatchStudentEntity
import com.batchfee.edu.data.repository.StudentAccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class StudentViewModel(private val db: AppDatabase) : ViewModel() {
    private val studentAccountRepository = StudentAccountRepository()
    private val _studentList = MutableStateFlow<List<StudentEntity>>(emptyList())
    val studentList = _studentList.asStateFlow()

    private val _batchList = MutableStateFlow<List<BatchEntity>>(emptyList())
    val batchList = _batchList.asStateFlow()

    init {
        loadStudents()
        loadBatches()
    }

    private fun loadStudents() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            // Render cached Room data immediately — do not wait on any sync.
            db.studentDao().getStudentsByInstitute(instId).collect {
                _studentList.value = it
            }
        }
        // Narrow background refresh: only the STUDENTS scope, deduped by the existing infrastructure.
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            InstituteCacheRefreshManager.refreshScopeIfStale(db, instId, InstituteRefreshScope.STUDENTS)
        }
    }

    private fun loadBatches() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            db.batchDao().getBatchesByInstitute(instId).collect {
                _batchList.value = it
            }
        }
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            InstituteCacheRefreshManager.refreshScopeIfStale(db, instId, InstituteRefreshScope.BATCHES)
        }
    }

    fun generateStudentCode(): String {
        val digits = UUID.randomUUID().toString().filter(Char::isDigit) + System.currentTimeMillis().toString()
        return digits.take(8).padEnd(8, '0')
    }

    fun addStudent(
        id: String,
        studentCode: String,
        fullName: String,
        phone: String,
        guardianName: String?,
        motherName: String?,
        whatsappNumber: String?,
        gender: String?,
        dateOfBirthMs: Long?,
        schoolName: String?,
        className: String?,
        address: String?,
        admissionDateMs: Long,
        photoUri: String?,
        isAppAccessEnabled: Boolean = false,
        appAccessPassword: String? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (fullName.isBlank()) { onError("Student name is required."); return }
        if (phone.isBlank()) { onError("Phone number is required."); return }
        val instId = SessionManager.currentInstituteId.value ?: return
        val combinedNotes = buildString {
            if (!whatsappNumber.isNullOrBlank()) {
                appendLine("WhatsApp: $whatsappNumber")
            }
        }.trimEnd().takeIf { it.isNotEmpty() }

        if (isAppAccessEnabled && appAccessPassword.isNullOrEmpty()) {
            onError("A password is required when enabling student app access.")
            return
        }
        if (appAccessPassword != null && appAccessPassword.length !in 6..128) {
            onError("Student app password must contain 6 to 128 characters.")
            return
        }
        val student = StudentEntity(
            id = id,
            instituteId = instId,
            studentCode = studentCode,
            fullName = fullName,
            photoUri = photoUri,
            gender = gender,
            dateOfBirthMs = dateOfBirthMs,
            phone = phone,
            email = null,
            address = address,
            schoolName = schoolName,
            className = className,
            guardianName = guardianName,
            guardianPhone = null,
            guardianEmail = null,
            emergencyContact = motherName,
            bloodGroup = null,
            admissionDateMs = admissionDateMs,
            status = "active",
            notes = combinedNotes,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            archivedAtMs = null,
            // The backend is the only writer of cloud authentication state.
            isAppAccessEnabled = false
        )
        viewModelScope.launch {
            try {
                db.studentDao().insertStudent(student)
                val savedStudent = if (isAppAccessEnabled) {
                    StudentSyncHelper.upsertStudentOrThrow(student)
                    studentAccountRepository.provision(instId, student.id, appAccessPassword!!)
                    student.copy(isAppAccessEnabled = true)
                } else {
                    launch { StudentSyncHelper.upsertStudent(student) }
                    student
                }
                if (savedStudent !== student) db.studentDao().updateStudent(savedStudent)
                onSuccess()
                launch {
                    try {
                        val count = withContext(Dispatchers.IO) {
                            db.studentDao().getStudentsByInstituteOnce(instId).size
                        }
                        InstituteSyncHelper.updateStudentCount(instId, count)
                    } catch (_: Exception) { }
                }
            } catch (error: Exception) {
                onError(accountErrorMessage(error, "Student account could not be created."))
            }
        }
    }

    suspend fun loadStudent(studentId: String): StudentEntity? {
        val instId = SessionManager.currentInstituteId.value ?: return null
        InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
        return db.studentDao().getStudentById(studentId, instId).firstOrNull()
    }

    /**
     * Sets a student app password through the existing trusted account service.
     * The password is intentionally only an in-memory call argument; it is never
     * written to Room or any client-side profile field.
     */
    fun setStudentAppPassword(
        studentId: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (password.length !in 6..128) {
            onError("Student app password must contain 6 to 128 characters.")
            return
        }
        val instId = SessionManager.currentInstituteId.value
        if (instId == null) {
            onError("No active institute session.")
            return
        }

        viewModelScope.launch {
            try {
                val existing = db.studentDao().getStudentById(studentId, instId).firstOrNull()
                    ?: run {
                        onError("Student profile was not found.")
                        return@launch
                    }
                if (existing.status != "active" || existing.archivedAtMs != null) {
                    onError("Only active students can use app access.")
                    return@launch
                }

                // Ensure the trusted backend has the current student record before
                // it creates or resets the managed account identity.
                StudentSyncHelper.upsertStudentOrThrow(existing)
                studentAccountRepository.provision(instId, studentId, password)
                db.studentDao().updateStudent(
                    existing.copy(
                        isAppAccessEnabled = true,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
                onSuccess()
            } catch (error: Exception) {
                onError(accountErrorMessage(error, "Student password could not be set."))
            }
        }
    }

    fun updateStudent(
        id: String,
        studentCode: String,
        fullName: String,
        phone: String,
        guardianName: String?,
        motherName: String?,
        whatsappNumber: String?,
        gender: String?,
        dateOfBirthMs: Long?,
        schoolName: String?,
        className: String?,
        address: String?,
        admissionDateMs: Long,
        photoUri: String?,
        isAppAccessEnabled: Boolean = false,
        appAccessPassword: String? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            val existing = db.studentDao().getStudentById(id, instId).firstOrNull() ?: run {
                onError("Student profile was not found.")
                return@launch
            }
            if (isAppAccessEnabled && !existing.isAppAccessEnabled && appAccessPassword.isNullOrEmpty()) {
                onError("A password is required when enabling student app access.")
                return@launch
            }
            if (appAccessPassword != null && appAccessPassword.length !in 6..128) {
                onError("Student app password must contain 6 to 128 characters.")
                return@launch
            }
            if (isAppAccessEnabled && existing.isAppAccessEnabled && appAccessPassword.isNullOrEmpty()) {
                val securelyLinked = try {
                    studentAccountRepository.isSecurelyLinked(instId, id)
                } catch (error: Exception) {
                    onError(accountErrorMessage(error, "Could not verify the existing student account."))
                    return@launch
                }
                if (!securelyLinked) {
                    onError("This legacy student account needs a one-time password reset before app access can continue.")
                    return@launch
                }
            }
            if (isAppAccessEnabled && existing.isAppAccessEnabled &&
                existing.studentCode != studentCode && appAccessPassword.isNullOrEmpty()) {
                onError("Re-enter the app password after changing the student ID.")
                return@launch
            }
            val combinedNotes = buildString {
                if (!whatsappNumber.isNullOrBlank()) {
                    appendLine("WhatsApp: $whatsappNumber")
                }
            }.trimEnd().takeIf { it.isNotEmpty() }
            val updated = existing.copy(
                studentCode = studentCode,
                fullName = fullName,
                phone = phone,
                guardianName = guardianName,
                emergencyContact = motherName,
                gender = gender,
                dateOfBirthMs = dateOfBirthMs,
                schoolName = schoolName,
                className = className,
                address = address,
                admissionDateMs = admissionDateMs,
                notes = combinedNotes,
                photoUri = photoUri,
                updatedAtMs = System.currentTimeMillis(),
                isAppAccessEnabled = existing.isAppAccessEnabled
            )
            try {
                val requiresAccountChange = appAccessPassword != null ||
                    isAppAccessEnabled != existing.isAppAccessEnabled
                val finalStudent = if (requiresAccountChange) {
                    StudentSyncHelper.upsertStudentOrThrow(updated)
                    if (isAppAccessEnabled) {
                        studentAccountRepository.provision(instId, id, appAccessPassword!!)
                    } else {
                        studentAccountRepository.disable(instId, id)
                    }
                    updated.copy(isAppAccessEnabled = isAppAccessEnabled)
                } else {
                    launch { StudentSyncHelper.upsertStudent(updated) }
                    updated
                }
                db.studentDao().updateStudent(finalStudent)
                onSuccess()
            } catch (error: Exception) {
                onError(accountErrorMessage(error, "Student account could not be updated."))
            }
        }
    }

    fun shiftStudentBatch(
        studentId: String,
        oldBatchId: String,
        newBatchId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val instId = SessionManager.currentInstituteId.value
        if (instId == null) {
            onError("No active institute session.")
            return
        }
        if (oldBatchId == newBatchId) {
            onError("Source and target batch must be different.")
            return
        }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()

                // Step 1: Remove from old batch (local)
                withContext(Dispatchers.IO) {
                    db.batchStudentDao().removeStudentFromBatch(oldBatchId, studentId, instId, now)
                }

                // Step 2: Enroll in new batch (local)
                val enrollment = BatchStudentEntity(
                    id = UUID.randomUUID().toString(),
                    instituteId = instId,
                    batchId = newBatchId,
                    studentId = studentId,
                    joinedAtMs = now,
                    status = "active",
                    leftAtMs = null
                )
                withContext(Dispatchers.IO) {
                    db.batchStudentDao().enrollStudent(enrollment)
                }

                onSuccess()

                // Historical fee ledger entries retain the batch under which they
                // were posted. Only the enrollment relationship is moved.
                launch {
                    try {
                        BatchStudentSyncHelper.markRemoved(instId, oldBatchId, studentId, now)
                        BatchStudentSyncHelper.upsertEnrollment(enrollment)
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to shift student batch.")
            }
        }
    }

    private fun accountErrorMessage(error: Exception, fallback: String): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("PERMISSION_DENIED", ignoreCase = true) ->
                "You do not have permission to manage student app access."
            message.contains("ALREADY_EXISTS", ignoreCase = true) ->
                "This institute code and student ID are already linked to another account."
            message.contains("UNAVAILABLE", ignoreCase = true) ||
                message.contains("DEADLINE_EXCEEDED", ignoreCase = true) ->
                "Account service is temporarily unavailable. Check your connection and try again."
            else -> fallback
        }
    }
}

class StudentViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StudentViewModel::class.java)) return StudentViewModel(db) as T
        throw IllegalArgumentException()
    }
}

