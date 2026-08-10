package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "works")
data class WorkEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val batchId: String?,
    val type: String,          // "HOMEWORK" or "ASSIGNMENT"
    val title: String,
    val description: String,
    val dueDateMs: Long?,      // null = no deadline
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archivedAtMs: Long?
)
