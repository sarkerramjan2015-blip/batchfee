package com.batchfee.edu.ui.studentapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batchfee.edu.data.repository.FeeInfo
import com.batchfee.edu.data.repository.ResultInfo
import com.batchfee.edu.data.repository.StudentDataRepository
import com.batchfee.edu.domain.StudentSessionManager
import com.batchfee.edu.data.models.InstituteEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StudentDashboardState(
    val studentName: String = "",
    val studentCode: String = "",
    val className: String? = null,
    val phone: String? = null,
    val photoUri: String? = null,
    val instituteName: String = "",
    val instituteCode: String = "",
    val totalFee: Double = 0.0,
    val totalPaid: Double = 0.0,
    val totalDue: Double = 0.0,
    val attendancePercent: Double = 0.0,
    val latestGrade: String = "-",
    val latestPercentage: String = "-",
    val isLoading: Boolean = true,
    val error: String? = null
)

class StudentDashboardViewModel : ViewModel() {
    private val repo = StudentDataRepository()
    private val _state = MutableStateFlow(StudentDashboardState())
    val state: StateFlow<StudentDashboardState> = _state.asStateFlow()

    init { load() }

    fun load() {
        val sid = StudentSessionManager.studentId.value ?: return
        val iid = StudentSessionManager.instituteId.value ?: return
        _state.value = _state.value.copy(
            studentName = StudentSessionManager.studentName.value ?: "",
            studentCode = StudentSessionManager.studentCode.value ?: "",
            instituteCode = StudentSessionManager.instituteCode.value ?: "",
            isLoading = true, error = null
        )
        viewModelScope.launch {
            try {
                val student = repo.fetchStudent(sid, iid)
                val institute = repo.fetchInstitute(iid)
                val fees = repo.fetchFees(iid, sid)
                val attendance = repo.fetchAttendance(iid, sid)
                val results = repo.fetchResults(iid, sid)

                val totalFee = fees.sumOf { it.totalAmount }
                val totalPaid = fees.sumOf { it.paidAmount }
                val totalDue = totalFee - totalPaid

                val presentCount = attendance.count { it.status.equals("present", ignoreCase = true) }
                val attendancePercent =
                    if (attendance.isNotEmpty()) presentCount * 100.0 / attendance.size else 0.0

                val latestResult = results.maxByOrNull { it.examDateMs ?: 0L }

                _state.value = _state.value.copy(
                    studentName = student?.fullName ?: _state.value.studentName,
                    studentCode = student?.studentCode ?: _state.value.studentCode,
                    className = student?.className,
                    phone = student?.phone,
                    photoUri = student?.photoUri,
                    instituteName = institute?.name ?: "",
                    totalFee = totalFee,
                    totalPaid = totalPaid,
                    totalDue = totalDue,
                    attendancePercent = attendancePercent,
                    latestGrade = latestResult?.grade ?: "-",
                    latestPercentage = if (latestResult != null) "%.0f%%".format(latestResult.percentage) else "-",
                    isLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = "Could not load data.")
            }
        }
    }
}
