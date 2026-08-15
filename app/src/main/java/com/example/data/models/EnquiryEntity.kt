package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "enquiries")
data class EnquiryEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val name: String,
    val phone: String,
    val address: String?,
    val subjectName: String,
    val enquiryDateMs: Long,
    val status: String,
    /** Scheduled contact date. Null means an older, unscheduled follow-up. */
    val followUpDateMs: Long? = null,
    val note: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archivedAtMs: Long?
)

