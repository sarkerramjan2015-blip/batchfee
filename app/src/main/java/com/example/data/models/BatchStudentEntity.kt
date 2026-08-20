package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "batch_students",
    indices = [
        Index(value = ["instituteId", "batchId", "status"]),
        Index(value = ["instituteId", "studentId", "status"])
    ]
)
data class BatchStudentEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val batchId: String,
    val studentId: String,
    val joinedAtMs: Long,
    val status: String, // active, removed
    val leftAtMs: Long?,
    /** Frozen when this enrollment is created so the first month's fee is stable. */
    val firstMonthFeePeriod: String? = null,
    val firstMonthFeeAmount: Double? = null,
    /** Optional owner-set monthly amount for this student in this specific batch. */
    val customMonthlyFeeAmount: Double? = null,
    /** Owner-only explanation for the custom amount; never shown in student receipts. */
    val customFeeReason: String? = null,
    /** The first billing period to which the custom monthly amount applies. */
    val customFeeEffectiveFromPeriod: String? = null,
    /** Marks that the trusted ledger has reconciled this custom-fee policy. */
    val customFeePolicySyncedAtMs: Long? = null
)

