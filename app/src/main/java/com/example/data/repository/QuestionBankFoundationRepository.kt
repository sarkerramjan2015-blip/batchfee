package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class QuestionBankFoundation(
    val policyVersion: String,
    val aiTncAccepted: Boolean,
    val terms: List<String>,
    val questionTypes: List<String>,
    val automaticAnonymousSync: Boolean,
    val aiBillingEnabled: Boolean,
    val walletBalancePoisha: Int,
    val freeLifetimeAttemptLimit: Int,
    val freeAttemptsUsed: Int,
    val freeAttemptsRemaining: Int,
    val ratesPoisha: Map<String, Int>,
    val manualRatePoisha: Int = 100,
    val topupMinAmountPoisha: Int = 5_000,
    val topupMaxAmountPoisha: Int = 100_000_000,
    val topupProcessingFeePercent: Double = 1.8,
    val pendingTopup: QuestionTopupRequest? = null,
)

data class QuestionTopupRequest(
    val status: String,
    val amountPoisha: Int,
    val feePoisha: Int,
    val payablePoisha: Int,
    val requestedAtMs: Long,
    val paymentMethod: String = "bkash",
    val senderNumber: String = "",
)

data class PreviousQuestion(
    val id: String,
    val type: String,
    val questionText: String,
    val options: List<String>,
    val correctAnswer: String,
    val explanation: String,
    val difficulty: String,
    val marks: Int,
    val className: String,
    val subject: String,
    val chapter: String,
    val chapterName: String,
    val topic: String,
    val examName: String,
    val finalizedAtMs: Long,
)

data class PreviousQuestionPage(
    val questions: List<PreviousQuestion>,
    val page: Int,
    val hasMore: Boolean,
)

