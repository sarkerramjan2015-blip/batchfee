package com.example.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminder_templates")
data class ReminderTemplateEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val title: String,
    val type: String,
    val messageTemplate: String,
    val isDefault: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long
)
