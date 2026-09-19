package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class QuestionBankFoundation(
    val policyVersion: String,
    val contributionEnabled: Boolean,
    val terms: List<String>,
    val questionTypes: List<String>,
    val aiBillingEnabled: Boolean,
)

/** Phase 0 policy transport. All authorization and preference writes are server-side. */
class QuestionBankFoundationRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1")
) {
    suspend fun load(instituteId: String): QuestionBankFoundation =
        call(mapOf("action" to "get_foundation", "instituteId" to instituteId))

    suspend fun setContributionPreference(
        instituteId: String,
        policyVersion: String,
        enabled: Boolean,
        confirmedRights: Boolean,
    ): QuestionBankFoundation = call(
        mapOf(
            "action" to "set_contribution_preference",
            "instituteId" to instituteId,
            "operationId" to UUID.randomUUID().toString(),
            "policyVersion" to policyVersion,
            "enabled" to enabled,
            "confirmedRights" to confirmedRights,
        )
    )

    private suspend fun call(values: Map<String, Any>): QuestionBankFoundation {
        val response = functions.getHttpsCallable("questionBankFoundation").call(values).await()
        val body = response.data as? Map<*, *> ?: error("Invalid question bank response.")
        val contribution = body["contribution"] as? Map<*, *> ?: error("Missing contribution policy.")
        val taxonomy = body["taxonomy"] as? Map<*, *> ?: error("Missing question taxonomy.")
        val billing = body["aiBilling"] as? Map<*, *> ?: error("Missing AI billing policy.")
        return QuestionBankFoundation(
            policyVersion = contribution["policyVersion"] as? String ?: error("Missing policy version."),
            contributionEnabled = contribution["enabled"] as? Boolean ?: false,
            terms = (contribution["terms"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            questionTypes = (taxonomy["questionTypes"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            aiBillingEnabled = billing["enabled"] as? Boolean ?: false,
        )
    }
}
