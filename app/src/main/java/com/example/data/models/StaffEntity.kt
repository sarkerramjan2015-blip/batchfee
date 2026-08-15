package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "staff",
    indices = [
        Index(value = ["instituteId", "archivedAtMs", "fullName"]),
        Index(value = ["staffCode", "archivedAtMs"])
    ]
)
data class StaffEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val staffCode: String,
    val fullName: String,
    val photoUri: String?,
    val roleTitle: String,
    val phone: String?,
    val email: String?,
    val address: String?,
    val joiningDateMs: Long?,
    val monthlySalary: Double,
    val assignedBatchIds: String?,
    val status: String,
    val notes: String?,
    val permissions: String? = null, // comma-separated permission flags e.g. "view_batch,collect_fee"
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archivedAtMs: Long?
)

