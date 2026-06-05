package com.example.data.models

data class PendingRegistration(
    val requestId: String = "",
    val instituteId: String = "",
    val fullName: String = "",
    val phone: String = "",
    val guardianName: String? = null,
    val whatsappNumber: String? = null,
    val gender: String? = null,
    val dateOfBirthMs: Long? = null,
    val schoolName: String? = null,
    val className: String? = null,
    val address: String? = null,
    val submittedAt: Long = System.currentTimeMillis(),
    val status: String = "pending"
)
