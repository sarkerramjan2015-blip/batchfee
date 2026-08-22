package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class InstituteOwnerLoginEvent(
    val id: String,
    val occurredAtMs: Long,
    val method: String
)

data class InstituteOwnerLoginActivity(
    val retentionDays: Int,
    val totalLoginCount: Int,
    val last30DaysCount: Int,
    val todayCount: Int,
    val lastLoginAtMs: Long,
    val events: List<InstituteOwnerLoginEvent>
)

class InstituteOwnerLoginActivityRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1")
) {
    suspend fun recordOwnerLogin(instituteId: String, method: String) {
        require(instituteId.isNotBlank()) { "Institute ID is required." }
        functions.getHttpsCallable("recordInstituteOwnerLogin")
            .call(
                mapOf(
                    "instituteId" to instituteId,
                    "sessionId" to UUID.randomUUID().toString(),
                    "method" to method
                )
            )
            .await()
    }

    suspend fun getOwnerLoginActivity(instituteId: String): InstituteOwnerLoginActivity {
        require(instituteId.isNotBlank()) { "Institute ID is required." }
        val response = functions.getHttpsCallable("getInstituteOwnerLoginActivity")
            .call(mapOf("instituteId" to instituteId))
            .await()
        @Suppress("UNCHECKED_CAST")
        val data = response.data as? Map<String, Any?>
            ?: error("Invalid login activity response.")
        @Suppress("UNCHECKED_CAST")
        val events = (data["events"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { event ->
            val occurredAtMs = (event["occurredAtMs"] as? Number)?.toLong() ?: return@mapNotNull null
            InstituteOwnerLoginEvent(
                id = event["id"] as? String ?: occurredAtMs.toString(),
                occurredAtMs = occurredAtMs,
                method = event["method"] as? String ?: "password"
            )
        }.sortedByDescending { it.occurredAtMs }
        return InstituteOwnerLoginActivity(
            retentionDays = (data["retentionDays"] as? Number)?.toInt() ?: 30,
            totalLoginCount = (data["totalLoginCount"] as? Number)?.toInt() ?: 0,
            last30DaysCount = (data["last30DaysCount"] as? Number)?.toInt() ?: events.size,
            todayCount = (data["todayCount"] as? Number)?.toInt() ?: 0,
            lastLoginAtMs = (data["lastLoginAtMs"] as? Number)?.toLong() ?: 0L,
            events = events
        )
    }
}

/**
 * Login navigation removes the Auth ViewModel immediately. This process-level,
 * supervised scope lets the best-effort metric finish without delaying or
 * failing the user's successful login.
 */
object InstituteOwnerLoginActivityTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = InstituteOwnerLoginActivityRepository()

    fun record(instituteId: String, method: String) {
        scope.launch {
            runCatching { repository.recordOwnerLogin(instituteId, method) }
                .onFailure { error ->
                    FirebaseCrashlytics.getInstance().log(
                        "Institute owner login activity was not recorded: ${error.javaClass.simpleName}"
                    )
                }
        }
    }
}
