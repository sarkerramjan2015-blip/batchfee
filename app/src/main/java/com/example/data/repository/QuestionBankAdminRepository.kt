package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class QuestionBankAdminSettings(
    val generationEnabled: Boolean = true,
    val contributionEnabled: Boolean = true,
    val actorDailyPreviewLimit: Int = 5,
    val instituteDailyPreviewLimit: Int = 25,
    val platformDailyPreviewLimit: Int = 100,
    val maxQuestionsPerRequest: Int = 30,
)

data class AdminBankQuestion(
    val id: String,
    val status: String,
    val className: String,
    val subject: String,
    val chapter: String,
    val chapterName: String,
    val questionText: String,
    val type: String,
    val difficulty: String,
    val marks: Int,
)

data class QuestionWalletCreditResult(
    val instituteId: String,
    val amountPoisha: Int,
    val balancePoisha: Int,
)

data class QuestionTopupDecision(
    val requestId: String,
    val instituteId: String,
    val amountPoisha: Int,
    val status: String,
    val balancePoisha: Int,
)

data class PendingQuestionTopup(
    val requestId: String,
    val instituteId: String,
    val amountPoisha: Int,
    val feePoisha: Int,
    val payablePoisha: Int,
    val requestedAtMs: Long,
    val paymentMethod: String = "bkash",
    val senderNumber: String = "",
)

data class QuestionRevenueSummary(
    val totalQuestionChargesPoisha: Int = 0,
    val totalTopupFeePoisha: Int = 0,
    val totalTopupCreditPoisha: Int = 0,
    val topupCount: Int = 0,
    val chargeCount: Int = 0,
)

