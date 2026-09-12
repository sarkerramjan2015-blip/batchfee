package com.batchfee.edu.data.firestore

import com.batchfee.edu.data.repository.callTrustedFunction
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Multi-tenant SMS wallet state. Counters are server-authoritative: the client
 * can read them and choose a send method, but never writes a balance directly.
 */
data class SmsWalletState(
    val smsBalance: Int = 0,
    val totalSmsPurchased: Int = 0,
    val totalSmsUsed: Int = 0,
    val smsUsedToday: Int = 0,
    val smsUsedThisMonth: Int = 0,
    val smsSendMethod: String = METHOD_CARRIER
) {
    companion object {
        const val METHOD_CARRIER = "carrier"
        const val METHOD_SERVER = "server"
    }
}

/** Server-quoted recharge package. Prices are never hardcoded client-side. */
data class SmsPackage(
    val packageId: String,
    val layer: String,
    val name: String,
    val baseAmount: Double,
    val chargePercent: Double,
    val chargeAmount: Double,
    val payableAmount: Double,
    val smsCount: Int
)

data class SmsRechargeRequest(
    val requestId: String,
    val status: String,
    val packageName: String,
    val layer: String,
    val smsCount: Int,
    val baseAmount: Double,
    val chargeAmount: Double,
    val payableAmount: Double,
    val paymentMethod: String,
    val senderPhone: String,
    val createdAtMs: Long,
    val reviewedAtMs: Long,
    val reviewerNote: String
)

/** One outbound message handed to the phone's SMS app (carrier channel). */
data class SmsOutboundRecord(
    val recipient: String,
    val purpose: String = ""
)

data class SmsMessageStatus(
    val messageId: String,
    val recipient: String,
    val purpose: String,
    val channel: String,
    val status: String,
    val createdAtMs: Long,
    val deliveredAtMs: Long,
    val failureReason: String
)

data class SmsMessageReport(
    val sent: Int = 0,
    val delivered: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val messages: List<SmsMessageStatus> = emptyList()
)

data class ServerSmsOutbound(
    val targetKey: String,
    val recipient: String,
    val message: String,
    val purpose: String = "bulk_message"
)

data class ServerSmsResult(
    val messageId: String,
    val targetKey: String,
    val status: String,
    val providerStatus: String,
    val credits: Int,
    val failureReason: String
)

data class ServerSmsBatchResult(
    val replayed: Boolean,
    val wallet: SmsWalletState,
    val results: List<ServerSmsResult>
)

/**
 * Client helpers for the institute SMS wallet. Wallet fields live on the
 * institute document (`sms_balance`, `total_sms_purchased`, `total_sms_used`,
 * `sms_send_method`) and are initialized with their defaults by the trusted
 * `commitSmsWalletOperation` callable; the send method is changed through the
 * same callable so the change is audited and can never be forged by a client.
 */
object SmsWalletSyncHelper {

    private val firestore = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance("asia-south1")

    /** Reads the wallet straight from the institute document with safe defaults. */
    suspend fun fetchWallet(instituteId: String): SmsWalletState = withContext(Dispatchers.IO) {
        val snapshot = firestore.collection("institutes").document(instituteId).get().await()
        val data = snapshot.data ?: emptyMap<String, Any?>()
        SmsWalletState(
            smsBalance = (data["sms_balance"] as? Number)?.toInt() ?: 0,
            totalSmsPurchased = (data["total_sms_purchased"] as? Number)?.toInt() ?: 0,
            totalSmsUsed = (data["total_sms_used"] as? Number)?.toInt() ?: 0,
            smsUsedToday = (data["sms_used_today"] as? Number)?.toInt() ?: 0,
            smsUsedThisMonth = (data["sms_used_this_month"] as? Number)?.toInt() ?: 0,
            smsSendMethod = data["sms_send_method"] as? String ?: SmsWalletState.METHOD_CARRIER
        )
    }

    /** Ensures the server has initialized any missing wallet field and returns the canonical state. */
    suspend fun ensureWalletInitialized(instituteId: String): SmsWalletState = toWalletState(
        call("get_wallet", instituteId, emptyMap())
    )

