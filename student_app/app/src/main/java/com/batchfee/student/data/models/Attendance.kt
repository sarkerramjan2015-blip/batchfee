package com.batchfee.student.data.models

data class Attendance(
    val id: String = "",
    val instituteId: String = "",
    val batchId: String = "",
    val studentId: String = "",
    val attendanceDateMs: Long = 0,
    val status: String = "",
    val note: String? = null
)

data class AttendanceSummary(
    val totalClasses: Int = 0,
    val present: Int = 0,
    val absent: Int = 0,
    val late: Int = 0
) {
    val percentage: Float
        get() = if (totalClasses > 0) (present.toFloat() / totalClasses * 100) else 0f
}