/** Root-only callable transport for global question-bank operational controls. */
class QuestionBankAdminRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1"),
) {
    suspend fun settings(): QuestionBankAdminSettings {
        val body = call(mapOf("action" to "get_settings"))
        return settingsFrom(body["settings"] as? Map<*, *> ?: emptyMap<String, Any>())
    }

    suspend fun updateSettings(settings: QuestionBankAdminSettings): QuestionBankAdminSettings {
        val body = call(
            mapOf(
                "action" to "update_settings",
                "operationId" to UUID.randomUUID().toString(),
                "settings" to settings.toMap(),
            ),
        )
        return settingsFrom(body["settings"] as? Map<*, *> ?: error("Missing saved settings."))
    }

    suspend fun questions(status: String): List<AdminBankQuestion> {
        val body = call(mapOf("action" to "list_questions", "status" to status, "limit" to 50))
        return (body["questions"] as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { value ->
                val id = value["id"] as? String ?: return@mapNotNull null
                val question = value["questionText"] as? String ?: return@mapNotNull null
                AdminBankQuestion(
                    id = id,
                    status = value["status"] as? String ?: status,
                    className = value["className"] as? String ?: "",
                    subject = value["subject"] as? String ?: "",
                    chapter = value["chapter"] as? String ?: "",
                    chapterName = value["chapterName"] as? String ?: "",
                    questionText = question,
                    type = value["type"] as? String ?: "",
                    difficulty = value["difficulty"] as? String ?: "",
                    marks = (value["marks"] as? Number)?.toInt() ?: 0,
                )
            }
    }

    suspend fun changeQuestionStatus(questionId: String, action: String) {
        require(action == "retire_question" || action == "restore_question")
        call(
            mapOf(
                "action" to action,
                "questionId" to questionId,
                "operationId" to UUID.randomUUID().toString(),
            ),
        )
    }

    suspend fun pendingTopups(): List<PendingQuestionTopup> {
        val body = call(mapOf("action" to "list_pending_topups"))
        return (body["requests"] as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .map { value ->
                PendingQuestionTopup(
                    requestId = value["requestId"] as? String ?: "",
                    instituteId = value["instituteId"] as? String ?: "",
                    amountPoisha = (value["amountPoisha"] as? Number)?.toInt() ?: 0,
                    feePoisha = (value["feePoisha"] as? Number)?.toInt() ?: 0,
                    payablePoisha = (value["payablePoisha"] as? Number)?.toInt() ?: 0,
                    requestedAtMs = (value["requestedAtMs"] as? Number)?.toLong() ?: 0L,
                    paymentMethod = value["paymentMethod"] as? String ?: "bkash",
                    senderNumber = value["senderNumber"] as? String ?: "",
                )
            }
    }

    suspend fun revenueSummary(): QuestionRevenueSummary {
        val body = call(mapOf("action" to "get_revenue_summary"))
        return QuestionRevenueSummary(
            totalQuestionChargesPoisha = (body["totalQuestionChargesPoisha"] as? Number)?.toInt() ?: 0,
            totalTopupFeePoisha = (body["totalTopupFeePoisha"] as? Number)?.toInt() ?: 0,
            totalTopupCreditPoisha = (body["totalTopupCreditPoisha"] as? Number)?.toInt() ?: 0,
            topupCount = (body["topupCount"] as? Number)?.toInt() ?: 0,
            chargeCount = (body["chargeCount"] as? Number)?.toInt() ?: 0,
        )
    }

    suspend fun decideTopup(
        instituteId: String,
        requestId: String,
        approve: Boolean,
    ): QuestionTopupDecision {
        require(instituteId.isNotBlank() && requestId.isNotBlank()) { "Invalid top-up request." }
        val body = call(
            mapOf(
                "action" to if (approve) "approve_topup" else "reject_topup",
                "operationId" to UUID.randomUUID().toString(),
                "instituteId" to instituteId,
                "requestId" to requestId,
            ),
        )
        return QuestionTopupDecision(
            requestId = body["requestId"] as? String ?: requestId,
            instituteId = body["instituteId"] as? String ?: instituteId,
            amountPoisha = (body["amountPoisha"] as? Number)?.toInt() ?: 0,
            status = body["status"] as? String ?: "",
            balancePoisha = (body["balancePoisha"] as? Number)?.toInt() ?: 0,
        )
    }

    suspend fun creditInstituteWallet(
        instituteId: String,
        amountPoisha: Int,
        reason: String,
    ): QuestionWalletCreditResult {
        require(instituteId.isNotBlank()) { "Institute ID is required." }
        require(amountPoisha > 0) { "Credit amount must be greater than zero." }
        val body = call(
            mapOf(
                "action" to "credit_institute_wallet",
                "operationId" to UUID.randomUUID().toString(),
                "instituteId" to instituteId.trim(),
                "amountPoisha" to amountPoisha,
                "reason" to reason.trim().ifBlank { "Question wallet top-up" },
            ),
        )
        return QuestionWalletCreditResult(
            instituteId = body["instituteId"] as? String ?: instituteId.trim(),
            amountPoisha = (body["amountPoisha"] as? Number)?.toInt() ?: amountPoisha,
            balancePoisha = (body["balancePoisha"] as? Number)?.toInt()
                ?: error("Missing wallet balance."),
        )
    }

    private fun QuestionBankAdminSettings.toMap(): Map<String, Any> = mapOf(
        "generationEnabled" to generationEnabled,
        "contributionEnabled" to contributionEnabled,
        "actorDailyPreviewLimit" to actorDailyPreviewLimit,
        "instituteDailyPreviewLimit" to instituteDailyPreviewLimit,
        "platformDailyPreviewLimit" to platformDailyPreviewLimit,
        "maxQuestionsPerRequest" to maxQuestionsPerRequest,
    )

    private fun settingsFrom(value: Map<*, *>): QuestionBankAdminSettings = QuestionBankAdminSettings(
        generationEnabled = value["generationEnabled"] as? Boolean ?: true,
        contributionEnabled = value["contributionEnabled"] as? Boolean ?: true,
        actorDailyPreviewLimit = (value["actorDailyPreviewLimit"] as? Number)?.toInt() ?: 5,
        instituteDailyPreviewLimit = (value["instituteDailyPreviewLimit"] as? Number)?.toInt() ?: 25,
        platformDailyPreviewLimit = (value["platformDailyPreviewLimit"] as? Number)?.toInt() ?: 100,
        maxQuestionsPerRequest = (value["maxQuestionsPerRequest"] as? Number)?.toInt() ?: 30,
    )

    private suspend fun call(values: Map<String, Any>): Map<*, *> {
        val response = functions.getHttpsCallable("commitQuestionBankAdminOperation").call(values).await()
        return response.data as? Map<*, *> ?: error("Invalid question-bank admin response.")
    }
}
