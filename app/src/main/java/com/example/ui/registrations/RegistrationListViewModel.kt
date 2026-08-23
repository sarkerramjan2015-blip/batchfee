package com.batchfee.edu.ui.registrations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.RegistrationRepository
import com.batchfee.edu.data.firestore.StudentSyncHelper
import com.batchfee.edu.data.repository.EntitledCreationRepository
import com.batchfee.edu.data.models.PendingRegistration
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class RegistrationListViewModel(private val db: AppDatabase) : ViewModel() {
    private val entitledCreationRepository = EntitledCreationRepository()
    private val repository = RegistrationRepository()

    private val _pendingList = MutableStateFlow<List<PendingRegistration>>(emptyList())
    val pendingList: StateFlow<List<PendingRegistration>> = _pendingList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _processingRequestIds = MutableStateFlow<Set<String>>(emptySet())
    val processingRequestIds: StateFlow<Set<String>> = _processingRequestIds.asStateFlow()

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

    fun generateRegistrationLink(onText: (String) -> Unit, onError: (String) -> Unit) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            try {
                val institute = db.instituteDao().getInstitute(instId)
                    ?: throw IllegalStateException("Institute profile could not be found.")
                val profile = repository.syncPublicRegistrationProfile(
                    instituteId = institute.id,
                    name = institute.name,
                    phone = institute.phone,
                    logoUri = institute.profilePhotoUri
                )
                onText(repository.getRegistrationShareText(profile))
            } catch (e: Exception) {
                onError("Could not create the official registration link. Check your connection and try again.")
            }
        }
    }

    fun approveRegistration(registration: PendingRegistration) {
        if (!startProcessing(registration.requestId)) return
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

                // The request ID is an idempotency key on the trusted backend. It atomically
                // creates this student and consumes the pending registration, so a second tap
                // cannot create a duplicate even if it reaches the server.
                val cloudPhotoUri = entitledCreationRepository.createStudent(student, registration.requestId)
                db.studentDao().insertStudent(student.copy(photoUri = cloudPhotoUri ?: student.photoUri))
                _snackbarMessage.value = "${registration.fullName} approved and added to students."
            } catch (e: Exception) {
                _snackbarMessage.value = approvalErrorMessage(e)
            } finally {
                finishProcessing(registration.requestId)
                _isLoading.value = false
            }
        }
    }

    fun rejectRegistration(registration: PendingRegistration) {
        if (!startProcessing(registration.requestId)) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val instId = SessionManager.currentInstituteId.value ?: return@launch
                // Move to rejected collection before deleting
                repository.logRejectedRegistration(instId, registration)
                repository.deletePendingRegistration(instId, registration.requestId)
                _snackbarMessage.value = "${registration.fullName}'s registration rejected."
            } catch (e: Exception) {
                _snackbarMessage.value = "Failed to reject: ${e.message}"
            } finally {
                finishProcessing(registration.requestId)
                _isLoading.value = false
            }
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    private fun generateStudentCode(): String = com.batchfee.edu.domain.StudentIdGenerator.generate()

    private fun startProcessing(requestId: String): Boolean {
        if (requestId.isEmpty() || requestId in _processingRequestIds.value) return false
        _processingRequestIds.value = _processingRequestIds.value + requestId
        return true
    }

    private fun finishProcessing(requestId: String) {
        _processingRequestIds.value = _processingRequestIds.value - requestId
    }

    private fun approvalErrorMessage(error: Exception): String = when (
        (error as? FirebaseFunctionsException)?.code
    ) {
        FirebaseFunctionsException.Code.ALREADY_EXISTS,
        FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
            "This registration was already handled. Refresh the list."
        else -> "Failed to approve: ${error.message}"
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

