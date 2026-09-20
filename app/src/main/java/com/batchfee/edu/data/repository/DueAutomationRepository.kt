package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import java.util.UUID

data class DueAutomationPolicy(
    val enabled: Boolean = false,
    val beforeDueDays: List<Long> = listOf(7, 3, 1),
    val sendOnDueDay: Boolean = true,
    val afterDueDays: List<Long> = listOf(3, 7, 15),
    val channels: List<String> = listOf("sms"),
    val feeTypes: List<String> = emptyList(),
    val excludedStudentIds: List<String> = emptyList(),
    val excludedBatchIds: List<String> = emptyList(),
    val sendWindowStartHour: Long = 9,
    val sendWindowEndHour: Long = 19,
    val dailySmsLimit: Long = 0,
    val templateId: String = "",
    val lastRunDayKey: String = "",
    val lastRunSentCount: Long = 0,
    val lastRunAtMs: Long = 0,
)

data class DueReminderRecord(
    val reminderId: String = "",
    val studentId: String = "",
    val studentName: String = "",
    val recipient: String = "",
    val batchName: String = "",
    val feePeriods: List<String> = emptyList(),
    val dueAmount: Double = 0.0,
    val dueDateMs: Long = 0,
    val trigger: String = "",
    val channel: String = "sms",
    val status: String = "queued",
    val skipReason: String = "",
    val failureReason: String = "",
    val credits: Long = 0,
    val createdAtMs: Long = 0,
    val sentAtMs: Long = 0,
)

data class DueRunSummary(
    val runId: String = "",
    val dayKey: String = "",
    val runAtMs: Long = 0,
    val matchedCount: Long = 0,
    val sentCount: Long = 0,
    val failedCount: Long = 0,
    val skippedCount: Long = 0,
    val creditsUsed: Long = 0,
)

data class DueAutomationState(
    val policy: DueAutomationPolicy = DueAutomationPolicy(),
    val smsBalance: Long = 0,
    val smsUsedToday: Long = 0,
    val smsUsedThisMonth: Long = 0,
    val totalSmsUsed: Long = 0,
    val smsSendMethod: String = "server",
    val estimatedDaysRemaining: Long = 0,
    val sentToday: Long = 0,
    val remainingTodayLimit: Long? = null,
    val recentReminders: List<DueReminderRecord> = emptyList(),
    val recentRuns: List<DueRunSummary> = emptyList(),
)

data class DueAutomationPreview(
    val matchedCount: Long = 0,
    val smsCount: Long = 0,
    val whatsappCount: Long = 0,
    val estimatedCredits: Long = 0,
    val availableCredits: Long = 0,
    val dailySmsLimit: Long = 0,
    val remainingTodayCapacity: Long? = null,
    val sample: List<DueReminderRecord> = emptyList(),
)

data class DueAutomationHistory(
    val reminders: List<DueReminderRecord> = emptyList(),
    val runs: List<DueRunSummary> = emptyList(),
    val historyTruncated: Boolean = false,
)

object DueAutomationRepository {
    private val functions = FirebaseFunctions.getInstance("asia-south1")

    suspend fun getState(): DueAutomationState = call("get_state").toState()

    suspend fun savePolicy(policy: DueAutomationPolicy) {
        call("save_policy", mapOf("policy" to policy.toPayload()))
    }

    suspend fun preview(): DueAutomationPreview = call("preview_estimate").toPreview()

    suspend fun history(): DueAutomationHistory = call("list_history").toHistory()

    private suspend fun call(action: String, values: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val response = callTrustedFunction(
            functions,
            "commitDueAutomationOperation",
            mapOf("action" to action, "operationId" to UUID.randomUUID().toString()) + values
        )
        @Suppress("UNCHECKED_CAST")
        return response as? Map<String, Any?> ?: emptyMap()
    }
}

