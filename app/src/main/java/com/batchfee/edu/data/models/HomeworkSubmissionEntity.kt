package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "homework_submissions")
data class HomeworkSubmissionEntity(
    @PrimaryKey val id: String,
    val homeworkId: String,
    val studentId: String,
    val instituteId: String,
    val status: String = "pending",      // pending, submitted, late
    val submittedAtMs: Long?,
    val attachmentUri: String?,
    val studentNote: String?
)
