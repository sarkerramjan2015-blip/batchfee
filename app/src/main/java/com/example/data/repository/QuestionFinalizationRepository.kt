package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class QuestionFinalizationResult(
    val operationId: String,
    val questionCount: Int,
    val costPoisha: Int,
    val billingStatus: String,
)

/**
 * The client can only submit reviewed content. The trusted callable rechecks ownership,
 * source IDs, academic metadata and every field before writing the private question bank.
 */
class QuestionFinalizationRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1"),
) {
    suspend fun finalize(
        instituteId: String,
        generationOperationId: String,
        questionType: String,
        questions: List<ReviewableQuestion>,
        operationId: String = UUID.randomUUID().toString(),
    ): QuestionFinalizationResult {
        require(questions.isNotEmpty()) { "Select at least one valid question." }
        val response = functions.getHttpsCallable("finalizeExamQuestions").call(
            mapOf(
                "instituteId" to instituteId,
                "generationOperationId" to generationOperationId,
                "operationId" to operationId,
                "questionType" to questionType,
                "questions" to questions.filter { it.selected }.map { question ->
                    mapOf(
                        "sourceQuestionId" to question.sourceQuestionId,
                        "questionText" to question.questionText.trim(),
                        "options" to question.options.map(String::trim),
                        "correctAnswer" to question.correctAnswer.trim(),
                        "explanation" to question.explanation.trim(),
                        "difficulty" to question.difficulty,
                        "marks" to question.marks,
                    )
                },
            ),
        ).await()
        val body = response.data as? Map<*, *> ?: error("Invalid finalization response.")
        return QuestionFinalizationResult(
            operationId = body["operationId"] as? String ?: operationId,
            questionCount = (body["questionCount"] as? Number)?.toInt() ?: 0,
            costPoisha = (body["costPoisha"] as? Number)?.toInt() ?: 0,
            billingStatus = body["billingStatus"] as? String ?: "not_configured",
        )
    }
}
