package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** Read-only academic question returned from the Super Admin-curated global bank. */
data class CuratedQuestion(
    val id: String,
    val curriculum: String,
    val syllabusYear: String,
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
)

data class QuestionBankFilters(
    val curriculum: String = "",
    val syllabusYear: String = "",
    val className: String = "",
    val subject: String = "",
    val chapter: String = "",
    val type: String = "",
    val difficulty: String = "",
    val search: String = "",
)

data class QuestionBankPage(
    val questions: List<CuratedQuestion>,
    val nextPageToken: String?,
)

/**
 * Global question content is never read from Firestore by the app. These
 * callable requests enforce active-institute and manage-exams authorization.
 */
class QuestionBankLibraryRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1"),
) {
    suspend fun listPage(
        instituteId: String,
        filters: QuestionBankFilters,
        pageToken: String? = null,
        limit: Int = 25,
    ): QuestionBankPage {
        val request = buildMap<String, Any> {
            put("action", "list")
            put("instituteId", instituteId)
            put("limit", limit.coerceIn(1, 50))
            pageToken?.takeIf(String::isNotBlank)?.let { put("pageToken", it) }
            put(
                "filters",
                mapOf(
                    "curriculum" to filters.curriculum.trim(),
                    "syllabusYear" to filters.syllabusYear.trim(),
                    "className" to filters.className.trim(),
                    "subject" to filters.subject.trim(),
                    "chapter" to filters.chapter.trim(),
                    "type" to filters.type,
                    "difficulty" to filters.difficulty,
                    "search" to filters.search.trim(),
                ),
            )
        }
        val body = call(request)
        val questions = (body["questions"] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull(::questionFrom)
        return QuestionBankPage(
            questions = questions,
            nextPageToken = body["nextPageToken"] as? String,
        )
    }

    suspend fun preparePaper(
        instituteId: String,
        questionIds: Collection<String>,
        operationId: String = UUID.randomUUID().toString(),
    ): List<ReviewableQuestion> {
        require(questionIds.isNotEmpty()) { "Choose at least one question." }
        val body = call(
            mapOf(
                "action" to "prepare_paper",
                "instituteId" to instituteId,
                "operationId" to operationId,
                "questionIds" to questionIds.toList(),
            ),
        )
        return (body["questions"] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull(::questionFrom)
            .map { question ->
                ReviewableQuestion(
                    sourceQuestionId = question.id,
                    selected = true,
                    questionText = question.questionText,
                    options = question.options,
                    correctAnswer = question.correctAnswer,
                    explanation = question.explanation,
                    difficulty = question.difficulty,
                    marks = question.marks,
                )
            }
    }

    private suspend fun call(values: Map<String, Any>): Map<*, *> {
        val response = functions.getHttpsCallable("commitQuestionBankLibraryOperation")
            .call(values)
            .await()
        return response.data as? Map<*, *> ?: error("Invalid question bank response.")
    }

    private fun questionFrom(data: Map<*, *>): CuratedQuestion? {
        val id = data["id"] as? String ?: return null
        val questionText = data["questionText"] as? String ?: return null
        val type = data["type"] as? String ?: return null
        val marks = (data["marks"] as? Number)?.toInt() ?: return null
        if (id.isBlank() || questionText.isBlank() || type !in setOf("mcq", "short", "creative") || marks !in 1..100) return null
        return CuratedQuestion(
            id = id,
            curriculum = data["curriculum"] as? String ?: "",
            syllabusYear = data["syllabusYear"] as? String ?: "",
            className = data["className"] as? String ?: "",
            subject = data["subject"] as? String ?: "",
            chapter = data["chapter"] as? String ?: "",
            topic = data["topic"] as? String ?: "",
            type = type,
            language = data["language"] as? String ?: "",
            difficulty = data["difficulty"] as? String ?: "",
            questionText = questionText,
            options = (data["options"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            correctAnswer = data["correctAnswer"] as? String ?: "",
            explanation = data["explanation"] as? String ?: "",
            marks = marks,
        )
    }
}