/** Phase 0 policy transport. All authorization and preference writes are server-side. */
class QuestionBankFoundationRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1")
) {
    suspend fun load(instituteId: String): QuestionBankFoundation =
        call(mapOf("action" to "get_foundation", "instituteId" to instituteId))

    suspend fun acceptAiTerms(
        instituteId: String,
        policyVersion: String,
    ): QuestionBankFoundation = call(
        mapOf(
            "action" to "accept_ai_tnc",
            "instituteId" to instituteId,
            "operationId" to UUID.randomUUID().toString(),
            "policyVersion" to policyVersion,
            "confirmedRights" to true,
        )
    )

    suspend fun listPreviousQuestions(
        instituteId: String,
        page: Int,
        limit: Int = 25,
    ): PreviousQuestionPage {
        require(page >= 0) { "Invalid page." }
        require(limit in 1..50) { "Invalid page size." }
        val response = functions.getHttpsCallable("questionBankFoundation").call(
            mapOf(
                "action" to "list_previous_questions",
                "instituteId" to instituteId,
                "page" to page,
                "limit" to limit,
            )
        ).await()
        val body = response.data as? Map<*, *> ?: error("Invalid previous questions response.")
        val questions = (body["questions"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            .mapNotNull { value ->
                val id = value["id"] as? String ?: return@mapNotNull null
                val text = value["questionText"] as? String ?: return@mapNotNull null
                PreviousQuestion(
                    id = id,
                    type = value["type"] as? String ?: "",
                    questionText = text,
                    options = (value["options"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
                    correctAnswer = value["correctAnswer"] as? String ?: "",
                    explanation = value["explanation"] as? String ?: "",
                    difficulty = value["difficulty"] as? String ?: "medium",
                    marks = (value["marks"] as? Number)?.toInt() ?: 1,
                    className = value["className"] as? String ?: "",
                    subject = value["subject"] as? String ?: "",
                    chapter = value["chapter"] as? String ?: "",
                    chapterName = value["chapterName"] as? String ?: "",
                    topic = value["topic"] as? String ?: "",
                    examName = value["examName"] as? String ?: "",
                    finalizedAtMs = (value["finalizedAtMs"] as? Number)?.toLong() ?: 0L,
                )
            }
        return PreviousQuestionPage(
            questions = questions,
            page = (body["page"] as? Number)?.toInt() ?: page,
            hasMore = body["hasMore"] as? Boolean ?: false,
        )
    }

    suspend fun requestTopup(
        instituteId: String,
        amountPoisha: Int,
        paymentMethod: String,
        senderNumber: String,
    ): QuestionTopupRequest? {
        require(amountPoisha >= 5_000) { "Top-up amount must be at least BDT 50." }
        require(paymentMethod == "bkash" || paymentMethod == "nagad") { "Choose bKash or Nagad." }
        require(senderNumber.isNotBlank()) { "Enter the number you sent the money from." }
        val response = functions.getHttpsCallable("questionBankFoundation").call(
            mapOf(
                "action" to "request_topup",
                "instituteId" to instituteId,
                "operationId" to UUID.randomUUID().toString(),
                "amountPoisha" to amountPoisha,
                "paymentMethod" to paymentMethod,
                "senderNumber" to senderNumber,
            )
        ).await()
        val body = response.data as? Map<*, *> ?: error("Invalid top-up response.")
        val policy = body["topupPolicy"] as? Map<*, *> ?: emptyMap<String, Any>()
        val pending = policy["pendingRequest"] as? Map<*, *> ?: return null
        return QuestionTopupRequest(
            status = pending["status"] as? String ?: "pending",
            amountPoisha = (pending["amountPoisha"] as? Number)?.toInt() ?: amountPoisha,
            feePoisha = (pending["feePoisha"] as? Number)?.toInt() ?: 0,
            payablePoisha = (pending["payablePoisha"] as? Number)?.toInt() ?: amountPoisha,
            requestedAtMs = (pending["requestedAtMs"] as? Number)?.toLong() ?: 0L,
            paymentMethod = pending["paymentMethod"] as? String ?: paymentMethod,
            senderNumber = pending["senderNumber"] as? String ?: senderNumber,
        )
    }

    private suspend fun call(values: Map<String, Any>): QuestionBankFoundation {
        val response = functions.getHttpsCallable("questionBankFoundation").call(values).await()
        val body = response.data as? Map<*, *> ?: error("Invalid question bank response.")
        val aiTerms = body["aiTerms"] as? Map<*, *> ?: error("Missing AI terms.")
        val taxonomy = body["taxonomy"] as? Map<*, *> ?: error("Missing question taxonomy.")
        val billing = body["aiBilling"] as? Map<*, *> ?: error("Missing AI billing policy.")
        val topup = body["topupPolicy"] as? Map<*, *>
        val pending = topup?.get("pendingRequest") as? Map<*, *>
        return QuestionBankFoundation(
            policyVersion = aiTerms["policyVersion"] as? String ?: error("Missing policy version."),
            aiTncAccepted = aiTerms["accepted"] as? Boolean ?: false,
            terms = (aiTerms["terms"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            questionTypes = (taxonomy["questionTypes"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            automaticAnonymousSync = aiTerms["automaticAnonymousSync"] as? Boolean ?: false,
            aiBillingEnabled = billing["enabled"] as? Boolean ?: false,
            walletBalancePoisha = (billing["balancePoisha"] as? Number)?.toInt() ?: 0,
            freeLifetimeAttemptLimit = (billing["freeLifetimeAttemptLimit"] as? Number)?.toInt() ?: 5,
            freeAttemptsUsed = (billing["freeAttemptsUsed"] as? Number)?.toInt() ?: 0,
            freeAttemptsRemaining = (billing["freeAttemptsRemaining"] as? Number)?.toInt() ?: 0,
            ratesPoisha = (billing["ratesPoisha"] as? Map<*, *>)
                ?.mapNotNull { (key, value) ->
                    val name = key as? String ?: return@mapNotNull null
                    val amount = (value as? Number)?.toInt() ?: return@mapNotNull null
                    name to amount
                }?.toMap().orEmpty(),
            manualRatePoisha = (billing["manualRatePoisha"] as? Number)?.toInt() ?: 100,
            topupMinAmountPoisha = (topup?.get("minAmountPoisha") as? Number)?.toInt() ?: 5_000,
            topupMaxAmountPoisha = (topup?.get("maxAmountPoisha") as? Number)?.toInt() ?: 100_000_000,
            topupProcessingFeePercent = (topup?.get("processingFeePercent") as? Number)?.toDouble() ?: 1.8,
            pendingTopup = pending?.let {
                QuestionTopupRequest(
                    status = it["status"] as? String ?: "pending",
                    amountPoisha = (it["amountPoisha"] as? Number)?.toInt() ?: 0,
                    feePoisha = (it["feePoisha"] as? Number)?.toInt() ?: 0,
                    payablePoisha = (it["payablePoisha"] as? Number)?.toInt() ?: 0,
                    requestedAtMs = (it["requestedAtMs"] as? Number)?.toLong() ?: 0L,
                    paymentMethod = it["paymentMethod"] as? String ?: "bkash",
                    senderNumber = it["senderNumber"] as? String ?: "",
                )
            },
        )
    }
}
