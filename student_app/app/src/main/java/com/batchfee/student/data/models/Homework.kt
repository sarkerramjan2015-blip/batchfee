package com.batchfee.student.data.models

data class Homework(
    val id: String = "",
    val instituteId: String = "",
    val batchId: String = "",
    val subject: String = "",
    val title: String = "",
    val description: String = "",
    val attachmentUrl: String? = null,
    val assignedDateMs: Long = 0,
    val deadlineDateMs: Long = 0,
    val createdAtMs: Long = 0
)
