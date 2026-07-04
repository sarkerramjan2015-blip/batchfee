package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exams")
data class ExamEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val batchId: String,
    val examName: String,
    val subject: String?,
    val examDateMs: Long,
    val totalMarks: Double,
    val passingMarks: Double,
    val teacherName: String?,
    val note: String?,
    val status: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archivedAtMs: Long?
)

