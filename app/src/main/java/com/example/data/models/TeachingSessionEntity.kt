package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A teacher's completed class.  It is separate from daily staff attendance:
 * attendance answers “was the person present?”, while this record answers
 * “which class was actually completed and what amount did it earn?”.
 */
@Entity(
    tableName = "teaching_sessions",
    indices = [
        Index(value = ["instituteId", "staffId", "sessionDateMs"]),
        Index(value = ["instituteId", "batchId", "sessionDateMs"]),
        Index(value = ["instituteId", "sessionKey"], unique = true),
        Index(value = ["salaryId"])
    ]
)
data class TeachingSessionEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val staffId: String,
    val batchId: String,
    /** Stable key for one teacher + batch + scheduled slot on one day. */
    val sessionKey: String,
    val subject: String?,
    val sessionDateMs: Long,
    val durationMinutes: Int,
    val salaryTypeSnapshot: String,
    val rateSnapshot: Double,
    val calculatedAmount: Double,
    /** Filled once this session has been included in a salary record. */
    val salaryId: String? = null,
    val note: String? = null,
    val createdByUserId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val deletedAtMs: Long? = null
)
