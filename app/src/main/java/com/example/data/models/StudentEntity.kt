package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "students")
data class StudentEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val studentCode: String,
    val fullName: String,
    val photoUri: String?,
    val gender: String?,
    val dateOfBirthMs: Long?,
    val phone: String?,
    val email: String?,
    val address: String?,
    val schoolName: String?,
    val className: String?,
    val guardianName: String?,
    val guardianPhone: String?,
    val guardianEmail: String?,
    val emergencyContact: String?,
    val bloodGroup: String?,
    val admissionDateMs: Long,
    val status: String,
    val notes: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archivedAtMs: Long?
)

