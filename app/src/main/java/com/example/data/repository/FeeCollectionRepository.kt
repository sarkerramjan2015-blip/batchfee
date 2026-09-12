package com.batchfee.edu.data.repository

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.data.models.FinancialOutboxEntity
import com.batchfee.edu.data.models.PaymentEntity
import java.util.UUID
import kotlin.math.abs

data class FeeCollectionResult(
    val paymentId: String,
    val receiptNumber: String,
    val updatedFee: FeeEntity
)

data class FeeCreationResult(
    val fee: FeeEntity,
    val paymentId: String? = null,
    val receiptNumber: String? = null
)

/** One monthly allocation inside a single, atomic guardian-facing receipt. */
data class GroupedMonthlyCollectionAllocation(
    val feeId: String? = null,
    val batchId: String? = null,
    val feePeriod: String,
    val feeType: String = "monthly_fee",
    val sourceId: String? = null,
    val dueDateMs: Long,
    val baseAmount: Double = 0.0,
    val discountAmount: Double = 0.0,
    val lateFeeAmount: Double = 0.0,
    val amount: Double
)

data class GroupedFeeCollectionResult(
    val receiptNumber: String,
    val fees: List<FeeEntity>,
    val payments: List<PaymentEntity>
)

class FeeCollectionRepository(
    private val db: AppDatabase,
    private val ledgerGateway: FinancialLedgerGateway = FirebaseFinancialLedgerGateway()
) {

    suspend fun createFee(
        instituteId: String,
        studentId: String,
        batchId: String?,
        feePeriod: String,
        feeType: String,
        sourceId: String? = null,
        dueDateMs: Long,
        baseAmount: Double,
        discountAmount: Double,
        lateFeeAmount: Double,
        note: String? = null,
        now: Long = System.currentTimeMillis(),
        operationId: String = UUID.randomUUID().toString()
    ): FeeEntity {
        val result = execute(
            request = baseRequest(operationId, instituteId, "create_fee") + mapOf(
                "studentId" to studentId,
                "batchId" to batchId,
                "feePeriod" to feePeriod,
                "feeType" to feeType,
                "sourceId" to sourceId,
                "dueDateMs" to dueDateMs,
                "baseAmount" to baseAmount,
                "discountAmount" to discountAmount,
                "lateFeeAmount" to lateFeeAmount,
                "note" to note
            ),
            queuedAtMs = now
        )
        return result.fees.singleOrNull() ?: error("Fee was not returned by the ledger service.")
    }

    suspend fun createFeeWithInitialPayment(
        instituteId: String,
        collectedByUserId: String,
        studentId: String,
        batchId: String?,
        feePeriod: String,
        feeType: String,
        sourceId: String? = null,
        dueDateMs: Long,
        baseAmount: Double,
        discountAmount: Double,
        lateFeeAmount: Double,
        collectedAmount: Double,
        paymentMethod: String,
        paymentDateMs: Long,
        note: String?,
        receiptText: String?,
        now: Long = System.currentTimeMillis(),
        receiptGroupId: String? = null,
        transactionId: String? = null,
        operationId: String = UUID.randomUUID().toString()
    ): FeeCreationResult {
        require(collectedByUserId.isNotBlank()) { "A signed-in collector is required." }
        val result = execute(
            request = baseRequest(operationId, instituteId, "create_fee") + mapOf(
                "studentId" to studentId,
                "batchId" to batchId,
                "feePeriod" to feePeriod,
                "feeType" to feeType,
                "sourceId" to sourceId,
                "dueDateMs" to dueDateMs,
                "baseAmount" to baseAmount,
                "discountAmount" to discountAmount,
                "lateFeeAmount" to lateFeeAmount,
                "amount" to collectedAmount,
                "paymentMethod" to paymentMethod,
                "paymentDateMs" to paymentDateMs,
                "note" to note,
                "receiptText" to receiptText,
                "receiptGroupId" to receiptGroupId,
                "transactionId" to transactionId
            ),
            queuedAtMs = now
        )
        val fee = result.fees.singleOrNull() ?: error("Fee was not returned by the ledger service.")
        return FeeCreationResult(
            fee = fee,
            paymentId = result.payments.singleOrNull()?.id,
            receiptNumber = result.receipts.singleOrNull()?.receiptNumber
        )
    }

    suspend fun collectPayment(
        instituteId: String,
        collectedByUserId: String,
        feeId: String,
        amount: Double,
        paymentMethod: String,
        paymentDateMs: Long = System.currentTimeMillis(),
        note: String? = null,
        receiptText: String? = null,
        now: Long = System.currentTimeMillis(),
        receiptGroupId: String? = null,
        transactionId: String? = null,
        operationId: String = UUID.randomUUID().toString()
    ): FeeCollectionResult {
        require(collectedByUserId.isNotBlank()) { "A signed-in collector is required." }
        val result = execute(
            request = baseRequest(operationId, instituteId, "collect_payment") + mapOf(
                "feeId" to feeId,
                "amount" to amount,
                "paymentMethod" to paymentMethod,
                "paymentDateMs" to paymentDateMs,
                "note" to note,
                "receiptText" to receiptText,
                "receiptGroupId" to receiptGroupId,
                "transactionId" to transactionId
            ),
            queuedAtMs = now
        )
        return result.asCollectionResult()
    }

    suspend fun updateFeeAndCollectPayment(
        instituteId: String,
        collectedByUserId: String,
        feeId: String,
        newBaseAmount: Double,
        discountPercent: Double,
        collectedAmount: Double,
        paymentMethod: String,
        feePeriod: String,
        note: String? = null,
        now: Long = System.currentTimeMillis(),
        operationId: String = UUID.randomUUID().toString()
    ): FeeCollectionResult {
        require(collectedByUserId.isNotBlank()) { "A signed-in collector is required." }
        val result = execute(
            request = baseRequest(operationId, instituteId, "adjust_and_collect") + mapOf(
                "feeId" to feeId,
                "newBaseAmount" to newBaseAmount,
                "discountPercent" to discountPercent,
                "amount" to collectedAmount,
                "paymentMethod" to paymentMethod,
                "paymentDateMs" to now,
                "feePeriod" to feePeriod,
                "note" to note
            ),
            queuedAtMs = now
        )
        return result.asCollectionResult()
    }

    /**
     * Records a full one-time fee waiver without creating a misleading zero-
     * value cash payment. The trusted ledger checks the immutable payment
     * history, records the reason/audit entry, and returns the canonical fee.
     */
    suspend fun waiveFee(
        instituteId: String,
        feeId: String? = null,
        studentId: String? = null,
        batchId: String? = null,
        feePeriod: String? = null,
        feeType: String? = null,
        sourceId: String? = null,
        dueDateMs: Long? = null,
        baseAmount: Double? = null,
        reason: String,
        now: Long = System.currentTimeMillis(),
        operationId: String = UUID.randomUUID().toString()
    ): FeeEntity {
        require(reason.trim().length >= 3) { "A waiver reason is required." }
        require(feeId != null || (
            !studentId.isNullOrBlank() && !feePeriod.isNullOrBlank() &&
                !feeType.isNullOrBlank() && dueDateMs != null && baseAmount != null
            )) { "A canonical fee or complete virtual fee details are required." }
        val result = execute(
            request = baseRequest(operationId, instituteId, "waive_fee") + mapOf(
                "feeId" to feeId,
                "studentId" to studentId,
                "batchId" to batchId,
                "feePeriod" to feePeriod,
                "feeType" to feeType,
                "sourceId" to sourceId,
                "dueDateMs" to dueDateMs,
                "baseAmount" to baseAmount,
                "reason" to reason.trim()
            ),
            queuedAtMs = now
        )
        return result.fees.singleOrNull() ?: error("Waived fee was not returned by the ledger service.")
    }

    /**
     * Commits every monthly allocation, payment and one receipt header in one
     * trusted Firestore transaction. Room is updated only from that canonical
     * result, so a failed or replayed request cannot leave a partial group.
     */
    suspend fun collectGroupedMonthlyPayment(
        instituteId: String,
        collectedByUserId: String,
        studentId: String,
        allocations: List<GroupedMonthlyCollectionAllocation>,
        paymentMethod: String,
        paymentDateMs: Long = System.currentTimeMillis(),
        note: String? = null,
        receiptText: String? = null,
        now: Long = System.currentTimeMillis(),
        operationId: String = UUID.randomUUID().toString()
    ): GroupedFeeCollectionResult {
        require(collectedByUserId.isNotBlank()) { "A signed-in collector is required." }
        require(allocations.isNotEmpty()) { "A grouped collection needs at least one monthly fee." }
        require(allocations.all { it.amount > 0.0 }) { "Every grouped allocation must collect an amount." }
        val result = execute(
            request = baseRequest(operationId, instituteId, "collect_grouped_payment") + mapOf(
                "studentId" to studentId,
                "paymentMethod" to paymentMethod,
                "paymentDateMs" to paymentDateMs,
                "note" to note,
                "receiptText" to receiptText,
                "allocations" to allocations.map { allocation ->
                    mapOf(
                        "feeId" to allocation.feeId,
                        "batchId" to allocation.batchId,
                        "feePeriod" to allocation.feePeriod,
                        "feeType" to allocation.feeType,
                        "sourceId" to allocation.sourceId,
                        "dueDateMs" to allocation.dueDateMs,
                        "baseAmount" to allocation.baseAmount,
                        "discountAmount" to allocation.discountAmount,
                        "lateFeeAmount" to allocation.lateFeeAmount,
                        "amount" to allocation.amount
                    )
                }
            ),
            queuedAtMs = now
        )
        val receipt = result.receipts.singleOrNull()
            ?: error("Grouped receipt was not returned by the ledger service.")
        return GroupedFeeCollectionResult(
            receiptNumber = receipt.receiptNumber,
            fees = result.fees,
            payments = result.payments
        )
    }

    /**
     * Saves a student's reduced monthly fee through the trusted ledger. The
     * backend updates only unpaid running/future monthly fee records; paid
     * receipts and past arrears are deliberately immutable.
     */
    suspend fun setCustomMonthlyFee(
        instituteId: String,
        enrollmentId: String,
        studentId: String,
        batchId: String,
        customMonthlyFeeAmount: Double?,
        customFeeReason: String?,
        now: Long = System.currentTimeMillis(),
        operationId: String = UUID.randomUUID().toString()
    ): FinancialOperationResult = execute(
        request = baseRequest(operationId, instituteId, "set_custom_monthly_fee") + mapOf(
            "enrollmentId" to enrollmentId,
            "studentId" to studentId,
            "batchId" to batchId,
            "customMonthlyFeeAmount" to customMonthlyFeeAmount,
            "customFeeReason" to customFeeReason
        ),
        queuedAtMs = now
    )

    /**
     * Reconciles an owner's admission-date correction through the trusted
     * ledger. The server updates active enrollments and only fully unpaid
     * monthly rows; receipts and paid history remain immutable.
     */
    suspend fun updateStudentAdmissionDate(
        instituteId: String,
        studentId: String,
        admissionDateMs: Long,
        now: Long = System.currentTimeMillis(),
        operationId: String = UUID.randomUUID().toString()
    ): FinancialOperationResult = execute(
        request = baseRequest(operationId, instituteId, "update_student_admission_date") + mapOf(
            "studentId" to studentId,
            "admissionDateMs" to admissionDateMs
        ),
        queuedAtMs = now
    )

    /**
     * Repairs only impossible, fully unpaid legacy monthly rows that fall
     * before the student's admission/batch window. The trusted server keeps
     * every payment and receipt immutable.
     */
    suspend fun reconcileInvalidMonthlyFees(
        instituteId: String,
        studentId: String,
        now: Long = System.currentTimeMillis(),
        operationId: String = UUID.randomUUID().toString()
    ): FinancialOperationResult = execute(
        request = baseRequest(operationId, instituteId, "reconcile_invalid_monthly_fees") + mapOf(
            "studentId" to studentId
        ),
        queuedAtMs = now
    )

    suspend fun reversePayment(
        paymentId: String,
        instituteId: String,
        reason: String,
        now: Long = System.currentTimeMillis(),
        operationId: String = UUID.randomUUID().toString()
    ) {
        require(reason.trim().length >= 3) { "A reversal reason is required." }
        execute(
            request = baseRequest(operationId, instituteId, "reverse_payment") + mapOf(
                "paymentId" to paymentId,
                "reason" to reason.trim()
            ),
            queuedAtMs = now
        )
    }

    suspend fun ownerEditPayment(
        paymentId: String,
        instituteId: String,
        amount: Double,
        paymentMethod: String,
        paymentDateMs: Long,
        feePeriod: String,
        note: String?,
        reason: String,
        now: Long = System.currentTimeMillis(),
        operationId: String = UUID.randomUUID().toString()
    ): FeeCollectionResult {
        require(reason.trim().length >= 3) { "A correction reason is required." }
        val result = execute(
            request = baseRequest(operationId, instituteId, "owner_edit_payment") + mapOf(
                "paymentId" to paymentId,
                "amount" to amount,
                "paymentMethod" to paymentMethod,
                "paymentDateMs" to paymentDateMs,
                "feePeriod" to feePeriod,
                "note" to note,
                "reason" to reason.trim()
            ),
            queuedAtMs = now
        )
        val payment = result.payments.singleOrNull() ?: error("Updated payment was not returned by the ledger service.")
        val fee = result.fees.singleOrNull { it.id == payment.feeId }
            ?: error("Updated payment fee was not returned by the ledger service.")
        val receipt = result.receipts.singleOrNull() ?: error("Updated receipt was not returned by the ledger service.")
        return FeeCollectionResult(payment.id, receipt.receiptNumber, fee)
    }

    suspend fun ownerDeletePayment(
        paymentId: String,
        instituteId: String,
        reason: String,
        now: Long = System.currentTimeMillis(),
        operationId: String = UUID.randomUUID().toString()
    ) {
        require(reason.trim().length >= 3) { "A deletion reason is required." }
        execute(
            request = baseRequest(operationId, instituteId, "owner_delete_payment") + mapOf(
                "paymentId" to paymentId,
                "reason" to reason.trim()
            ),
            queuedAtMs = now
        )
    }

    suspend fun replayPendingOperations(instituteId: String) {
        db.financialLedgerDao().getPendingOperations(instituteId).forEach { pending ->
            try {
                execute(FinancialRequestCodec.decode(pending.requestJson), pending.createdAtMs)
            } catch (_: Exception) {
                // The durable pending/failed status is retained for a later retry or operator review.
            }
        }
    }

    private suspend fun execute(
        request: Map<String, Any?>,
        queuedAtMs: Long
    ): FinancialOperationResult {
        val operationId = request["operationId"] as? String ?: error("Missing operation ID.")
        val instituteId = request["instituteId"] as? String ?: error("Missing institute ID.")
        val action = request["action"] as? String ?: error("Missing financial action.")

        // Do not queue a create/edit/delete operation for a signed-out user.
        // A later login must never silently perform a financial action that
        // the owner only attempted while their session had expired.
        ledgerGateway.requireAuthenticatedSession()

        val existing = db.financialLedgerDao().getOperation(instituteId, operationId)
        val pending = FinancialOutboxEntity(
            operationId = operationId,
            instituteId = instituteId,
            action = action,
            requestJson = FinancialRequestCodec.encode(request),
            status = "pending",
            attempts = (existing?.attempts ?: 0) + 1,
            createdAtMs = existing?.createdAtMs ?: queuedAtMs,
            updatedAtMs = System.currentTimeMillis(),
            lastError = null
        )
        db.financialLedgerDao().upsertOutbox(pending)

        return try {
            val result = ledgerGateway.commit(request)
            validateCanonicalResult(request, result)
            db.withTransaction {
                result.fees.forEach { fee ->
                    fee.businessKey?.let { businessKey ->
                        val existingFee = db.feeDao().getFeeByBusinessKey(fee.instituteId, businessKey)
                        check(existingFee == null || existingFee.id == fee.id) {
                            "Local fee business key collision."
                        }
                    }
                    db.feeDao().insertFee(fee)
                }
                result.payments.forEach { payment ->
                    payment.operationId?.let { paymentOperationId ->
                        val existingPayment = db.paymentDao()
                            .getPaymentByOperationId(payment.instituteId, paymentOperationId)
                        check(existingPayment == null || existingPayment.id == payment.id) {
                            "Local payment operation collision."
                        }
                    }
                    db.paymentDao().insertPayment(payment)
                }
                result.receipts.forEach { receipt ->
                    receipt.operationId?.let { receiptOperationId ->
                        val existingReceipt = db.receiptDao()
                            .getReceiptByOperationId(receipt.instituteId, receiptOperationId)
                        check(existingReceipt == null || existingReceipt.id == receipt.id) {
                            "Local receipt operation collision."
                        }
                    }
                    db.receiptDao().insertReceipt(receipt)
                }
                result.reversals.forEach { reversal ->
                    val existingReversal = db.financialLedgerDao()
                        .getReversalForPayment(reversal.instituteId, reversal.paymentId)
                    check(existingReversal == null || existingReversal.id == reversal.id) {
                        "Local payment reversal collision."
                    }
                    db.financialLedgerDao().upsertReversal(reversal)
                }
                result.deletedReceiptIds.forEach { receiptId ->
                    db.receiptDao().deleteReceiptById(instituteId, receiptId)
                }
                result.deletedPaymentIds.forEach { paymentId ->
                    db.paymentDao().deletePaymentById(instituteId, paymentId)
                }
                db.financialLedgerDao().upsertOutbox(
                    pending.copy(
                        status = "completed",
                        updatedAtMs = System.currentTimeMillis(),
                        lastError = null
                    )
                )
            }
            result
        } catch (error: Exception) {
            val rejected = error is FinancialOperationRejectedException
            val sessionExpired = error is FinancialSessionExpiredException
            if (sessionExpired) {
                // The callable could not authenticate, so it did not commit a
                // financial mutation. Restore pre-existing retry state, or
                // remove the transient outbox record created for this tap.
                if (existing == null) {
                    db.financialLedgerDao().deleteOperation(instituteId, operationId)
                } else {
                    db.financialLedgerDao().upsertOutbox(existing)
                }
                throw error
            }
            db.financialLedgerDao().upsertOutbox(
                pending.copy(
                    status = if (rejected) "failed" else "pending",
                    updatedAtMs = System.currentTimeMillis(),
                    lastError = error.message?.take(500)
                )
            )
            if (rejected) throw error
            throw FinancialOperationPendingException(operationId, error)
        }
    }

    private fun baseRequest(operationId: String, instituteId: String, action: String) = mapOf(
        "operationId" to operationId,
        "instituteId" to instituteId,
        "action" to action
    )

    private fun validateCanonicalResult(
        request: Map<String, Any?>,
        result: FinancialOperationResult
    ) {
        val operationId = request["operationId"] as String
        val instituteId = request["instituteId"] as String
        val action = request["action"] as String
        check(result.operationId == operationId && result.action == action) {
            "Ledger response does not match the queued operation."
        }
        check((result.fees + result.payments + result.receipts + result.reversals)
            .all { record ->
                when (record) {
                    is com.batchfee.edu.data.models.FeeEntity -> record.instituteId == instituteId
                    is com.batchfee.edu.data.models.PaymentEntity -> record.instituteId == instituteId
                    is com.batchfee.edu.data.models.ReceiptEntity -> record.instituteId == instituteId
                    is com.batchfee.edu.data.models.PaymentReversalEntity -> record.instituteId == instituteId
                    else -> false
                }
            }) { "Ledger response crossed the institute boundary." }

        if (action !in setOf(
                "set_custom_monthly_fee",
                "update_student_admission_date",
                "reconcile_invalid_monthly_fees"
            )) {
            check(result.fees.isNotEmpty()) { "Ledger response must contain a canonical fee." }
        }
        result.fees.forEach { fee ->
            check(abs((fee.totalAmount - fee.paidAmount) - fee.dueAmount) <= 0.001) {
                "Ledger response contains inconsistent fee totals."
            }
        }
        when (action) {
            "create_fee" -> {
                val hasPayment = ((request["amount"] as? Number)?.toDouble() ?: 0.0) > 0.0
                check(result.payments.size == if (hasPayment) 1 else 0)
                check(result.receipts.size == if (hasPayment) 1 else 0)
                check(result.reversals.isEmpty())
            }
            "collect_payment", "adjust_and_collect" -> {
                check(result.payments.size == 1 && result.receipts.size == 1 && result.reversals.isEmpty())
            }
            "waive_fee" -> {
                check(result.fees.size == 1 && result.payments.isEmpty() && result.receipts.isEmpty())
                check(result.reversals.isEmpty() && result.deletedPaymentIds.isEmpty() && result.deletedReceiptIds.isEmpty())
                val fee = result.fees.single()
                check(fee.totalAmount == 0.0 && fee.paidAmount == 0.0 && fee.dueAmount == 0.0)
            }
            "collect_grouped_payment" -> {
                val allocations = request["allocations"] as? List<*> ?: emptyList<Any>()
                check(allocations.isNotEmpty())
                check(result.fees.size == allocations.size && result.payments.size == allocations.size)
                check(result.receipts.size == 1 && result.reversals.isEmpty())
                check(result.deletedPaymentIds.isEmpty() && result.deletedReceiptIds.isEmpty())
                check(result.payments.map { it.feeId }.toSet().size == result.payments.size)
                check(result.payments.map { it.receiptNumber }.toSet() == setOf(result.receipts.single().receiptNumber))
            }
            "reverse_payment" -> {
                check(result.payments.size == 1 && result.receipts.isEmpty() && result.reversals.size == 1)
                check(result.payments.single().status == "reversed")
            }
            "owner_edit_payment" -> {
                check(result.fees.size in 1..2 && result.payments.size == 1 && result.receipts.size == 1)
                check(result.reversals.isEmpty() && result.deletedPaymentIds.isEmpty() && result.deletedReceiptIds.isEmpty())
            }
            "owner_delete_payment" -> {
                check(result.fees.size == 1 && result.payments.isEmpty() && result.receipts.isEmpty())
                check(result.reversals.isEmpty() && result.deletedPaymentIds == listOf(request["paymentId"] as String))
            }
            "set_custom_monthly_fee" -> {
                check(result.payments.isEmpty() && result.receipts.isEmpty() && result.reversals.isEmpty())
                check(result.deletedPaymentIds.isEmpty() && result.deletedReceiptIds.isEmpty())
            }
            "update_student_admission_date" -> {
                check(result.payments.isEmpty() && result.receipts.isEmpty() && result.reversals.isEmpty())
                check(result.deletedPaymentIds.isEmpty() && result.deletedReceiptIds.isEmpty())
            }
            "reconcile_invalid_monthly_fees" -> {
                check(result.payments.isEmpty() && result.receipts.isEmpty() && result.reversals.isEmpty())
                check(result.deletedPaymentIds.isEmpty() && result.deletedReceiptIds.isEmpty())
                check(result.fees.all { it.status == "cancelled" && it.dueAmount == 0.0 })
            }
            else -> error("Unsupported ledger response action.")
        }

        val feesById = result.fees.associateBy { it.id }
        result.payments.forEach { payment ->
            val paymentFee = feesById[payment.feeId]
                ?: error("Ledger response payment references an unknown fee.")
            check(payment.studentId == paymentFee.studentId)
            if (action == "collect_grouped_payment") {
                check(payment.operationId?.startsWith("$operationId:") == true && payment.status == "completed")
            } else if (action !in setOf("reverse_payment", "owner_edit_payment")) {
                check(payment.operationId == operationId && payment.status == "completed")
            }
        }
        result.receipts.forEach { receipt ->
            val payment = result.payments.firstOrNull { it.id == receipt.paymentId }
                ?: error("Ledger response receipt references an unknown payment.")
            val receiptFee = feesById[receipt.feeId]
                ?: error("Ledger response receipt references an unknown fee.")
            check((action == "owner_edit_payment" || receipt.operationId == operationId) &&
                receipt.paymentId == payment.id &&
                receipt.feeId == payment.feeId &&
                receipt.studentId == receiptFee.studentId &&
                receipt.receiptNumber == payment.receiptNumber)
        }
        result.reversals.forEach { reversal ->
            val payment = result.payments.single()
            val reversalFee = feesById[reversal.feeId]
                ?: error("Ledger response reversal references an unknown fee.")
            check(reversal.operationId == operationId &&
                reversal.paymentId == payment.id &&
                reversal.feeId == payment.feeId &&
                reversal.studentId == reversalFee.studentId)
        }
    }

    private fun FinancialOperationResult.asCollectionResult(): FeeCollectionResult {
        val fee = fees.singleOrNull() ?: error("Updated fee was not returned by the ledger service.")
        val payment = payments.singleOrNull() ?: error("Payment was not returned by the ledger service.")
        val receipt = receipts.singleOrNull() ?: error("Receipt was not returned by the ledger service.")
        return FeeCollectionResult(payment.id, receipt.receiptNumber, fee)
    }
}
