package com.batchfee.edu.ui.registrations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteSyncHelper
import com.batchfee.edu.data.firestore.RegistrationRepository
import com.batchfee.edu.data.firestore.StudentSyncHelper
import com.batchfee.edu.data.models.PendingRegistration
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class RegistrationListViewModel(private val db: AppDatabase) : ViewModel() {
    private val repository = RegistrationRepository()

    private val _pendingList = MutableStateFlow<List<PendingRegistration>>(emptyList())
    val pendingList: StateFlow<List<PendingRegistration>> = _pendingList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private var instituteId: String = ""

    init {
        loadRegistrations()
    }

    private fun loadRegistrations() {
        viewModelScope.launch {
            _isLoading.value = true
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            instituteId = instId
            repository.pendingRegistrationsFlow(instId).collect { list ->
                _pendingList.value = list.sortedByDescending { it.submittedAt }
                _isLoading.value = false
            }
        }
    }

    fun generateRegistrationLink(): String {
        val instId = SessionManager.currentInstituteId.value ?: return ""
        viewModelScope.launch {
            try {
                val institute = db.instituteDao().getInstitute(instId)
                if (institute != null) {
                    repository.syncInstituteInfo(institute.id, institute.name, institute.phone)
                }
            } catch (_: Exception) {}
        }
        return repository.getRegistrationFormUrl(instId)
    }

    fun approveRegistration(registration: PendingRegistration) {
        if (registration.requestId.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val instId = SessionManager.currentInstituteId.value ?: return@launch
                val studentCode = generateStudentCode()

                val combinedNotes = buildString {
                    if (!registration.whatsappNumber.isNullOrBlank()) {
                        appendLine("WhatsApp: ${registration.whatsappNumber}")
                    }
                }.trimEnd().takeIf { it.isNotEmpty() }

                val student = StudentEntity(
                    id = UUID.randomUUID().toString(),
                    instituteId = instId,
                    studentCode = studentCode,
                    fullName = registration.fullName,
                    photoUri = null,
                    gender = registration.gender,
                    dateOfBirthMs = registration.dateOfBirthMs,
                    phone = registration.phone,
                    email = null,
                    address = registration.address,
                    schoolName = registration.schoolName,
                    className = registration.className,
                    guardianName = registration.guardianName,
                    guardianPhone = null,
                    guardianEmail = null,
                    emergencyContact = null,
                    bloodGroup = null,
                    admissionDateMs = System.currentTimeMillis(),
                    status = "active",
                    notes = combinedNotes,
                    createdAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                    archivedAtMs = null
                )

                StudentSyncHelper.upsertStudent(student)
                db.studentDao().insertStudent(student)
                val count = db.studentDao().getStudentsByInstituteOnce(instId).size
                InstituteSyncHelper.updateStudentCount(instId, count)
                repository.deletePendingRegistration(instId, registration.requestId)
                _snackbarMessage.value = "${registration.fullName} approved and added to students."
            } catch (e: Exception) {
                _snackbarMessage.value = "Failed to approve: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun rejectRegistration(registration: PendingRegistration) {
        if (registration.requestId.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val instId = SessionManager.currentInstituteId.value ?: return@launch
                repository.deletePendingRegistration(instId, registration.requestId)
                _snackbarMessage.value = "${registration.fullName}'s registration rejected."
            } catch (e: Exception) {
                _snackbarMessage.value = "Failed to reject: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    private fun generateStudentCode(): String {
        val digits = UUID.randomUUID().toString().filter(Char::isDigit) + System.currentTimeMillis().toString()
        return digits.take(8).padEnd(8, '0')
    }
}

class RegistrationListViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RegistrationListViewModel::class.java)) {
            return RegistrationListViewModel(db) as T
        }
        throw IllegalArgumentException()
    }
}

