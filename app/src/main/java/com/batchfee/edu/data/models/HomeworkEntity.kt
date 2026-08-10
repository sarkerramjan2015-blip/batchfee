package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "homework")
data class HomeworkEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val batchId: String?,
    val title: String,
    val subject: String?,
    val className: String?,       // e.g. "Class 7 - Section A"
    val instructions: String,
    val bookPage: String?,        // e.g. "Page 25-27"
    val startDateMs: Long,
    val dueDateMs: Long?,
    val attachmentUri: String?,
    val requiresSubmission: Boolean = false,
    val status: String = "active", // active, closed
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archivedAtMs: Long?
)
