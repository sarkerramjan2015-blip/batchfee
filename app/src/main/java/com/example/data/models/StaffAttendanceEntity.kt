package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "staff_attendance")
data class StaffAttendanceEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val staffId: String,
    val attendanceDateMs: Long,
    val status: String,
    val note: String?,
    val markedByUserId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

