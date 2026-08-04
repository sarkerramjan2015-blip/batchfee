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
import com.batchfee.edu.data.firestore.FinanceSyncHelper
import com.batchfee.edu.data.models.BatchStudentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class StudentViewModel(private val db: AppDatabase) : ViewModel() {
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

        val student = StudentEntity(
            id = UUID.randomUUID().toString(),
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
            archivedAtMs = null
        )
        viewModelScope.launch {
            db.studentDao().insertStudent(student)
            onSuccess()
            // Firestore sync in background after immediate local save
            launch { StudentSyncHelper.upsertStudent(student) }
            launch {
                try {
                    val count = withContext(Dispatchers.IO) {
                        db.studentDao().getStudentsByInstituteOnce(instId).size
                    }
                    InstituteSyncHelper.updateStudentCount(instId, count)
                } catch (_: Exception) { }
            }
        }
    }

    suspend fun loadStudent(studentId: String): StudentEntity? {
        val instId = SessionManager.currentInstituteId.value ?: return null
        InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
        return db.studentDao().getStudentById(studentId, instId).firstOrNull()
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
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            val existing = db.studentDao().getStudentById(id, instId).firstOrNull() ?: run {
                onError("Student profile was not found.")
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
                updatedAtMs = System.currentTimeMillis()
            )
            StudentSyncHelper.upsertStudent(updated)
            db.studentDao().updateStudent(updated)
            onSuccess()
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

                // Step 3: Update fee batchIds (local)
                withContext(Dispatchers.IO) {
                    db.feeDao().updateFeeBatchIdForStudent(studentId, oldBatchId, newBatchId, instId, now)
                }

                onSuccess()

                // Step 4: Firestore sync (background, non-blocking)
                launch {
                    try {
                        BatchStudentSyncHelper.markRemoved(instId, oldBatchId, studentId, now)
                        BatchStudentSyncHelper.upsertEnrollment(enrollment)
                        val fees = withContext(Dispatchers.IO) {
                            db.feeDao().getFeesByStudentOnce(instId, studentId, newBatchId)
                        }
                        fees.forEach { fee ->
                            try { FinanceSyncHelper.upsertFee(fee) } catch (_: Exception) { }
                        }
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to shift student batch.")
            }
        }
    }
}

class StudentViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StudentViewModel::class.java)) return StudentViewModel(db) as T
        throw IllegalArgumentException()
    }
}

