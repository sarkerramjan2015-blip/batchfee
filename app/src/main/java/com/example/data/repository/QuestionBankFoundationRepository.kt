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

    private suspend fun call(values: Map<String, Any>): QuestionBankFoundation {
        val response = functions.getHttpsCallable("questionBankFoundation").call(values).await()
        val body = response.data as? Map<*, *> ?: error("Invalid question bank response.")
        val aiTerms = body["aiTerms"] as? Map<*, *> ?: error("Missing AI terms.")
        val taxonomy = body["taxonomy"] as? Map<*, *> ?: error("Missing question taxonomy.")
        val billing = body["aiBilling"] as? Map<*, *> ?: error("Missing AI billing policy.")
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
        )
    }
}
