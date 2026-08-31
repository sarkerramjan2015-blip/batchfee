package com.batchfee.edu.ui.students

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.audit.StaffActivityLogger
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.InstituteRefreshScope
import com.batchfee.edu.data.firestore.StudentSyncHelper
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.MonthlyDueCalculator
import com.batchfee.edu.domain.StudentIdGenerator
import com.batchfee.edu.data.firestore.InstituteSyncHelper
import com.batchfee.edu.data.firestore.BatchStudentSyncHelper
import com.batchfee.edu.data.models.BatchStudentEntity
import com.batchfee.edu.data.repository.StudentAccountRepository
import com.batchfee.edu.data.repository.EntitledCreationRepository
import com.batchfee.edu.data.repository.FeeCollectionRepository
import com.google.firebase.functions.FirebaseFunctionsException
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
    private val entitledCreationRepository = EntitledCreationRepository()
    private val feeCollectionRepository = FeeCollectionRepository(db)
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
        return StudentIdGenerator.generate()
    }

    fun addStudent(
        id: String,
        studentCode: String,
        studentCodeIsAuto: Boolean,
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
        onSuccess: (String) -> Unit,
        onPartialSuccess: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (fullName.isBlank()) { onError("Student name is required."); return }
        if (phone.isBlank()) { onError("Phone number is required."); return }
        val normalizedStudentCode = StudentIdGenerator.normalize(studentCode)
        if (!StudentIdGenerator.isValid(normalizedStudentCode)) {
            onError("Student ID must contain 3 to 20 letters, numbers or hyphens.")
            return
        }
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
            studentCode = normalizedStudentCode,
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
                val savedCloudStudent = createStudentWithUniqueCode(student, studentCodeIsAuto)
                db.studentDao().insertStudent(savedCloudStudent)
                val savedStudent = if (isAppAccessEnabled) {
                    try {
                        studentAccountRepository.provision(instId, savedCloudStudent.id, appAccessPassword!!)
                        savedCloudStudent.copy(isAppAccessEnabled = true)
                    } catch (error: Exception) {
                        // The profile is already safely created. Do not leave the owner on the
                        // form where a retry would look like another student registration.
                        onPartialSuccess(
                            "Student was saved, but app login could not be enabled. " +
                                "Open this student's profile and set a password again."
                        )
                        return@launch
                    }
                } else {
                    savedCloudStudent
                }
                if (savedStudent != savedCloudStudent) db.studentDao().updateStudent(savedStudent)
                StaffActivityLogger.logCompletedAction(
                    db, "student_created", "students", "Added student ${savedStudent.fullName}"
                )
                onSuccess(savedStudent.studentCode)
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
                StaffActivityLogger.logCompletedAction(
                    db, "student_app_access_changed", "students", "Set student app login for ${existing.fullName}"
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
        studentCodeIsAuto: Boolean,
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
        onPartialSuccess: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            val normalizedStudentCode = StudentIdGenerator.normalize(studentCode)
            val existing = db.studentDao().getStudentById(id, instId).firstOrNull() ?: run {
                onError("Student profile was not found.")
                return@launch
            }
            val unchangedLegacyCode = StudentIdGenerator.normalize(existing.studentCode) == normalizedStudentCode
            if (!StudentIdGenerator.isValid(normalizedStudentCode) && !unchangedLegacyCode) {
                onError("Student ID must contain 3 to 20 letters, numbers or hyphens.")
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
            // A legacy app-login record must be reset before that student can log in again,
            // but it must never block ordinary profile work such as replacing a photo. A
            // non-empty password below explicitly performs the reset; otherwise only the
            // student profile is saved.
            if (isAppAccessEnabled && existing.isAppAccessEnabled &&
                !existing.studentCode.equals(normalizedStudentCode, ignoreCase = true) &&
                appAccessPassword.isNullOrEmpty()) {
                onError("Re-enter the app password after changing the student ID.")
                return@launch
            }
            val combinedNotes = buildString {
                if (!whatsappNumber.isNullOrBlank()) {
                    appendLine("WhatsApp: $whatsappNumber")
                }
            }.trimEnd().takeIf { it.isNotEmpty() }
            val updated = existing.copy(
                studentCode = normalizedStudentCode,
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
                val resolvedStudent = resolveUniqueStudentCodeForUpdate(updated, studentCodeIsAuto)
                val admissionDateChanged = existing.admissionDateMs != admissionDateMs
                if (admissionDateChanged) {
                    // The admission date is part of the financial contract. Let the
                    // trusted ledger update the cloud profile, every active batch
                    // enrollment, and only unpaid monthly rows as one operation.
                    // Paid receipts are intentionally never touched here.
                    feeCollectionRepository.updateStudentAdmissionDate(
                        instituteId = instId,
                        studentId = id,
                        admissionDateMs = admissionDateMs
                    )
                    syncLocalActiveEnrollmentDates(
                        instituteId = instId,
                        studentId = id,
                        previousAdmissionDateMs = existing.admissionDateMs,
                        admissionDateMs = admissionDateMs
                    )
                }
                // An ordinary profile update must reach Firestore before the screen reports
                // success. The previous best-effort path could look saved on this device while
                // leaving the cloud record (and every other device) unchanged.
                val syncedStudent = syncStudentProfileWithUniqueCode(resolvedStudent, studentCodeIsAuto)
                val requiresAccountChange = appAccessPassword != null ||
                    isAppAccessEnabled != existing.isAppAccessEnabled
                val finalStudent = if (requiresAccountChange) {
                    try {
                        if (isAppAccessEnabled) {
                            studentAccountRepository.provision(instId, id, appAccessPassword!!)
                        } else {
                            studentAccountRepository.disable(instId, id)
                        }
                        syncedStudent.copy(isAppAccessEnabled = isAppAccessEnabled)
                    } catch (error: Exception) {
                        // Profile fields did save successfully. Keep Room consistent with the
                        // cloud and make the remaining account action explicit to the owner.
                        db.studentDao().updateStudent(syncedStudent)
                        onPartialSuccess(
                            "Student details were saved, but app login could not be updated. " +
                                "Try setting the password again from the student profile."
                        )
                        return@launch
                    }
                } else {
                    syncedStudent
                }
                db.studentDao().updateStudent(finalStudent)
                StaffActivityLogger.logCompletedAction(
                    db, "student_updated", "students", "Updated student ${finalStudent.fullName}"
                )
                onSuccess()
            } catch (error: Exception) {
                onError(accountErrorMessage(error, "Student account could not be updated."))
            }
        }
    }

    private suspend fun syncLocalActiveEnrollmentDates(
        instituteId: String,
        studentId: String,
        previousAdmissionDateMs: Long,
        admissionDateMs: Long
    ) = withContext(Dispatchers.IO) {
        val batchesById = db.batchDao().getBatchesByInstituteOnce(instituteId).associateBy { it.id }
        db.batchStudentDao().getActiveEnrollmentsForStudentOnce(studentId, instituteId).forEach { enrollment ->
            val previousAdmissionPeriod = MonthlyDueCalculator.periodFor(previousAdmissionDateMs)
            val admissionLinked = enrollment.firstMonthFeePeriod.isNullOrBlank() ||
                enrollment.firstMonthFeePeriod.equals(previousAdmissionPeriod, ignoreCase = true)
            if (!admissionLinked) return@forEach
            val batch = batchesById[enrollment.batchId] ?: return@forEach
            db.batchStudentDao().enrollStudent(
                enrollment.copy(
                    firstMonthFeePeriod = MonthlyDueCalculator.periodFor(admissionDateMs),
                    firstMonthFeeAmount = MonthlyDueCalculator.calculateFirstMonthFee(
                        batch.monthlyFeeAmount,
                        admissionDateMs
                    )
                )
            )
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

                // Step 2: Enroll in new batch (local). Freeze the first-month
                // amount now; future batch price edits must not alter it.
                val targetBatch = withContext(Dispatchers.IO) {
                    db.batchDao().getBatchesByInstituteOnce(instId)
                        .firstOrNull { it.id == newBatchId }
                }
                val enrollment = BatchStudentEntity(
                    id = UUID.randomUUID().toString(),
                    instituteId = instId,
                    batchId = newBatchId,
                    studentId = studentId,
                    joinedAtMs = now,
                    status = "active",
                    leftAtMs = null,
                    firstMonthFeePeriod = MonthlyDueCalculator.periodFor(now),
                    firstMonthFeeAmount = MonthlyDueCalculator.calculateFirstMonthFee(
                        targetBatch?.monthlyFeeAmount ?: 0.0,
                        now
                    )
                )
                withContext(Dispatchers.IO) {
                    db.batchStudentDao().enrollStudent(enrollment)
                }

                StaffActivityLogger.logCompletedAction(
                    db, "student_batch_changed", "batches", "Moved a student to another batch"
                )

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
        val functionsCode = (error as? FirebaseFunctionsException)?.code
        return when {
            error is StudentIdConflictException ||
                functionsCode == FirebaseFunctionsException.Code.ALREADY_EXISTS &&
                message.contains("Student ID", ignoreCase = true) ->
                "This Student ID is already in use."
            message.contains("PERMISSION_DENIED", ignoreCase = true) ->
                "You do not have permission to manage student app access."
            functionsCode == FirebaseFunctionsException.Code.ALREADY_EXISTS ||
                message.contains("ALREADY_EXISTS", ignoreCase = true) ->
                "This Student ID is already in use."
            message.contains("UNAVAILABLE", ignoreCase = true) ||
                message.contains("DEADLINE_EXCEEDED", ignoreCase = true) ->
                "Account service is temporarily unavailable. Check your connection and try again."
            else -> fallback
        }
    }

    private suspend fun createStudentWithUniqueCode(
        baseStudent: StudentEntity,
        autoGenerated: Boolean
    ): StudentEntity {
        var candidate = baseStudent.copy(studentCode = StudentIdGenerator.normalize(baseStudent.studentCode))
        repeat(STUDENT_ID_ATTEMPTS) {
            if (db.studentDao().isStudentCodeInUse(candidate.instituteId, candidate.studentCode)) {
                if (!autoGenerated) throw StudentIdConflictException()
                candidate = candidate.copy(studentCode = StudentIdGenerator.generate())
                return@repeat
            }
            try {
                val result = entitledCreationRepository.createStudent(candidate)
                return candidate.copy(
                    studentCode = StudentIdGenerator.normalize(result.studentCode),
                    photoUri = result.photoUri ?: candidate.photoUri
                )
            } catch (error: Exception) {
                if (!isStudentIdConflict(error) || !autoGenerated) throw error
                candidate = candidate.copy(studentCode = StudentIdGenerator.generate())
            }
        }
        throw StudentIdConflictException()
    }

    private suspend fun resolveUniqueStudentCodeForUpdate(
        baseStudent: StudentEntity,
        autoGenerated: Boolean
    ): StudentEntity {
        var candidate = baseStudent.copy(studentCode = StudentIdGenerator.normalize(baseStudent.studentCode))
        repeat(STUDENT_ID_ATTEMPTS) {
            val inUse = db.studentDao().isStudentCodeInUse(
                candidate.instituteId,
                candidate.studentCode,
                excludingStudentId = candidate.id
            )
            if (!inUse) return candidate
            if (!autoGenerated) throw StudentIdConflictException()
            candidate = candidate.copy(studentCode = StudentIdGenerator.generate())
        }
        throw StudentIdConflictException()
    }

    private suspend fun syncStudentProfileWithUniqueCode(
        baseStudent: StudentEntity,
        autoGenerated: Boolean
    ): StudentEntity {
        var candidate = baseStudent
        repeat(STUDENT_ID_ATTEMPTS) {
            try {
                StudentSyncHelper.upsertStudentOrThrow(candidate)
                return candidate
            } catch (error: Exception) {
                if (!isStudentIdConflict(error) || !autoGenerated) throw error
                candidate = candidate.copy(studentCode = StudentIdGenerator.generate())
                while (db.studentDao().isStudentCodeInUse(
                        candidate.instituteId,
                        candidate.studentCode,
                        excludingStudentId = candidate.id
                    )) {
                    candidate = candidate.copy(studentCode = StudentIdGenerator.generate())
                }
            }
        }
        throw StudentIdConflictException()
    }

    private fun isStudentIdConflict(error: Exception): Boolean {
        val functionsError = error as? FirebaseFunctionsException
        return functionsError?.code == FirebaseFunctionsException.Code.ALREADY_EXISTS &&
            functionsError.message.orEmpty().contains("Student ID", ignoreCase = true)
    }

    private class StudentIdConflictException : IllegalStateException("This Student ID is already in use.")

    private companion object {
        const val STUDENT_ID_ATTEMPTS = 12
    }
}

class StudentViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StudentViewModel::class.java)) return StudentViewModel(db) as T
        throw IllegalArgumentException()
    }
}

