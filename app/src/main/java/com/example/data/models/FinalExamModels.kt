package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Multi-subject final exam. Status: draft → in_progress → completed (published). */
@Entity(
    tableName = "final_exams",
    indices = [androidx.room.Index("instituteId")]
)
data class FinalExamEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val batchId: String,
    val examName: String,
    val status: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val publishedAtMs: Long? = null,
    val archivedAtMs: Long? = null,
    val examFeeAmount: Double = 0.0
)

/**
 * One subject inside a final exam. `components` is a comma-separated list from
 * mcq / cq / practical / total_only — the columns shown during marks entry.
 */
@Entity(
    tableName = "final_exam_subjects",
    indices = [androidx.room.Index("finalExamId")]
)
data class FinalExamSubjectEntity(
    @PrimaryKey val id: String,
    val finalExamId: String,
    val instituteId: String,
    val subjectName: String,
    val fullMarks: Double,
    val passMarks: Double,
    val components: String,
    val assignedStaffId: String? = null,
    val assignedStaffName: String? = null,
    val sortOrder: Int = 0,
    val mcqFullMarks: Double = 0.0,
    val cqFullMarks: Double = 0.0,
    val practicalFullMarks: Double = 0.0,
    val mcqPassMarks: Double = 0.0,
    val cqPassMarks: Double = 0.0,
    val practicalPassMarks: Double = 0.0,
    val marksEntryEnabled: Boolean = true
)

/**
 * Student marks for one subject of one final exam.
 * Status: draft → submitted → under_review → approved (locked) → published.
 * Owner edits after approval are allowed and recorded via AuditLog.
 */
@Entity(
    tableName = "final_exam_marks",
    indices = [
        androidx.room.Index("finalExamId"),
        androidx.room.Index("subjectId"),
        androidx.room.Index(value = ["finalExamId", "subjectId", "studentId"], unique = true)
    ]
)
data class FinalExamMarksEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val finalExamId: String,
    val subjectId: String,
    val studentId: String,
    val mcqMarks: Double,
    val cqMarks: Double,
    val practicalMarks: Double,
    val totalMarks: Double,
    val status: String,
    val enteredByUserId: String,
    val enteredByName: String,
    val submittedAtMs: Long? = null,
    val reviewedAtMs: Long? = null,
    val approvedAtMs: Long? = null,
    val updatedAtMs: Long
)
