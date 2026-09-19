package com.batchfee.edu.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Non-fee income such as admission forms, book sales, donations or events. */
@Entity(
    tableName = "other_income",
    indices = [Index(value = ["instituteId", "archivedAtMs", "incomeDateMs"])]
)
data class OtherIncomeEntity(
    @PrimaryKey val id: String,
    val instituteId: String,
    val category: String,
    val title: String,
    val amount: Double,
    val incomeDateMs: Long,
    val paymentMethod: String?,
    val note: String?,
    val createdByUserId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val archivedAtMs: Long? = null,
)
