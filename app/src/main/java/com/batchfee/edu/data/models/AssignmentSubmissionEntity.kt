package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "assignment_submissions")
data class AssignmentSubmissionEntity(
    @PrimaryKey val id: String,
    val assignmentId: String,
    val studentId: String,
    val instituteId: String,
    val status: String = "pending",       // pending, submitted, late, graded, returned
    val submittedAtMs: Long?,
    val attachmentUri: String?,
    val studentNote: String?,
    val marksObtained: Double?,
    val grade: String?,
    val percentage: Double?,
    val teacherFeedback: String?,
    val feedbackAttachmentUri: String?,
    val gradedAtMs: Long?,
    val resubmitRequested: Boolean = false
)
