package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "background_sync")
data class BackgroundSyncEntity(
    @PrimaryKey val id: String,
    val actorUid: String,
    val instituteId: String,
    val kind: String,
    val documentId: String,
    val payload: String,
    val createdAtMs: Long
)
