package com.example.ui.students

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.models.StudentEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class StudentViewModel(private val db: AppDatabase) : ViewModel() {
    private val _studentList = MutableStateFlow<List<StudentEntity>>(emptyList())
    val studentList = _studentList.asStateFlow()

    init {
        loadStudents()
    }

    private fun loadStudents() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            db.studentDao().getStudentsByInstitute(instId).collect {
                _studentList.value = it
            }
        }
    }

    fun generateStudentCode(): String {
        val now = Date()
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        return "STD-${fmt.format(now)}"
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
        notes: String?,
        photoUri: String?,
        onSuccess: () -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val combinedNotes = buildString {
            if (!whatsappNumber.isNullOrBlank()) {
                appendLine("WhatsApp: $whatsappNumber")
            }
            if (!notes.isNullOrBlank()) {
                append(notes)
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
            admissionDateMs = System.currentTimeMillis(),
            status = "active",
            notes = combinedNotes,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            archivedAtMs = null
        )
        viewModelScope.launch {
            db.studentDao().insertStudent(student)
            onSuccess()
        }
    }

    suspend fun loadStudent(studentId: String): StudentEntity? {
        val instId = SessionManager.currentInstituteId.value ?: return null
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
        notes: String?,
        photoUri: String?,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            val existing = db.studentDao().getStudentById(id, instId).firstOrNull() ?: return@launch
            val combinedNotes = buildString {
                if (!whatsappNumber.isNullOrBlank()) {
                    appendLine("WhatsApp: $whatsappNumber")
                }
                if (!notes.isNullOrBlank()) {
                    append(notes)
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
                notes = combinedNotes,
                photoUri = photoUri,
                updatedAtMs = System.currentTimeMillis()
            )
            db.studentDao().updateStudent(updated)
            onSuccess()
        }
    }
}

class StudentViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StudentViewModel::class.java)) return StudentViewModel(db) as T
        throw IllegalArgumentException()
    }
}
