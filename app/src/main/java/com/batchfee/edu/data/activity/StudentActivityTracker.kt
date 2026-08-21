package com.batchfee.edu.data.activity

import com.batchfee.edu.data.repository.StudentAccountRepository
import com.batchfee.edu.domain.StudentSessionManager
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Lightweight, best-effort activity tracking for the student app. The backend
 * derives identity and server time from the verified Firebase token, so this
 * class only sends an approved event name and never blocks student UI work.
 */
object StudentActivityTracker {
    private const val HEARTBEAT_INTERVAL_MS = 2 * 60 * 1000L
    private const val EVENT_DEBOUNCE_MS = 8 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val functions = FirebaseFunctions.getInstance(StudentAccountRepository.FUNCTIONS_REGION)
    private val lock = Any()
    private var lastHeartbeatAtMs = 0L
    private var lastEventType: String? = null
    private var lastEventAtMs = 0L

    fun recordScreen(eventType: String) = record(eventType, isHeartbeat = false)

    fun heartbeat() = record("heartbeat", isHeartbeat = true)

    private fun record(eventType: String, isHeartbeat: Boolean) {
        if (!StudentSessionManager.isLoggedIn()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (isHeartbeat) {
                if (now - lastHeartbeatAtMs < HEARTBEAT_INTERVAL_MS) return
                lastHeartbeatAtMs = now
            } else {
                if (lastEventType == eventType && now - lastEventAtMs < EVENT_DEBOUNCE_MS) return
                lastEventType = eventType
                lastEventAtMs = now
            }
        }
        scope.launch {
            // Activity logging must never make a student screen fail or wait.
            runCatching {
                functions.getHttpsCallable("recordStudentActivity")
                    .call(mapOf("eventType" to eventType))
                    .await()
            }
        }
    }
}
