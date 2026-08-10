package com.batchfee.edu.data.repository

import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.data.models.PaymentEntity
import com.batchfee.edu.data.models.PaymentReversalEntity
import com.batchfee.edu.data.models.ReceiptEntity
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class FinancialOperationResult(
    val operationId: String,
    val action: String,
    val fees: List<FeeEntity> = emptyList(),
    val payments: List<PaymentEntity> = emptyList(),
    val receipts: List<ReceiptEntity> = emptyList(),
    val reversals: List<PaymentReversalEntity> = emptyList(),
    val deletedPaymentIds: List<String> = emptyList(),
    val deletedReceiptIds: List<String> = emptyList()
)

class FinancialOperationRejectedException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class FinancialOperationPendingException(
    val operationId: String,
    cause: Throwable? = null
) : Exception(
    "Financial operation is pending secure reconciliation (ID: $operationId). Do not submit it again; refresh when online.",
    cause
)

interface FinancialLedgerGateway {
    suspend fun commit(request: Map<String, Any?>): FinancialOperationResult
}

class FirebaseFinancialLedgerGateway : FinancialLedgerGateway {
    private val functions = FirebaseFunctions.getInstance("asia-south1")

    override suspend fun commit(request: Map<String, Any?>): FinancialOperationResult =
        withContext(Dispatchers.IO) {
            try {
                val response = functions.getHttpsCallable("commitFinancialOperation")
                    .call(request)
                    .await()
                @Suppress("UNCHECKED_CAST")
                parseFinancialResult(response.data as? Map<String, Any?>
                    ?: error("Invalid financial operation response."))
            } catch (error: FirebaseFunctionsException) {
                when (error.code) {
                    FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                    FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                    FirebaseFunctionsException.Code.ALREADY_EXISTS,
                    FirebaseFunctionsException.Code.NOT_FOUND,
                    FirebaseFunctionsException.Code.PERMISSION_DENIED,
                    FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                        throw FinancialOperationRejectedException(
                            error.message ?: "Financial operation was rejected.",
                            error
                        )
                    else -> throw error
                }
            }
        }
}

internal object FinancialRequestCodec {
    fun encode(request: Map<String, Any?>): String = JSONObject(request).toString()

    fun decode(json: String): Map<String, Any?> = JSONObject(json).toKotlinMap()

    private fun JSONObject.toKotlinMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
        unwrap(get(key))
    }

    private fun JSONArray.toKotlinList(): List<Any?> = (0 until length()).map { index ->
        unwrap(get(index))
    }

    private fun unwrap(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> value.toKotlinMap()
        is JSONArray -> value.toKotlinList()
        else -> value
    }
}

private fun parseFinancialResult(data: Map<String, Any?>): FinancialOperationResult =
    FinancialOperationResult(
        operationId = data.string("operationId"),
        action = data.string("action"),
        fees = data.maps("fees").map(::parseFee),
        payments = data.maps("payments").map(::parsePayment),
        receipts = data.maps("receipts").map(::parseReceipt),
        reversals = data.maps("reversals").map(::parseReversal),
        deletedPaymentIds = data.strings("deletedPaymentIds"),
        deletedReceiptIds = data.strings("deletedReceiptIds")
    )

private fun parseFee(data: Map<String, Any?>) = FeeEntity(
    id = data.string("id"),
    instituteId = data.string("instituteId"),
    studentId = data.string("studentId"),
    batchId = data.optionalString("batchId"),
    feePeriod = data.string("feePeriod"),
    feeType = data.string("feeType"),
    dueDateMs = data.long("dueDateMs"),
    baseAmount = data.double("baseAmount"),
    discountAmount = data.double("discountAmount"),
    lateFeeAmount = data.double("lateFeeAmount"),
    totalAmount = data.double("totalAmount"),
    paidAmount = data.double("paidAmount"),
    dueAmount = data.double("dueAmount"),
    status = data.string("status"),
    note = data.optionalString("note"),
    createdAtMs = data.long("createdAtMs"),
    updatedAtMs = data.long("updatedAtMs"),
    cancelledAtMs = data.optionalLong("cancelledAtMs"),
    businessKey = data.optionalString("businessKey"),
    ledgerVersion = data.int("ledgerVersion")
)

private fun parsePayment(data: Map<String, Any?>) = PaymentEntity(
    id = data.string("id"),
    instituteId = data.string("instituteId"),
    feeId = data.string("feeId"),
    studentId = data.string("studentId"),
    amount = data.double("amount"),
    paymentMethod = data.string("paymentMethod"),
    transactionId = data.optionalString("transactionId"),
    receiptNumber = data.string("receiptNumber"),
    paymentDateMs = data.long("paymentDateMs"),
    collectedByUserId = data.string("collectedByUserId"),
    status = data.string("status"),
    note = data.optionalString("note"),
    createdAtMs = data.long("createdAtMs"),
    updatedAtMs = data.long("updatedAtMs"),
    operationId = data.optionalString("operationId"),
    ledgerVersion = data.int("ledgerVersion")
)

private fun parseReceipt(data: Map<String, Any?>) = ReceiptEntity(
    id = data.string("id"),
    instituteId = data.string("instituteId"),
    paymentId = data.string("paymentId"),
    feeId = data.string("feeId"),
    studentId = data.string("studentId"),
    receiptNumber = data.string("receiptNumber"),
    receiptDateMs = data.long("receiptDateMs"),
    totalAmount = data.double("totalAmount"),
    paidAmount = data.double("paidAmount"),
    dueAmount = data.double("dueAmount"),
    paymentMethod = data.string("paymentMethod"),
    receiptText = data.optionalString("receiptText"),
    createdAtMs = data.long("createdAtMs"),
    operationId = data.optionalString("operationId"),
    ledgerVersion = data.int("ledgerVersion")
)

private fun parseReversal(data: Map<String, Any?>) = PaymentReversalEntity(
    id = data.string("id"),
    instituteId = data.string("instituteId"),
    paymentId = data.string("paymentId"),
    feeId = data.string("feeId"),
    studentId = data.string("studentId"),
    amount = data.double("amount"),
    receiptNumber = data.string("receiptNumber"),
    reason = data.string("reason"),
    reversedByUserId = data.string("reversedByUserId"),
    reversedAtMs = data.long("reversedAtMs"),
    operationId = data.string("operationId"),
    ledgerVersion = data.int("ledgerVersion")
)

private fun Map<String, Any?>.string(key: String): String =
    this[key] as? String ?: error("Missing $key in financial response.")

private fun Map<String, Any?>.optionalString(key: String): String? = this[key] as? String

private fun Map<String, Any?>.double(key: String): Double =
    (this[key] as? Number)?.toDouble() ?: error("Missing $key in financial response.")

private fun Map<String, Any?>.long(key: String): Long =
    (this[key] as? Number)?.toLong() ?: error("Missing $key in financial response.")

private fun Map<String, Any?>.optionalLong(key: String): Long? = (this[key] as? Number)?.toLong()

private fun Map<String, Any?>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: 0

private fun Map<String, Any?>.maps(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.mapNotNull { value ->
        @Suppress("UNCHECKED_CAST")
        value as? Map<String, Any?>
    }.orEmpty()

private fun Map<String, Any?>.strings(key: String): List<String> =
    (this[key] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
