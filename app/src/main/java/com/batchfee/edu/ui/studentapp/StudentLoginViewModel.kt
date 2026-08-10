package com.batchfee.edu.ui.studentapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.repository.StudentAuthRepository
import com.batchfee.edu.data.repository.StudentLoginResult
import com.batchfee.edu.domain.StudentSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StudentLoginUiState(
    val instituteCode: String = "",
    val studentId: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val passwordVisible: Boolean = false
)

class StudentLoginViewModel : ViewModel() {
    private val authRepo = StudentAuthRepository()

    private val _uiState = MutableStateFlow(StudentLoginUiState())
    val uiState: StateFlow<StudentLoginUiState> = _uiState.asStateFlow()

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess.asStateFlow()

    fun updateInstituteCode(code: String) {
        _uiState.value = _uiState.value.copy(instituteCode = code, errorMessage = null)
    }

    fun updateStudentId(id: String) {
        _uiState.value = _uiState.value.copy(studentId = id, errorMessage = null)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password, errorMessage = null)
    }

    fun togglePasswordVisibility() {
        _uiState.value = _uiState.value.copy(passwordVisible = !_uiState.value.passwordVisible)
    }

    fun login() {
        val state = _uiState.value
        val instituteCode = state.instituteCode.trim()
        val id = state.studentId.trim()
        val pw = state.password

        if (instituteCode.isEmpty()) { _uiState.value = state.copy(errorMessage = "Please enter your institute code."); return }
        if (id.isEmpty()) { _uiState.value = state.copy(errorMessage = "Please enter your student ID."); return }
        if (pw.isEmpty()) { _uiState.value = state.copy(errorMessage = "Please enter your password."); return }

        _uiState.value = state.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val result = authRepo.login(instituteCode, id, pw)
            if (result.success) {
                val sessionStarted = StudentSessionManager.login(
                    firebaseUid = result.firebaseUid,
                    studentId = result.studentId,
                    instituteId = result.instituteId,
                    instituteCode = result.instituteCode,
                    studentName = result.studentName,
                    studentCodeStr = result.studentCode,
                    expiresAtMs = result.sessionExpiresAtMs
                )
                if (sessionStarted) {
                    _loginSuccess.value = true
                    _uiState.value = _uiState.value.copy(isLoading = false)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Student session could not be verified. Please log in again."
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }
}
