package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "absent_messages")
data class AbsentMessageEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val batchId: String,
    val studentId: String,
    val attendanceDateMs: Long,
    val messageType: String,  // "whatsapp" | "sms"
    val messageText: String,
    val sentByUserId: String,
    val status: String,       // "sent" | "failed"
    val createdAtMs: Long
)

