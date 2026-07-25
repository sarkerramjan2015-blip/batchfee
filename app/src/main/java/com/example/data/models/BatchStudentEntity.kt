package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "batch_students")
data class BatchStudentEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val batchId: String,
    val studentId: String,
    val joinedAtMs: Long,
    val status: String, // active, removed
    val leftAtMs: Long?
)

