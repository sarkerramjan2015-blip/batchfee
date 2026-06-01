package com.example.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val userId: String?,
    val action: String,
    val module: String,
    val description: String,
    val oldValue: String?,
    val newValue: String?,
    val createdAtMs: Long
)
