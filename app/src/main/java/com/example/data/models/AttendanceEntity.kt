package com.example.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "attendance",
    indices = [Index(value = ["instituteId", "batchId", "studentId", "attendanceDateMs"], unique = true)]
)
data class AttendanceEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val batchId: String,
    val studentId: String,
    val attendanceDateMs: Long,
    val status: String,
    val note: String?,
    val markedByUserId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)
