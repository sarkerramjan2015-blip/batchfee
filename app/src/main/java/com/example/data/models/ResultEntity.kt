package com.example.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "results")
data class ResultEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val examId: String,
    val batchId: String,
    val studentId: String,
    val marksObtained: Double,
    val grade: String?,
    val position: Int?,
    val remarks: String?,
    val published: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long
)
