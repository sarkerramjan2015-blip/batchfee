package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "batches",
    indices = [Index(value = ["instituteId", "archivedAtMs", "name"])]
)
data class BatchEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val batchCode: String,
    val name: String,
    val subject: String?,
    val className: String?,
    val teacherName: String?,
    val monthlyFeeAmount: Double,
    val admissionFeeAmount: Double,
    val startDateMs: Long?,
    val endDateMs: Long?,
    val scheduleDays: String?,
    val startTime: String?,
    val endTime: String?,
    val maxStudents: Int?,
    val status: String,
    val description: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archivedAtMs: Long?,
    /** `monthly` for all legacy rows; `course` bills the course fee once. */
    val billingMode: String = "monthly",
    /** One-time fee for a course batch. Monthly batches keep this at zero. */
    val courseFeeAmount: Double = 0.0
)

