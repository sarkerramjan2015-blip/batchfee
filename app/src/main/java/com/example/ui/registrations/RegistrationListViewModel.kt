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
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.StudentIdGenerator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
            repository.pendingRegistrationsFlow(instId)
                .retryWhen { error, attempt ->
                    val firestoreError = error as? FirebaseFirestoreException
                    val retryable = firestoreError?.code in setOf(
                        FirebaseFirestoreException.Code.UNAUTHENTICATED,
                        FirebaseFirestoreException.Code.PERMISSION_DENIED,
                        FirebaseFirestoreException.Code.UNAVAILABLE,
                        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
                    )
                    if (!retryable || attempt >= 2) return@retryWhen false
                    if (firestoreError?.code == FirebaseFirestoreException.Code.UNAUTHENTICATED ||
                        firestoreError?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                    ) {
                        runCatching {
                            FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await()
                        }
                    }
                    delay(500L * (attempt + 1))
                    true
                }
                .catch { error ->
                    if (error is CancellationException) throw error
                    FirebaseFailureReporter.report(
                        error,
                        operation = "load pending registrations",
                        permissionDeniedIsExpected = true
                    )
                    _pendingList.value = emptyList()
                    _isLoading.value = false
                }
                .collect { list ->
                    _pendingList.value = list.sortedByDescending { it.submittedAt }
                    _isLoading.value = false
                }
        }
    }

    fun generateRegistrationLink(onText: (String) -> Unit, onError: (String) -> Unit) {
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            try {
                val profile = repository.syncPublicRegistrationProfile(instId)
                onText(repository.getRegistrationShareText(profile))
            } catch (e: Exception) {
                val functionsError = e as? FirebaseFunctionsException
                val message = when (functionsError?.code) {
                    FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                        "Your session expired. Please log in again."
                    FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                        functionsError.message ?: "Renew the subscription to use registration links."
                    FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                        "You do not have permission to create a registration link."
                    else -> "Could not create the official registration link. Check your connection and try again."
                }
                onError(message)
            }
        }
    }

    fun approveRegistration(registration: PendingRegistration) {
        if (!startProcessing(registration.requestId)) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val instId = SessionManager.currentInstituteId.value ?: return@launch
                val combinedNotes = buildString {
                    if (!registration.whatsappNumber.isNullOrBlank()) {
                        appendLine("WhatsApp: ${registration.whatsappNumber}")
                    }
                }.trimEnd().takeIf { it.isNotEmpty() }

                val student = StudentEntity(
                    id = UUID.randomUUID().toString(),
                    instituteId = instId,
                    studentCode = generateStudentCode(),
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
                val savedStudent = createRegisteredStudentWithUniqueCode(student, registration.requestId)
                db.studentDao().insertStudent(savedStudent)
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

    private suspend fun createRegisteredStudentWithUniqueCode(
        baseStudent: StudentEntity,
        registrationRequestId: String
    ): StudentEntity {
        var candidate = baseStudent
        repeat(STUDENT_ID_ATTEMPTS) {
            if (db.studentDao().isStudentCodeInUse(candidate.instituteId, candidate.studentCode)) {
                candidate = candidate.copy(studentCode = StudentIdGenerator.generate())
                return@repeat
            }
            try {
                val result = entitledCreationRepository.createStudent(candidate, registrationRequestId)
                return candidate.copy(
                    studentCode = StudentIdGenerator.normalize(result.studentCode),
                    photoUri = result.photoUri ?: candidate.photoUri
                )
            } catch (error: Exception) {
                val functionsError = error as? FirebaseFunctionsException
                val idConflict = functionsError?.code == FirebaseFunctionsException.Code.ALREADY_EXISTS &&
                    functionsError.message.orEmpty().contains("Student ID", ignoreCase = true)
                if (!idConflict) throw error
                candidate = candidate.copy(studentCode = StudentIdGenerator.generate())
            }
        }
        throw IllegalStateException("Could not reserve a unique Student ID. Please try again.")
    }

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
        FirebaseFunctionsException.Code.ALREADY_EXISTS ->
            if (error.message.orEmpty().contains("Student ID", ignoreCase = true)) {
                "Could not reserve a unique Student ID. Please try again."
            } else {
                "This registration was already handled. Refresh the list."
            }
        FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
            error.message ?: "The subscription or registration is no longer active."
        FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
            error.message ?: "The current student limit has been reached."
        FirebaseFunctionsException.Code.UNAUTHENTICATED ->
            "Your session expired. Please log in again."
        FirebaseFunctionsException.Code.PERMISSION_DENIED ->
            "You do not have permission to approve registrations."
        else -> "Failed to approve: ${error.message}"
    }

    private companion object {
        const val STUDENT_ID_ATTEMPTS = 12
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

