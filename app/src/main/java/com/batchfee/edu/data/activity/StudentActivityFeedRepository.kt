package com.batchfee.edu.data.activity

import com.batchfee.edu.data.repository.StudentAccountRepository
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class StudentActivityEvent(
    val id: String,
    val studentId: String,
    val batchIds: List<String>,
    val eventType: String,
    val label: String,
    val occurredAtMs: Long,
)

data class StudentPresence(
    val studentId: String,
    val batchIds: List<String>,
    val lastSeenAtMs: Long,
    val lastActivityType: String?,
    val lastActivityLabel: String?,
    val lastActivityAtMs: Long?,
)

data class StudentActivityFeed(
    val events: List<StudentActivityEvent>,
    val presence: List<StudentPresence>,
)

/** Owner-only activity feed served by the trusted backend. */
class StudentActivityFeedRepository {
    private val functions = FirebaseFunctions.getInstance(StudentAccountRepository.FUNCTIONS_REGION)

    suspend fun load(instituteId: String): StudentActivityFeed = withContext(Dispatchers.IO) {
        val result = functions.getHttpsCallable("getStudentActivity")
            .call(mapOf("instituteId" to instituteId))
            .await()
        val data = result.data as? Map<*, *> ?: return@withContext StudentActivityFeed(emptyList(), emptyList())
        StudentActivityFeed(
            events = (data["events"] as? List<*>).orEmpty().mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val studentId = map.string("studentId") ?: return@mapNotNull null
                val timestamp = (map["occurredAtMs"] as? Number)?.toLong() ?: return@mapNotNull null
                StudentActivityEvent(
                    id = map.string("id") ?: "$studentId-$timestamp",
                    studentId = studentId,
                    batchIds = map.stringList("batchIds"),
                    eventType = map.string("eventType").orEmpty(),
                    label = map.string("label").orEmpty().ifBlank { "Used student app" },
                    occurredAtMs = timestamp,
                )
            },
            presence = (data["presence"] as? List<*>).orEmpty().mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val studentId = map.string("studentId") ?: return@mapNotNull null
                val lastSeen = (map["lastSeenAtMs"] as? Number)?.toLong() ?: return@mapNotNull null
                StudentPresence(
                    studentId = studentId,
                    batchIds = map.stringList("batchIds"),
                    lastSeenAtMs = lastSeen,
                    lastActivityType = map.string("lastActivityType"),
                    lastActivityLabel = map.string("lastActivityLabel"),
                    lastActivityAtMs = (map["lastActivityAtMs"] as? Number)?.toLong(),
                )
            },
        )
    }

    private fun Map<*, *>.string(key: String): String? = this[key] as? String
    private fun Map<*, *>.stringList(key: String): List<String> =
        (this[key] as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() } ?: emptyList()
}