private fun Map<*, *>.toPolicy(): DueAutomationPolicy = DueAutomationPolicy(
    enabled = this["enabled"] as? Boolean ?: false,
    beforeDueDays = (this["beforeDueDays"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toLong() },
    sendOnDueDay = this["sendOnDueDay"] as? Boolean ?: true,
    afterDueDays = (this["afterDueDays"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toLong() },
    channels = (this["channels"] as? List<*>).orEmpty().mapNotNull { it as? String },
    feeTypes = (this["feeTypes"] as? List<*>).orEmpty().mapNotNull { it as? String },
    excludedStudentIds = (this["excludedStudentIds"] as? List<*>).orEmpty().mapNotNull { it as? String },
    excludedBatchIds = (this["excludedBatchIds"] as? List<*>).orEmpty().mapNotNull { it as? String },
    sendWindowStartHour = (this["sendWindowStartHour"] as? Number)?.toLong() ?: 9,
    sendWindowEndHour = (this["sendWindowEndHour"] as? Number)?.toLong() ?: 19,
    dailySmsLimit = (this["dailySmsLimit"] as? Number)?.toLong() ?: 0,
    templateId = this["templateId"] as? String ?: "",
    lastRunDayKey = this["lastRunDayKey"] as? String ?: "",
    lastRunSentCount = (this["lastRunSentCount"] as? Number)?.toLong() ?: 0,
    lastRunAtMs = (this["lastRunAtMs"] as? Number)?.toLong() ?: 0,
)

private fun DueAutomationPolicy.toPayload(): Map<String, Any?> = mapOf(
    "enabled" to enabled,
    "beforeDueDays" to beforeDueDays,
    "sendOnDueDay" to sendOnDueDay,
    "afterDueDays" to afterDueDays,
    "channels" to channels,
    "feeTypes" to feeTypes,
    "excludedStudentIds" to excludedStudentIds,
    "excludedBatchIds" to excludedBatchIds,
    "sendWindowStartHour" to sendWindowStartHour,
    "sendWindowEndHour" to sendWindowEndHour,
    "dailySmsLimit" to dailySmsLimit,
    "templateId" to templateId,
)

private fun Map<*, *>.toReminder(): DueReminderRecord = DueReminderRecord(
    reminderId = this["reminderId"] as? String ?: "",
    studentId = this["studentId"] as? String ?: "",
    studentName = this["studentName"] as? String ?: "",
    recipient = this["recipient"] as? String ?: "",
    batchName = this["batchName"] as? String ?: "",
    feePeriods = (this["feePeriods"] as? List<*>).orEmpty().mapNotNull { it as? String },
    dueAmount = (this["dueAmount"] as? Number)?.toDouble() ?: 0.0,
    dueDateMs = (this["dueDateMs"] as? Number)?.toLong() ?: 0,
    trigger = this["trigger"] as? String ?: "",
    channel = this["channel"] as? String ?: "sms",
    status = this["status"] as? String ?: "queued",
    skipReason = this["skipReason"] as? String ?: "",
    failureReason = this["failureReason"] as? String ?: "",
    credits = (this["credits"] as? Number)?.toLong() ?: 0,
    createdAtMs = (this["createdAtMs"] as? Number)?.toLong() ?: 0,
    sentAtMs = (this["sentAtMs"] as? Number)?.toLong() ?: 0,
)

private fun Map<*, *>.toRun(): DueRunSummary = DueRunSummary(
    runId = this["runId"] as? String ?: (this["dayKey"] as? String ?: ""),
    dayKey = this["dayKey"] as? String ?: "",
    runAtMs = (this["runAtMs"] as? Number)?.toLong() ?: 0,
    matchedCount = (this["matchedCount"] as? Number)?.toLong() ?: 0,
    sentCount = (this["sentCount"] as? Number)?.toLong() ?: 0,
    failedCount = (this["failedCount"] as? Number)?.toLong() ?: 0,
    skippedCount = (this["skippedCount"] as? Number)?.toLong() ?: 0,
    creditsUsed = (this["creditsUsed"] as? Number)?.toLong() ?: 0,
)

private fun Map<String, Any?>.toState(): DueAutomationState {
    val wallet = this["wallet"] as? Map<*, *> ?: emptyMap<String, Any?>()
    val today = this["today"] as? Map<*, *> ?: emptyMap<String, Any?>()
    return DueAutomationState(
        policy = ((this["policy"] as? Map<*, *>) ?: emptyMap<String, Any?>()).toPolicy(),
        smsBalance = (wallet["smsBalance"] as? Number)?.toLong() ?: 0,
        smsUsedToday = (wallet["smsUsedToday"] as? Number)?.toLong() ?: 0,
        smsUsedThisMonth = (wallet["smsUsedThisMonth"] as? Number)?.toLong() ?: 0,
        totalSmsUsed = (wallet["totalSmsUsed"] as? Number)?.toLong() ?: 0,
        smsSendMethod = wallet["smsSendMethod"] as? String ?: "server",
        estimatedDaysRemaining = (this["estimatedDaysRemaining"] as? Number)?.toLong() ?: 0,
        sentToday = (today["sent"] as? Number)?.toLong() ?: 0,
        remainingTodayLimit = (today["remainingLimit"] as? Number)?.toLong(),
        recentReminders = (this["recentReminders"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.toReminder() },
        recentRuns = (this["recentRuns"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.toRun() },
    )
}

private fun Map<String, Any?>.toPreview(): DueAutomationPreview = DueAutomationPreview(
    matchedCount = (this["matchedCount"] as? Number)?.toLong() ?: 0,
    smsCount = (this["smsCount"] as? Number)?.toLong() ?: 0,
    whatsappCount = (this["whatsappCount"] as? Number)?.toLong() ?: 0,
    estimatedCredits = (this["estimatedCredits"] as? Number)?.toLong() ?: 0,
    availableCredits = (this["availableCredits"] as? Number)?.toLong() ?: 0,
    dailySmsLimit = (this["dailySmsLimit"] as? Number)?.toLong() ?: 0,
    remainingTodayCapacity = (this["remainingTodayCapacity"] as? Number)?.toLong(),
    sample = (this["sample"] as? List<*>).orEmpty()
        .mapNotNull { (it as? Map<*, *>)?.toReminder() },
)

private fun Map<String, Any?>.toHistory(): DueAutomationHistory = DueAutomationHistory(
    reminders = (this["reminders"] as? List<*>).orEmpty()
        .mapNotNull { (it as? Map<*, *>)?.toReminder() },
    runs = (this["runs"] as? List<*>).orEmpty()
        .mapNotNull { (it as? Map<*, *>)?.toRun() },
    historyTruncated = this["historyTruncated"] as? Boolean ?: false,
)
