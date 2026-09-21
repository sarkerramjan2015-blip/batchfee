package com.batchfee.edu.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GeneratedQuestionPreview(
    val sourceQuestionId: String,
    val questionText: String,
    val options: List<String>,
    val correctAnswer: String,
    val explanation: String,
    val difficulty: String,
    val marks: Int,
)

data class QuestionGenerationPreview(
    val operationId: String,
    val questions: List<GeneratedQuestionPreview>,
    val model: String,
    val billingMode: String = "manual",
    val attemptNumber: Int = 0,
    val freeAttemptsRemaining: Int = 0,
    val maximumCostPoisha: Int = 0,
)

data class QuestionGenerationSetup(
    val examName: String,
    val totalMarks: Int,
    val durationMinutes: Int,
    val className: String,
    val subject: String,
    val chapter: String,
    val questionType: String,
    val questionCount: Int,
    val language: String = "bn",
    val shortQuestionMarks: Int = 2,
    val chapterName: String = "",
    val topic: String = "",
    val patternKey: String = "standard",
    val patternVariant: String = "",
    val questionLevel: String = "balanced",
)

/** Server-authorized AI generation. No client Storage permission or AI credential is needed. */
class QuestionGenerationRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1"),
) {
    /** Server-rendered ready-made prompt for the Create Questions chat box. No AI cost. */
    suspend fun renderPromptPreview(
        instituteId: String,
        setup: QuestionGenerationSetup,
    ): String {
        val response = functions.getHttpsCallable("generateExamQuestions")
            .withTimeout(20, TimeUnit.SECONDS)
            .call(mapOf(
                "op" to "preview_prompt",
                "instituteId" to instituteId,
                "examName" to setup.examName,
                "totalMarks" to setup.totalMarks,
                "durationMinutes" to setup.durationMinutes,
                "className" to setup.className,
                "subject" to setup.subject,
                "chapter" to setup.chapter,
                "chapterName" to setup.chapterName,
                "topic" to setup.topic,
                "patternKey" to setup.patternKey,
                "patternVariant" to setup.patternVariant,
                "questionType" to setup.questionType,
                "questionCount" to setup.questionCount,
                "language" to setup.language,
                "shortQuestionMarks" to setup.shortQuestionMarks,
                "questionLevel" to setup.questionLevel,
            )).await()
        val body = response.data as? Map<*, *> ?: error("Invalid prompt preview response.")
        return body["prompt"] as? String ?: error("Missing prompt preview.")
    }

    suspend fun generate(
        context: Context,
        instituteId: String,
        setup: QuestionGenerationSetup,
        pageUris: List<String>,
        operationId: String = UUID.randomUUID().toString(),
        promptText: String? = null,
    ): QuestionGenerationPreview {
        require(pageUris.size in 1..2) { "Scan one or two pages first." }
        val sourcePages = withContext(Dispatchers.IO) {
            var total = 0
            pageUris.map { value ->
                val bytes = readBoundedJpeg(context, Uri.parse(value))
                total += bytes.size
                require(total <= MAX_TOTAL_BYTES) { "Combined scans must be under 10 MB." }
                mapOf("mimeType" to "image/jpeg", "dataBase64" to Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
        val response = functions.getHttpsCallable("generateExamQuestions")
            .withTimeout(310, TimeUnit.SECONDS)
            .call(mapOf(
                "instituteId" to instituteId,
                "operationId" to operationId,
                "examName" to setup.examName,
                "totalMarks" to setup.totalMarks,
                "durationMinutes" to setup.durationMinutes,
                "className" to setup.className,
                "subject" to setup.subject,
                "chapter" to setup.chapter,
                "chapterName" to setup.chapterName,
                "topic" to setup.topic,
                "patternKey" to setup.patternKey,
                "patternVariant" to setup.patternVariant,
                "questionType" to setup.questionType,
                "questionCount" to setup.questionCount,
                "language" to setup.language,
                "shortQuestionMarks" to setup.shortQuestionMarks,
                "questionLevel" to setup.questionLevel,
                "promptText" to promptText?.trim()?.takeIf { it.isNotBlank() },
                "sourcePages" to sourcePages,
            )).await()
        val body = response.data as? Map<*, *> ?: error("Invalid AI generation response.")
        val billing = body["billing"] as? Map<*, *> ?: emptyMap<String, Any>()
        val questions = (body["questions"] as? List<*>)?.mapIndexed { index, raw ->
            val item = raw as? Map<*, *> ?: error("Invalid question in response.")
            GeneratedQuestionPreview(
                sourceQuestionId = item["id"] as? String ?: "generated_${(index + 1).toString().padStart(2, '0')}",
                questionText = item["questionText"] as? String ?: error("Missing question text."),
                options = (item["options"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
                correctAnswer = item["correctAnswer"] as? String ?: error("Missing model answer."),
                explanation = item["explanation"] as? String ?: "",
                difficulty = item["difficulty"] as? String ?: "medium",
                marks = (item["marks"] as? Number)?.toInt() ?: 1,
            )
        } ?: error("No questions returned.")
        return QuestionGenerationPreview(
            operationId = body["operationId"] as? String ?: operationId,
            questions = questions,
            model = body["model"] as? String ?: "AI",
            billingMode = billing["mode"] as? String ?: "wallet",
            attemptNumber = (billing["attemptNumber"] as? Number)?.toInt() ?: 0,
            freeAttemptsRemaining = (billing["freeAttemptsRemaining"] as? Number)?.toInt() ?: 0,
            maximumCostPoisha = (billing["maximumCostPoisha"] as? Number)?.toInt() ?: 0,
        )
    }

    private fun readBoundedJpeg(context: Context, uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri) ?: error("Cannot open scanned page.")
        val output = ByteArrayOutputStream()
        stream.use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(output.size() + count <= MAX_PAGE_BYTES) { "Each scanned page must be under 6 MB." }
                output.write(chunk, 0, count)
            }
        }
        val bytes = output.toByteArray()
        require(bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes[bytes.lastIndex] == 0xd9.toByte()
        ) { "Scanner returned an invalid JPEG. Scan again." }
        return bytes
    }

    private companion object {
        const val MAX_PAGE_BYTES = 6 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 10 * 1024 * 1024
    }
}
