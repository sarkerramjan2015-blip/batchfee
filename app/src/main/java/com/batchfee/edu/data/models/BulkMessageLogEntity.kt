package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bulk_message_log",
    indices = [Index(value = ["instituteId", "studentId", "channel", "messageText"])]
)
data class BulkMessageLogEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val studentId: String,
    val channel: String,          // "whatsapp" | "sms"
    val messageText: String,
    val status: String,           // "sent" | "failed" | "duplicate"
    val createdAtMs: Long
)
