package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class QuestionFinalizationResult(
    val operationId: String,
    val questionCount: Int,
    val costPoisha: Int,
    val billingStatus: String,
    val quotedCostPoisha: Int = costPoisha,
    val chargedCostPoisha: Int = costPoisha,
    val remainingBalancePoisha: Int = 0,
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
        sourceType: String = "ai_assisted",
        manualSetup: QuestionGenerationSetup? = null,
    ): QuestionFinalizationResult {
        val selectedQuestions = questions.filter { it.selected }
        require(selectedQuestions.isNotEmpty()) { "Select at least one valid question." }
        val response = functions.getHttpsCallable("finalizeExamQuestions").call(
            mapOf(
                "instituteId" to instituteId,
                "generationOperationId" to generationOperationId,
                "operationId" to operationId,
                "questionType" to questionType,
                "sourceType" to sourceType,
                "manualSetup" to manualSetup?.let { setup ->
                    mapOf(
                        "examName" to setup.examName,
                        "totalMarks" to setup.totalMarks,
                        "durationMinutes" to setup.durationMinutes,
                        "className" to setup.className,
                        "subject" to setup.subject,
                        "chapter" to setup.chapter,
                        "language" to setup.language,
                    )
                },
                "questions" to selectedQuestions.map { question ->
                    mapOf(
                        "sourceQuestionId" to question.sourceQuestionId,
                        "questionText" to question.questionText.trim(),
                        "options" to question.options.map(String::trim),
                        "correctAnswer" to question.correctAnswer.trim(),
                        "explanation" to question.explanation.trim(),
                        "difficulty" to question.difficulty,
                        "marks" to question.marks,
                        "imageReference" to question.imageReference,
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
            quotedCostPoisha = (body["quotedCostPoisha"] as? Number)?.toInt()
                ?: (body["costPoisha"] as? Number)?.toInt() ?: 0,
            chargedCostPoisha = (body["chargedCostPoisha"] as? Number)?.toInt()
                ?: (body["costPoisha"] as? Number)?.toInt() ?: 0,
            remainingBalancePoisha = (body["remainingBalancePoisha"] as? Number)?.toInt() ?: 0,
        )
    }
}