    /**
     * Owner-only send-method change. The trusted callable validates the value,
     * writes the institute document, and appends an immutable audit entry. The
     * same operation ID replays safely without a duplicate audit row.
     */
    suspend fun setSmsSendMethod(
        instituteId: String,
        method: String,
        operationId: String = UUID.randomUUID().toString()
    ): SmsWalletState {
        require(method in setOf(SmsWalletState.METHOD_CARRIER, SmsWalletState.METHOD_SERVER)) {
            "The SMS sending method must be carrier or server."
        }
        return toWalletState(call("set_send_method", instituteId, mapOf("smsSendMethod" to method), operationId))
    }

    /** Returns the server-quoted recharge package list. */
    suspend fun listPackages(): List<SmsPackage> {
        val payload = call("list_packages", instituteId = null, values = emptyMap())
        @Suppress("UNCHECKED_CAST")
        return (payload["packages"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.toSmsPackage() }
    }

    /** Owner-only recharge request with a manual Nagad/BKash payment. */
    suspend fun submitRechargeRequest(
        packageId: String,
        paymentMethod: String,
        senderPhone: String,
        operationId: String = UUID.randomUUID().toString()
    ): SmsRechargeRequest {
        require(paymentMethod in setOf("bkash", "nagad")) { "Payment method must be bKash or Nagad." }
        require(senderPhone.isNotBlank()) { "The sender phone number is required." }
        val payload = call(
            "submit_recharge_request",
            instituteId = null,
            values = mapOf(
                "packageId" to packageId,
                "paymentMethod" to paymentMethod,
                "senderPhone" to senderPhone
            ),
            operationId = operationId
        )
        @Suppress("UNCHECKED_CAST")
        val request = payload["request"] as? Map<*, *> ?: error("The recharge request was not returned.")
        return request.toSmsRechargeRequest()
    }

    /** The current institute's own recharge request history, newest first. */
    suspend fun myRechargeRequests(): List<SmsRechargeRequest> {
        val payload = call("list_my_recharge_requests", instituteId = null, values = emptyMap())
        @Suppress("UNCHECKED_CAST")
        return (payload["requests"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.toSmsRechargeRequest() }
    }

    /**
     * Records carrier hand-offs as sent. Tracking is fire-and-forget: a failed
     * record can never block or cancel an actual SMS send.
     */
    suspend fun recordCarrierSmsBatch(messages: List<SmsOutboundRecord>, operationId: String = UUID.randomUUID().toString()) {
        if (messages.isEmpty()) return
        call(
            "record_sms_batch",
            instituteId = null,
            values = mapOf(
                "channel" to "carrier",
                "messages" to messages.map { mapOf("recipient" to it.recipient, "purpose" to it.purpose) }
            ),
            operationId = operationId
        )
    }

    /**
     * Sends an idempotent batch through the trusted server gateway. Provider
     * credentials never reach the Android process.
     */
    suspend fun sendServerSmsBatch(
        messages: List<ServerSmsOutbound>,
        operationId: String = UUID.randomUUID().toString()
    ): ServerSmsBatchResult {
        require(messages.isNotEmpty() && messages.size <= 100) { "A server SMS batch must contain 1-100 messages." }
        val response = callTrustedFunction(
            functions,
            "sendBulkSms",
            mapOf(
                "operationId" to operationId,
                "messages" to messages.map {
                    mapOf(
                        "targetKey" to it.targetKey,
                        "recipient" to it.recipient,
                        "message" to it.message,
                        "purpose" to it.purpose
                    )
                }
            )
        )
        @Suppress("UNCHECKED_CAST")
        val payload = response as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val rawResults = payload["results"] as? List<*> ?: emptyList<Any?>()
        @Suppress("UNCHECKED_CAST")
        val rawWallet = payload["wallet"] as? Map<String, Any?> ?: emptyMap()
        return ServerSmsBatchResult(
            replayed = payload["replayed"] as? Boolean ?: false,
            wallet = toWalletState(rawWallet),
            results = rawResults.mapNotNull { (it as? Map<*, *>)?.toServerSmsResult() }
        )
    }

    /** Status counts plus the latest messages for the institute SMS report. */
    suspend fun smsReport(): SmsMessageReport {
        val payload = call("list_sms_report", instituteId = null, values = emptyMap())
        @Suppress("UNCHECKED_CAST")
        val counts = payload["counts"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        @Suppress("UNCHECKED_CAST")
        val messages = (payload["messages"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.toSmsMessageStatus() }
        return SmsMessageReport(
            sent = (counts["sent"] as? Number)?.toInt() ?: 0,
            delivered = (counts["delivered"] as? Number)?.toInt() ?: 0,
            pending = (counts["pending"] as? Number)?.toInt() ?: 0,
            failed = (counts["failed"] as? Number)?.toInt() ?: 0,
            messages = messages
        )
    }

    private suspend fun call(
        action: String,
        instituteId: String?,
        values: Map<String, Any?>,
        operationId: String = UUID.randomUUID().toString()
    ): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>("action" to action, "operationId" to operationId)
        if (!instituteId.isNullOrBlank()) base["instituteId"] = instituteId
        val response = callTrustedFunction(
            functions,
            "commitSmsWalletOperation",
            base + values
        )
        @Suppress("UNCHECKED_CAST")
        return response as? Map<String, Any?> ?: emptyMap()
    }

    private fun toWalletState(payload: Map<String, Any?>): SmsWalletState = SmsWalletState(
        smsBalance = (payload["smsBalance"] as? Number)?.toInt() ?: 0,
        totalSmsPurchased = (payload["totalSmsPurchased"] as? Number)?.toInt() ?: 0,
        totalSmsUsed = (payload["totalSmsUsed"] as? Number)?.toInt() ?: 0,
        smsUsedToday = (payload["smsUsedToday"] as? Number)?.toInt() ?: 0,
        smsUsedThisMonth = (payload["smsUsedThisMonth"] as? Number)?.toInt() ?: 0,
        smsSendMethod = payload["smsSendMethod"] as? String ?: SmsWalletState.METHOD_CARRIER
    )
}

private fun Map<*, *>.toSmsPackage(): SmsPackage {
    fun number(key: String): Double = (this[key] as? Number)?.toDouble()
        ?: error("Missing $key in SMS package quote.")
    return SmsPackage(
        packageId = this["packageId"] as? String ?: error("Missing packageId in SMS package quote."),
        layer = this["layer"] as? String ?: "",
        name = this["name"] as? String ?: "",
        baseAmount = number("baseAmount"),
        chargePercent = number("chargePercent"),
        chargeAmount = number("chargeAmount"),
        payableAmount = number("payableAmount"),
        smsCount = (this["smsCount"] as? Number)?.toInt() ?: 0
    )
}

private fun Map<*, *>.toSmsRechargeRequest(): SmsRechargeRequest = SmsRechargeRequest(
    requestId = this["requestId"] as? String ?: "",
    status = this["status"] as? String ?: "pending",
    packageName = this["packageName"] as? String ?: "",
    layer = this["layer"] as? String ?: "",
    smsCount = (this["smsCount"] as? Number)?.toInt() ?: 0,
    baseAmount = (this["baseAmount"] as? Number)?.toDouble() ?: 0.0,
    chargeAmount = (this["chargeAmount"] as? Number)?.toDouble() ?: 0.0,
    payableAmount = (this["payableAmount"] as? Number)?.toDouble() ?: 0.0,
    paymentMethod = this["paymentMethod"] as? String ?: "",
    senderPhone = this["senderPhone"] as? String ?: "",
    createdAtMs = (this["createdAtMs"] as? Number)?.toLong() ?: 0L,
    reviewedAtMs = (this["reviewedAtMs"] as? Number)?.toLong() ?: 0L,
    reviewerNote = this["reviewerNote"] as? String ?: ""
)

private fun Map<*, *>.toServerSmsResult(): ServerSmsResult = ServerSmsResult(
    messageId = this["messageId"] as? String ?: "",
    targetKey = this["targetKey"] as? String ?: "",
    status = this["status"] as? String ?: "failed",
    providerStatus = this["providerStatus"] as? String ?: "",
    credits = (this["credits"] as? Number)?.toInt() ?: 1,
    failureReason = this["failureReason"] as? String ?: ""
)

private fun Map<*, *>.toSmsMessageStatus(): SmsMessageStatus = SmsMessageStatus(
    messageId = this["messageId"] as? String ?: "",
    recipient = this["recipient"] as? String ?: "",
    purpose = this["purpose"] as? String ?: "",
    channel = this["channel"] as? String ?: "carrier",
    status = this["status"] as? String ?: "sent",
    createdAtMs = (this["createdAtMs"] as? Number)?.toLong() ?: 0L,
    deliveredAtMs = (this["deliveredAtMs"] as? Number)?.toLong() ?: 0L,
    failureReason = this["failureReason"] as? String ?: ""
)
