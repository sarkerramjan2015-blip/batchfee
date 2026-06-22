package com.batchfee.student.data.models

data class Exam(
    val id: String = "",
    val instituteId: String = "",
    val batchId: String = "",
    val examName: String = "",
    val subject: String? = null,
    val examDateMs: Long = 0,
    val totalMarks: Double = 0.0,
    val passingMarks: Double = 0.0,
    val teacherName: String? = null,
    val note: String? = null,
    val status: String = ""
)

data class Result(
    val id: String = "",
    val instituteId: String = "",
    val examId: String = "",
    val batchId: String = "",
    val studentId: String = "",
    val marksObtained: Double = 0.0,
    val grade: String? = null,
    val position: Int? = null,
    val remarks: String? = null,
    val published: Boolean = false
)

data class MeritEntry(
    val position: Int = 0,
    val studentId: String = "",
    val studentName: String = "",
    val totalMarks: Double = 0.0,
    val grade: String = "",
    val isSelf: Boolean = false
)

data class BatchStudent(
    val id: String = "",
    val instituteId: String = "",
    val batchId: String = "",
    val studentId: String = "",
    val joinedAtMs: Long = 0,
    val status: String = "active"
)
