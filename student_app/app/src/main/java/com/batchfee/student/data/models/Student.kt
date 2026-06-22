package com.batchfee.student.data.models

data class Student(
    val id: String = "",
    val instituteId: String = "",
    val studentCode: String = "",
    val fullName: String = "",
    val photoUri: String? = null,
    val gender: String? = null,
    val dateOfBirthMs: Long? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val schoolName: String? = null,
    val className: String? = null,
    val guardianName: String? = null,
    val guardianPhone: String? = null,
    val guardianEmail: String? = null,
    val emergencyContact: String? = null,
    val bloodGroup: String? = null,
    val admissionDateMs: Long = 0,
    val status: String = "active"
)

data class Institute(
    val id: String = "",
    val name: String = "",
    val phone: String? = null,
    val address: String? = null,
    val whatsappNumber: String? = null,
    val ownerName: String? = null,
    val email: String? = null,
    val instituteCode: String? = null,
    val profilePhotoUri: String? = null
)

data class Batch(
    val id: String = "",
    val instituteId: String = "",
    val batchCode: String = "",
    val name: String = "",
    val subject: String? = null,
    val className: String? = null,
    val teacherName: String? = null,
    val monthlyFeeAmount: Double = 0.0,
    val scheduleDays: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val status: String = "active"
)
