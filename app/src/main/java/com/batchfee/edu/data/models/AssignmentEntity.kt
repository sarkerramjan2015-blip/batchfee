package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "assignments")
data class AssignmentEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val batchId: String?,
    val title: String,
    val subject: String?,
    val className: String?,
    val assignmentType: String = "individual", // individual, group, project, presentation, written, lab
    val instructions: String,
    val learningObjective: String?,
    val totalMarks: Double?,
    val passingMarks: Double?,
    val gradingMethod: String = "marks",    // marks, grade, percentage, rubric
    val rubricJson: String?,                // JSON for rubric criteria
    val startDateMs: Long,
    val dueDateMs: Long?,
    val allowLateSubmission: Boolean = false,
    val latePenalty: String?,               // e.g. "1 mark per day"
    val submissionFormat: String = "any",   // pdf, word, image, video, presentation, text, link, any
    val maxFileSizeKb: Long?,
    val referenceMaterials: String?,         // comma-separated URIs or text
    val status: String = "draft",            // draft, published, closed
    val publishDateMs: Long?,               // for scheduled publishing
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archivedAtMs: Long?
)
