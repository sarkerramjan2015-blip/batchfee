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
import com.batchfee.edu.data.firestore.ServerSmsOutbound
import com.batchfee.edu.data.firestore.SmsWalletState
import com.batchfee.edu.data.firestore.SmsWalletSyncHelper
import com.google.firebase.functions.FirebaseFunctionsException

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

    enum class Status { PENDING, SENT, QUEUED, FAILED, DUPLICATE, NO_PHONE, CANCELLED }
    enum class Phase { IDLE, RUNNING, AWAITING_RESUME, COMPLETED }

    data class BulkQueueItem(
        val target: BulkTarget,
        val status: Status = Status.PENDING,
        val lastError: String? = null
    )

    data class BulkQueueState(
        val items: List<BulkQueueItem> = emptyList(),
        val phase: Phase = Phase.IDLE,
        val serverManaged: Boolean = false
    ) {
        val sentCount: Int get() = items.count { it.status == Status.SENT }
        val queuedCount: Int get() = items.count { it.status == Status.QUEUED }
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
    private var serverOperationId = UUID.randomUUID().toString()
    private var smsMethodOverride: String? = null

    val isRunning: Boolean get() = queueJob?.isActive == true

    fun start(
        targets: List<BulkTarget>,
        channel: String,
        delayMs: Long,
        messageBuilder: (BulkTarget) -> String,
        launcher: (BulkTarget, String) -> Boolean,
        smsMethodOverride: String? = null
    ): Boolean {
        if (isRunning || targets.isEmpty()) return false
        this.channel = channel
        this.delayMs = delayMs.coerceAtLeast(0L)
        this.messageBuilder = messageBuilder
        this.launcher = launcher
        this.serverOperationId = UUID.randomUUID().toString()
        this.smsMethodOverride = smsMethodOverride
        this.pausedSinceLaunch = false
        val items = targets.map { BulkQueueItem(it) }
        _state.value = BulkQueueState(items = items, phase = Phase.RUNNING)
        queueJob = scope.launch { process(items) }
        return true
    }

    private suspend fun process(items: List<BulkQueueItem>) {
        // An explicit per-send choice from the SMS delivery chooser (for example
        // the owner's one-send "Phone SMS" fallback) wins over the institute's
        // saved method; otherwise the saved automatic preference applies.
        val useServerSms = channel == "sms" && runCatching {
            val instId = instituteId.orEmpty()
            val method = smsMethodOverride
                ?: SmsWalletSyncHelper.ensureWalletInitialized(instId).smsSendMethod
            instId.isNotBlank() && method == SmsWalletState.METHOD_SERVER
        }.getOrDefault(false)
        if (useServerSms) {
            processServer(items)
            return
        }
        var i = 0
        while (i < items.size) {
            currentCoroutineContext().ensureActive()
            val item = items[i]
            if (item.status != Status.PENDING) { i++; continue }

            val digits = item.target.phone?.filter(Char::isDigit).orEmpty()
            if (digits.isBlank()) {
                update(i, Status.NO_PHONE, "No phone number")
                i++
                continue
            }

            val message = messageBuilder(item.target)
            if (message.isBlank()) {
                update(i, Status.FAILED, "Message is empty")
                i++
                continue
            }

            val instId = instituteId.orEmpty()
            val alreadySent = try {
                db.bulkMessageLogDao()
                    .hasSent(instId, item.target.key, channel, message) > 0
            } catch (_: Exception) { false }
            if (alreadySent) {
                update(i, Status.DUPLICATE, "Already sent")
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
                update(i, Status.FAILED, "No app found to send this message")
                log(item.target.key, message, "failed")
                _state.update { it.copy(phase = Phase.RUNNING) }
                i++
                continue
            }

            awaitResume()

            update(i, Status.SENT)
            log(item.target.key, message, "sent")
            _state.update { it.copy(phase = Phase.RUNNING) }

            if (i < items.size - 1 && delayMs > 0) delay(delayMs)
            i++
        }
        _state.update { it.copy(phase = Phase.COMPLETED) }
    }

    private suspend fun processServer(items: List<BulkQueueItem>) {
        data class Prepared(val index: Int, val target: BulkTarget, val body: String)
        _state.update { it.copy(phase = Phase.RUNNING, serverManaged = true) }
        val prepared = mutableListOf<Prepared>()
        items.forEachIndexed { index, item ->
            if (item.status != Status.PENDING) return@forEachIndexed
            val digits = item.target.phone?.filter(Char::isDigit).orEmpty()
            if (digits.isBlank()) {
                update(index, Status.NO_PHONE, "No phone number")
                return@forEachIndexed
            }
            val body = messageBuilder(item.target).trim()
            if (body.isBlank()) {
                update(index, Status.FAILED, "Message is empty")
                return@forEachIndexed
            }
            if (body.length > 480) {
                update(index, Status.FAILED, "SMS is too long (maximum 480 characters)")
                return@forEachIndexed
            }
            val alreadySent = try {
                db.bulkMessageLogDao().hasSent(instituteId.orEmpty(), item.target.key, channel, body) > 0
            } catch (_: Exception) { false }
            if (alreadySent) {
                update(index, Status.DUPLICATE, "Already sent")
                return@forEachIndexed
            }
            prepared += Prepared(index, item.target, body)
        }

        prepared.chunked(100).forEachIndexed { chunkIndex, chunk ->
            val operationId = "$serverOperationId-${chunkIndex + 1}"
            val batchResult = runCatching {
                SmsWalletSyncHelper.sendServerSmsBatch(
                    messages = chunk.map {
                        ServerSmsOutbound(
                            targetKey = it.target.key,
                            recipient = it.target.phone.orEmpty(),
                            message = it.body,
                            purpose = "bulk_message"
                        )
                    },
                    operationId = operationId
                )
            }.getOrElse { error ->
                val message = error.message?.takeIf { it.isNotBlank() } ?: "Server SMS could not be sent"
                val ambiguous = error !is FirebaseFunctionsException || error.code in setOf(
                    FirebaseFunctionsException.Code.ABORTED,
                    FirebaseFunctionsException.Code.CANCELLED,
                    FirebaseFunctionsException.Code.DATA_LOSS,
                    FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
                    FirebaseFunctionsException.Code.INTERNAL,
                    FirebaseFunctionsException.Code.UNKNOWN,
                    FirebaseFunctionsException.Code.UNAVAILABLE
                )
                chunk.forEach {
                    if (ambiguous) {
                        update(it.index, Status.QUEUED, "SMS request status is being reconciled. Do not resend.")
                        log(it.target.key, it.body, "sent")
                    } else {
                        update(it.index, Status.FAILED, message)
                    }
                }
                return@forEachIndexed
            }
            val results = batchResult.results.associateBy { it.targetKey }
            chunk.forEach { preparedItem ->
                val result = results[preparedItem.target.key]
                when (result?.status) {
                    "sent", "delivered" -> {
                        update(preparedItem.index, Status.SENT)
                        log(preparedItem.target.key, preparedItem.body, "sent")
                    }
                    "pending" -> {
                        update(preparedItem.index, Status.QUEUED, "Accepted by SMS server; delivery confirmation pending")
                        // Treat an accepted/ambiguous provider hand-off as sent
                        // locally so a new screen session cannot duplicate it.
                        log(preparedItem.target.key, preparedItem.body, "sent")
                    }
                    "failed" -> update(
                        preparedItem.index,
                        Status.FAILED,
                        result.failureReason.takeIf { it.isNotBlank() } ?: "SMS provider rejected the message"
                    )
                    else -> {
                        update(preparedItem.index, Status.QUEUED, "SMS request status is being reconciled. Do not resend.")
                        log(preparedItem.target.key, preparedItem.body, "sent")
                    }
                }
            }
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
        if (_state.value.serverManaged && isRunning) return
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
        serverOperationId = UUID.randomUUID().toString()
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

    private fun update(index: Int, status: Status, error: String? = null) {
        val next = _state.value.items.toMutableList()
        if (index !in next.indices) return
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
