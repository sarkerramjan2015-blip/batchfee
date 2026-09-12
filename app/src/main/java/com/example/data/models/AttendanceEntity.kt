package com.batchfee.edu.data.models

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
    /** Exact arrival instant captured when status is `late`. */
    val arrivalTimeMs: Long? = null,
    /** Scheduled class-start instant captured at marking time. */
    val scheduledStartTimeMs: Long? = null,
    /** Frozen difference between arrival and scheduled start. */
    val lateByMinutes: Int? = null,
    val markedByUserId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

