package com.batchfee.student.data.models

data class Notice(
    val id: String = "",
    val instituteId: String = "",
    val title: String = "",
    val body: String = "",
    val targetBatchIds: List<String>? = null,
    val priority: String = "normal",
    val attachmentUrl: String? = null,
    val createdAtMs: Long = 0,
    val createdByUserId: String = ""
)
