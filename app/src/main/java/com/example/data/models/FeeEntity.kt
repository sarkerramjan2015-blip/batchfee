package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "fees",
    indices = [
        Index(value = ["instituteId", "businessKey"], unique = true),
        Index(value = ["instituteId", "cancelledAtMs", "dueDateMs"]),
        Index(value = ["instituteId", "studentId", "cancelledAtMs"]),
        Index(value = ["instituteId", "sourceId"])
    ]
)
data class FeeEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val studentId: String,
    val batchId: String?,
    val feePeriod: String,
    val feeType: String,
    val dueDateMs: Long,
    val baseAmount: Double,
    val discountAmount: Double,
    val lateFeeAmount: Double,
    val totalAmount: Double,
    val paidAmount: Double,
    val dueAmount: Double,
    val status: String,
    val note: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val cancelledAtMs: Long?,
    /** Stable source link for generated fees, such as the exam that created an exam fee. */
    val sourceId: String? = null,
    val businessKey: String? = null,
    val ledgerVersion: Int = 0
)

