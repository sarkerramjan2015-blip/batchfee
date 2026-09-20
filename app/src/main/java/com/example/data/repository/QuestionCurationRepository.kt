package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Deliberately anonymous view of a question awaiting platform curation.
 * The server only returns academic fields; tenant and teacher fields are never
 * parsed or retained by this client model.
 */
data class CurationQuestion(
    val id: String,
    val className: String,
    val subject: String,
    val chapter: String,
    val topic: String,
    val type: String,
    val language: String,
    val difficulty: String,
    val questionText: String,
    val options: List<String>,
    val correctAnswer: String,
    val explanation: String,
    val marks: Int,
    val syncedAtMs: Long,
)

data class QuestionCurationResult(
    val action: String,
    val pendingId: String,
    val publishedId: String?,
    val status: String,
    val reviewedAtMs: Long,
)

/** Super-admin-only transport for anonymous global question-bank moderation. */
class QuestionCurationRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1"),
) {
    suspend fun pending(limit: Int = 25): List<CurationQuestion> {
        val body = call(mapOf("action" to "list_pending", "limit" to limit.coerceIn(1, 50)))
        return (body["questions"] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull(::questionFrom)
    }

    suspend fun approve(
        pendingId: String,
        operationId: String = UUID.randomUUID().toString(),
    ): QuestionCurationResult = operation(
        action = "approve",
        pendingId = pendingId,
        operationId = operationId,
    )

    suspend fun reject(
        pendingId: String,
        reason: String,
        operationId: String = UUID.randomUUID().toString(),
    ): QuestionCurationResult = operation(
        action = "reject",
        pendingId = pendingId,
        operationId = operationId,
        reason = reason.trim(),
    )

    private suspend fun operation(
        action: String,
        pendingId: String,
        operationId: String,
        reason: String? = null,
    ): QuestionCurationResult {
        val request = buildMap<String, Any> {
            put("action", action)
            put("pendingId", pendingId)
            put("operationId", operationId)
            reason?.let { put("reason", it) }
        }
        val body = call(request)
        return QuestionCurationResult(
            action = body["action"] as? String ?: action,
            pendingId = body["pendingId"] as? String ?: pendingId,
            publishedId = body["publishedId"] as? String,
            status = body["status"] as? String ?: error("Missing curation status."),
            reviewedAtMs = (body["reviewedAtMs"] as? Number)?.toLong() ?: 0L,
        )
    }

    private suspend fun call(values: Map<String, Any>): Map<*, *> {
        val response = functions.getHttpsCallable("commitQuestionCurationOperation")
            .call(values)
            .await()
        return response.data as? Map<*, *> ?: error("Invalid question curation response.")
    }

    private fun questionFrom(data: Map<*, *>): CurationQuestion? {
        val id = data["id"] as? String ?: return null
        val questionText = data["questionText"] as? String ?: return null
        if (id.isBlank() || questionText.isBlank()) return null
        return CurationQuestion(
            id = id,
            className = data["className"] as? String ?: "",
            subject = data["subject"] as? String ?: "",
            chapter = data["chapter"] as? String ?: "",
            topic = data["topic"] as? String ?: "",
            type = data["type"] as? String ?: "",
            language = data["language"] as? String ?: "",
            difficulty = data["difficulty"] as? String ?: "",
            questionText = questionText,
            options = (data["options"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            correctAnswer = data["correctAnswer"] as? String ?: "",
            explanation = data["explanation"] as? String ?: "",
            marks = (data["marks"] as? Number)?.toInt() ?: 0,
            syncedAtMs = (data["syncedAtMs"] as? Number)?.toLong() ?: 0L,
        )
    }
}
