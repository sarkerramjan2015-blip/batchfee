package com.example.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import com.batchfee.edu.data.database.AppDatabase

/**
 * Drives a queued, one-by-one bulk SMS/WhatsApp send flow.
 *
 * Android cannot confirm real delivery for intents, so "SENT" means the
 * external compose screen was opened and the admin returned to the app.
 * The screen must forward lifecycle onPause/onResume into this controller.
 */
class BulkMessageController(
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val instituteId: String?
) {
    data class BulkTarget(val key: String, val name: String, val phone: String?)

    enum class Status { PENDING, SENT, FAILED, DUPLICATE, NO_PHONE, CANCELLED }
    enum class Phase { IDLE, RUNNING, AWAITING_RESUME, COMPLETED }

    data class BulkQueueItem(
        val target: BulkTarget,
        val status: Status = Status.PENDING,
        val lastError: String? = null
    )

    data class BulkQueueState(
        val items: List<BulkQueueItem> = emptyList(),
        val phase: Phase = Phase.IDLE
    ) {
        val sentCount: Int get() = items.count { it.status == Status.SENT }
        val totalCount: Int get() = items.size
        val failedCount: Int get() = items.count { it.status == Status.FAILED }
        val processedCount: Int get() = items.count { it.status != Status.PENDING }
        val active: Boolean get() = phase != Phase.IDLE
    }

    private val _state = MutableStateFlow(BulkQueueState())
    val state: StateFlow<BulkQueueState> = _state.asStateFlow()

    private var queueJob: Job? = null
    private var resumed = false
    private var pausedSinceLaunch = false
    private var lastLaunchMs = 0L
    private var delayMs = 3000L
    private var channel = ""
    private var messageBuilder: (BulkTarget) -> String = { "" }
    private var launcher: (BulkTarget, String) -> Boolean = { _, _ -> false }

    val isRunning: Boolean get() = queueJob?.isActive == true

    fun start(
        targets: List<BulkTarget>,
        channel: String,
        delayMs: Long,
        messageBuilder: (BulkTarget) -> String,
        launcher: (BulkTarget, String) -> Boolean
    ): Boolean {
        if (isRunning || targets.isEmpty()) return false
        this.channel = channel
        this.delayMs = delayMs.coerceAtLeast(0L)
        this.messageBuilder = messageBuilder
        this.launcher = launcher
        this.pausedSinceLaunch = false
        val items = targets.map { BulkQueueItem(it) }
        _state.value = BulkQueueState(items = items, phase = Phase.RUNNING)
        queueJob = scope.launch { process(items) }
        return true
    }

    private suspend fun process(items: List<BulkQueueItem>) {
        var i = 0
        while (i < items.size) {
            currentCoroutineContext().ensureActive()
            val item = items[i]
            if (item.status != Status.PENDING) { i++; continue }

            val digits = item.target.phone?.filter(Char::isDigit).orEmpty()
            if (digits.isBlank()) {
                update(items, i, Status.NO_PHONE, "No phone number")
                i++
                continue
            }

            val message = messageBuilder(item.target)
            if (message.isBlank()) {
                update(items, i, Status.FAILED, "Message is empty")
                i++
                continue
            }

            val instId = instituteId.orEmpty()
            val alreadySent = try {
                db.bulkMessageLogDao()
                    .hasSent(instId, item.target.key, channel, message) > 0
            } catch (_: Exception) { false }
            if (alreadySent) {
                update(items, i, Status.DUPLICATE, "Already sent")
                i++
                continue
            }

            pausedSinceLaunch = false
            lastLaunchMs = System.currentTimeMillis()
            _state.update { it.copy(phase = Phase.AWAITING_RESUME) }

            val launched = try {
                launcher(item.target, message)
            } catch (_: Exception) { false }

            if (!launched) {
                update(items, i, Status.FAILED, "No app found to send this message")
                log(item.target.key, message, "failed")
                _state.update { it.copy(phase = Phase.RUNNING) }
                i++
                continue
            }

            awaitResume()

            update(items, i, Status.SENT)
            log(item.target.key, message, "sent")
            _state.update { it.copy(phase = Phase.RUNNING) }

            if (i < items.size - 1 && delayMs > 0) delay(delayMs)
            i++
        }
        _state.update { it.copy(phase = Phase.COMPLETED) }
    }

    private suspend fun awaitResume() {
        resumed = false
        while (!resumed) {
            currentCoroutineContext().ensureActive()
            delay(50)
        }
    }

    /** Lifecycle ON_PAUSE — only a real pause enables the next resume to confirm a send. */
    fun onPaused() {
        if (_state.value.phase == Phase.AWAITING_RESUME) pausedSinceLaunch = true
    }

    /** Lifecycle ON_RESUME — confirms the admin returned from the external compose screen. */
    fun onResumed() {
        if (_state.value.phase != Phase.AWAITING_RESUME) return
        if (!pausedSinceLaunch) return
        if (System.currentTimeMillis() - lastLaunchMs < 300) return
        resumed = true
    }

    /** Stops the queue; remaining pending items become CANCELLED. */
    fun cancel() {
        queueJob?.cancel()
        queueJob = null
        val current = _state.value
        _state.value = BulkQueueState(
            items = current.items.map {
                if (it.status == Status.PENDING) it.copy(status = Status.CANCELLED) else it
            },
            phase = Phase.COMPLETED
        )
    }

    /** Requeues failed items. Returns false when there is nothing to retry. */
    fun retryFailed(): Boolean {
        if (isRunning) return false
        val current = _state.value
        val items = current.items.map {
            if (it.status == Status.FAILED) it.copy(status = Status.PENDING, lastError = null) else it
        }
        if (items.none { it.status == Status.PENDING }) return false
        pausedSinceLaunch = false
        _state.value = BulkQueueState(items = items, phase = Phase.RUNNING)
        queueJob = scope.launch { process(items) }
        return true
    }

    /** Clears the panel state when the queue is no longer running. */
    fun reset() {
        if (isRunning) return
        _state.value = BulkQueueState()
        queueJob = null
    }

    private fun update(items: List<BulkQueueItem>, index: Int, status: Status, error: String? = null) {
        val next = items.toMutableList()
        next[index] = next[index].copy(status = status, lastError = error)
        _state.update { it.copy(items = next) }
    }

    private suspend fun log(studentId: String, messageText: String, status: String) {
        try {
            db.bulkMessageLogDao().insert(
                com.batchfee.edu.data.models.BulkMessageLogEntity(
                    id = UUID.randomUUID().toString(),
                    instituteId = instituteId.orEmpty(),
                    studentId = studentId,
                    channel = channel,
                    messageText = messageText,
                    status = status,
                    createdAtMs = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) { }
    }
}
