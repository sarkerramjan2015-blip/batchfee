package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SafeDeletionResult(
    val operationId: String,
    val instituteId: String,
    val entityType: String,
    val entityId: String,
    val action: String,
    val status: String,
    val archivedAtMs: Long?,
    val retentionUntilMs: Long?,
    val isAppAccessEnabled: Boolean?,
    val subscriptionStatus: String?,
    val authCleanupState: String,
    val mediaCleanupState: String,
    val hardDeleteAllowed: Boolean
)

class SafeDeletionRejectedException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class SafeDeletionPendingException(
    val operationId: String,
    cause: Throwable? = null
) : Exception(
    "Deletion operation is secured and pending reconciliation (ID: $operationId). Refresh online; do not submit another permanent deletion.",
    cause
)

interface SafeDeletionGateway {
    suspend fun commit(request: Map<String, Any?>): SafeDeletionResult
}

class FirebaseSafeDeletionGateway : SafeDeletionGateway {
    private val functions = FirebaseFunctions.getInstance("asia-south1")

    override suspend fun commit(request: Map<String, Any?>): SafeDeletionResult =
        withContext(Dispatchers.IO) {
            try {
                val response = functions.getHttpsCallable("commitSafeDeletion").call(request).await()
                @Suppress("UNCHECKED_CAST")
                parseSafeDeletionResult(response.data as? Map<String, Any?>
                    ?: error("Invalid safe-deletion response."))
            } catch (error: FirebaseFunctionsException) {
                when (error.code) {
                    FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                    FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                    FirebaseFunctionsException.Code.ALREADY_EXISTS,
                    FirebaseFunctionsException.Code.NOT_FOUND,
                    FirebaseFunctionsException.Code.PERMISSION_DENIED,
                    FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                        throw SafeDeletionRejectedException(
                            error.message ?: "Deletion operation was rejected.",
                            error
                        )
                    else -> throw error
                }
            }
        }
}

internal object DeletionRequestCodec {
    fun encode(request: Map<String, Any?>): String = JSONObject(request).toString()
    fun decode(json: String): Map<String, Any?> = JSONObject(json).toMap()

    private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { unwrap(get(it)) }
    private fun JSONArray.toListValue(): List<Any?> = (0 until length()).map { unwrap(get(it)) }
    private fun unwrap(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> value.toMap()
        is JSONArray -> value.toListValue()
        else -> value
    }
}

private fun parseSafeDeletionResult(data: Map<String, Any?>) = SafeDeletionResult(
    operationId = data.requiredString("operationId"),
    instituteId = data.requiredString("instituteId"),
    entityType = data.requiredString("entityType"),
    entityId = data.requiredString("entityId"),
    action = data.requiredString("action"),
    status = data.requiredString("status"),
    archivedAtMs = (data["archivedAtMs"] as? Number)?.toLong(),
    retentionUntilMs = (data["retentionUntilMs"] as? Number)?.toLong(),
    isAppAccessEnabled = data["isAppAccessEnabled"] as? Boolean,
    subscriptionStatus = data["subscriptionStatus"] as? String,
    authCleanupState = data.requiredString("authCleanupState"),
    mediaCleanupState = data.requiredString("mediaCleanupState"),
    hardDeleteAllowed = data["hardDeleteAllowed"] as? Boolean ?: false
)

private fun Map<String, Any?>.requiredString(key: String): String =
    this[key] as? String ?: error("Missing $key in safe-deletion response.")
