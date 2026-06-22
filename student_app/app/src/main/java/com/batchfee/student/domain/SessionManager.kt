package com.batchfee.student.domain

import com.batchfee.student.demo.DemoDataProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionManager {
    private val _currentStudentId = MutableStateFlow<String?>(null)
    val currentStudentId: StateFlow<String?> = _currentStudentId.asStateFlow()

    private val _currentInstituteId = MutableStateFlow<String?>(null)
    val currentInstituteId: StateFlow<String?> = _currentInstituteId.asStateFlow()

    private val _studentName = MutableStateFlow<String?>(null)
    val studentName: StateFlow<String?> = _studentName.asStateFlow()

    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    fun isLoggedIn(): Boolean = _currentStudentId.value != null

    fun login(studentId: String, instituteId: String, name: String) {
        _currentStudentId.value = studentId
        _currentInstituteId.value = instituteId
        _studentName.value = name
        _isDemoMode.value = false
    }

    fun loginDemo() {
        _currentStudentId.value = DemoDataProvider.STUDENT_ID
        _currentInstituteId.value = DemoDataProvider.INSTITUTE_ID
        _studentName.value = DemoDataProvider.mockStudent.fullName
        _isDemoMode.value = true
    }

    fun logout() {
        _currentStudentId.value = null
        _currentInstituteId.value = null
        _studentName.value = null
        _isDemoMode.value = false
    }
}
