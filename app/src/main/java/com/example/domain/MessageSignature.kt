package com.example.domain

import com.example.data.database.AppDatabase

suspend fun loadInstituteSignature(db: AppDatabase, instituteId: String?): String {
    val instituteName = instituteId
        ?.let { db.instituteDao().getInstitute(it)?.name }
        .orEmpty()
        .trim()
    return instituteName.takeIf { it.isNotBlank() }?.let { "-$it" } ?: ""
}

fun appendInstituteSignature(message: String, instituteSignature: String): String {
    val trimmed = message.trimEnd()
    if (trimmed.isBlank() || instituteSignature.isBlank()) return trimmed
    return if (trimmed.endsWith(instituteSignature)) trimmed else "$trimmed\n$instituteSignature"
}
