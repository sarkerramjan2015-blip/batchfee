package com.batchfee.edu.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.data.models.PaymentEntity
import com.batchfee.edu.data.models.PaymentReversalEntity
import com.batchfee.edu.data.models.ReceiptEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeeCollectionRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var gateway: ScriptedLedgerGateway
    private lateinit var repository: FeeCollectionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = ScriptedLedgerGateway()
        repository = FeeCollectionRepository(db, gateway)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun trustedCanonicalResultIsAppliedAndOutboxCompletes() = runTest {
        gateway.responder = { request ->
            FinancialOperationResult(
                operationId = request.getValue("operationId") as String,
                action = "create_fee",
                fees = listOf(fee(id = "canonical-fee", businessKey = "server-key"))
            )
        }

        val created = repository.createFee(
            instituteId = INSTITUTE_ID,
            studentId = STUDENT_ID,
            batchId = BATCH_ID,
            feePeriod = "May 2026",
            feeType = "monthly_fee",
            dueDateMs = 1_000L,
            baseAmount = 1_000.0,
            discountAmount = 0.0,
            lateFeeAmount = 0.0,
            now = 1_000L,
            operationId = OPERATION_ID
        )

        assertEquals("canonical-fee", created.id)
        assertEquals("server-key", db.feeDao().getFeeById(created.id, INSTITUTE_ID)?.businessKey)
        assertEquals("completed", db.financialLedgerDao().getOperation(INSTITUTE_ID, OPERATION_ID)?.status)
        assertEquals(OPERATION_ID, gateway.requests.single()["operationId"])
    }

    @Test
    fun transientCloudFailureLeavesNoLocalLedgerAndReplayUsesSameOperationId() = runTest {
        gateway.responder = { throw IllegalStateException("offline") }

        assertThrowsSuspend<FinancialOperationPendingException> {
            repository.createFee(
                instituteId = INSTITUTE_ID,
                studentId = STUDENT_ID,
                batchId = BATCH_ID,
                feePeriod = "June 2026",
                feeType = "monthly_fee",
                dueDateMs = 2_000L,
                baseAmount = 900.0,
                discountAmount = 0.0,
                lateFeeAmount = 0.0,
                now = 2_000L,
                operationId = OPERATION_ID
            )
        }

        assertNull(db.feeDao().getFeeById("canonical-fee", INSTITUTE_ID))
        assertEquals("pending", db.financialLedgerDao().getOperation(INSTITUTE_ID, OPERATION_ID)?.status)

        gateway.responder = { request ->
            FinancialOperationResult(
                operationId = request.getValue("operationId") as String,
                action = "create_fee",
                fees = listOf(fee(id = "canonical-fee"))
            )
        }
        repository.replayPendingOperations(INSTITUTE_ID)

        assertNotNull(db.feeDao().getFeeById("canonical-fee", INSTITUTE_ID))
        assertEquals("completed", db.financialLedgerDao().getOperation(INSTITUTE_ID, OPERATION_ID)?.status)
        assertEquals(listOf(OPERATION_ID, OPERATION_ID), gateway.requests.map { it["operationId"] })
    }

    @Test
    fun roomApplyIsAtomicWhenAChildRecordConflicts() = runTest {
        val originalFee = fee(id = "fee-atomic")
        db.feeDao().insertFee(originalFee)
        db.paymentDao().insertPayment(payment(id = "existing-payment", operationId = OPERATION_ID))
        gateway.responder = {
            FinancialOperationResult(
                operationId = OPERATION_ID,
                action = "collect_payment",
                fees = listOf(originalFee.copy(paidAmount = 400.0, dueAmount = 600.0, status = "partially_paid")),
                payments = listOf(payment(id = "conflicting-payment", operationId = OPERATION_ID)),
                receipts = listOf(receipt(
                    id = "must-roll-back",
                    paymentId = "conflicting-payment",
                    operationId = OPERATION_ID
                ))
            )
        }

        assertThrowsSuspend<FinancialOperationPendingException> {
            repository.collectPayment(
                instituteId = INSTITUTE_ID,
                collectedByUserId = USER_ID,
                feeId = originalFee.id,
                amount = 400.0,
                paymentMethod = "cash",
                now = 3_000L,
                operationId = OPERATION_ID
            )
        }

        val unchanged = db.feeDao().getFeeById(originalFee.id, INSTITUTE_ID)!!
        assertEquals(0.0, unchanged.paidAmount, MONEY_DELTA)
        assertNull(db.receiptDao().getReceiptByPaymentIdOnce(INSTITUTE_ID, "conflicting-payment"))
        assertEquals("pending", db.financialLedgerDao().getOperation(INSTITUTE_ID, OPERATION_ID)?.status)
    }

    @Test
    fun reversalRetainsPaymentAndReceiptAndStoresAuditRecord() = runTest {
        val postedFee = fee(id = "fee-reverse").copy(
            paidAmount = 400.0,
            dueAmount = 600.0,
            status = "partially_paid"
        )
        val postedPayment = payment(id = "payment-reverse", feeId = postedFee.id)
        val postedReceipt = receipt(id = "receipt-reverse", paymentId = postedPayment.id, feeId = postedFee.id)
        db.feeDao().insertFee(postedFee)
        db.paymentDao().insertPayment(postedPayment)
        db.receiptDao().insertReceipt(postedReceipt)
        gateway.responder = {
            FinancialOperationResult(
                operationId = OPERATION_ID,
                action = "reverse_payment",
                fees = listOf(postedFee.copy(paidAmount = 0.0, dueAmount = 1_000.0, status = "unpaid")),
                payments = listOf(postedPayment.copy(status = "reversed")),
                reversals = listOf(reversal(postedPayment))
            )
        }

        repository.reversePayment(
            paymentId = postedPayment.id,
            instituteId = INSTITUTE_ID,
            reason = "Duplicate collection",
            now = 4_000L,
            operationId = OPERATION_ID
        )

        assertEquals("reversed", db.paymentDao().getPaymentById(postedPayment.id, INSTITUTE_ID)?.status)
        assertNotNull(db.receiptDao().getReceiptByPaymentIdOnce(INSTITUTE_ID, postedPayment.id))
        assertNotNull(db.financialLedgerDao().getReversalForPayment(INSTITUTE_ID, postedPayment.id))
        assertEquals(1_000.0, db.feeDao().getFeeById(postedFee.id, INSTITUTE_ID)?.dueAmount ?: -1.0, MONEY_DELTA)
    }

    @Test
    fun trustedRejectionIsNotRetriedAutomatically() = runTest {
        gateway.responder = {
            throw FinancialOperationRejectedException("Duplicate fee")
        }

        assertThrowsSuspend<FinancialOperationRejectedException> {
            repository.createFee(
                instituteId = INSTITUTE_ID,
                studentId = STUDENT_ID,
                batchId = BATCH_ID,
                feePeriod = "May 2026",
                feeType = "monthly_fee",
                dueDateMs = 1_000L,
                baseAmount = 1_000.0,
                discountAmount = 0.0,
                lateFeeAmount = 0.0,
                operationId = OPERATION_ID
            )
        }

        assertEquals("failed", db.financialLedgerDao().getOperation(INSTITUTE_ID, OPERATION_ID)?.status)
        repository.replayPendingOperations(INSTITUTE_ID)
        assertEquals(1, gateway.requests.size)
    }

    private fun fee(id: String, businessKey: String? = "business-key") = FeeEntity(
        id = id,
        instituteId = INSTITUTE_ID,
        studentId = STUDENT_ID,
        batchId = BATCH_ID,
        feePeriod = "May 2026",
        feeType = "monthly_fee",
        dueDateMs = 1_000L,
        baseAmount = 1_000.0,
        discountAmount = 0.0,
        lateFeeAmount = 0.0,
        totalAmount = 1_000.0,
        paidAmount = 0.0,
        dueAmount = 1_000.0,
        status = "unpaid",
        note = null,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        cancelledAtMs = null,
        businessKey = businessKey,
        ledgerVersion = 1
    )

    private fun payment(
        id: String,
        feeId: String = "fee-atomic",
        operationId: String? = "payment-operation"
    ) = PaymentEntity(
        id = id,
        instituteId = INSTITUTE_ID,
        feeId = feeId,
        studentId = STUDENT_ID,
        amount = 400.0,
        paymentMethod = "cash",
        transactionId = null,
        receiptNumber = "REC-0000000001",
        paymentDateMs = 2_000L,
        collectedByUserId = USER_ID,
        status = "completed",
        note = null,
        createdAtMs = 2_000L,
        updatedAtMs = 2_000L,
        operationId = operationId,
        ledgerVersion = 1
    )

    private fun receipt(
        id: String,
        paymentId: String,
        feeId: String = "fee-atomic",
        operationId: String = "receipt-operation-$id"
    ) = ReceiptEntity(
        id = id,
        instituteId = INSTITUTE_ID,
        paymentId = paymentId,
        feeId = feeId,
        studentId = STUDENT_ID,
        receiptNumber = "REC-0000000001",
        receiptDateMs = 2_000L,
        totalAmount = 1_000.0,
        paidAmount = 400.0,
        dueAmount = 600.0,
        paymentMethod = "cash",
        receiptText = null,
        createdAtMs = 2_000L,
        operationId = operationId,
        ledgerVersion = 1
    )

    private fun reversal(payment: PaymentEntity) = PaymentReversalEntity(
        id = "reversal-${payment.id}",
        instituteId = INSTITUTE_ID,
        paymentId = payment.id,
        feeId = payment.feeId,
        studentId = STUDENT_ID,
        amount = payment.amount,
        receiptNumber = payment.receiptNumber,
        reason = "Duplicate collection",
        reversedByUserId = USER_ID,
        reversedAtMs = 4_000L,
        operationId = OPERATION_ID,
        ledgerVersion = 1
    )

    private suspend inline fun <reified T : Exception> assertThrowsSuspend(
        crossinline block: suspend () -> Unit
    ) {
        var thrown: Exception? = null
        try {
            block()
        } catch (error: Exception) {
            thrown = error
        }
        assertTrue("Expected ${T::class.java.simpleName}, got ${thrown?.javaClass?.simpleName}", thrown is T)
    }

    private companion object {
        const val INSTITUTE_ID = "inst-1"
        const val USER_ID = "user-1"
        const val STUDENT_ID = "student-1"
        const val BATCH_ID = "batch-1"
        const val OPERATION_ID = "operation-000001"
        const val MONEY_DELTA = 0.0001
    }
}

private class ScriptedLedgerGateway : FinancialLedgerGateway {
    val requests = mutableListOf<Map<String, Any?>>()
    var responder: suspend (Map<String, Any?>) -> FinancialOperationResult = {
        error("No ledger response configured")
    }

    override suspend fun commit(request: Map<String, Any?>): FinancialOperationResult {
        requests += request
        return responder(request)
    }
}
