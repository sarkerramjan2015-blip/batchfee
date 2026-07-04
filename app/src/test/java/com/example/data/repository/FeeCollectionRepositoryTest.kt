package com.batchfee.edu.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.FeeEntity
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
    private lateinit var repository: FeeCollectionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FeeCollectionRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createFeeCreatesUnpaidDue() = runTest {
        val fee = createFee(amount = 1_000.0)

        val stored = db.feeDao().getFeeById(fee.id, INSTITUTE_ID)
        assertNotNull(stored)
        assertEquals(1_000.0, stored!!.totalAmount, MONEY_DELTA)
        assertEquals(0.0, stored.paidAmount, MONEY_DELTA)
        assertEquals(1_000.0, stored.dueAmount, MONEY_DELTA)
        assertEquals("unpaid", stored.status)
        assertNull(stored.cancelledAtMs)
    }

    @Test
    fun partialPaymentUpdatesDueAndCreatesPaymentReceipt() = runTest {
        val fee = createFee(amount = 1_000.0)

        val result = repository.collectPayment(
            instituteId = INSTITUTE_ID,
            collectedByUserId = USER_ID,
            feeId = fee.id,
            amount = 400.0,
            paymentMethod = "Cash",
            now = 2_000L
        )

        val updated = db.feeDao().getFeeById(fee.id, INSTITUTE_ID)!!
        assertEquals(400.0, updated.paidAmount, MONEY_DELTA)
        assertEquals(600.0, updated.dueAmount, MONEY_DELTA)
        assertEquals("partially_paid", updated.status)
        assertEquals(1, db.paymentDao().getAllPaymentsOnce(INSTITUTE_ID).size)
        assertNotNull(db.receiptDao().getReceiptByPaymentIdOnce(INSTITUTE_ID, result.paymentId))
    }

    @Test
    fun fullPaymentSettlesFee() = runTest {
        val fee = createFee(amount = 1_000.0)

        repository.collectPayment(
            instituteId = INSTITUTE_ID,
            collectedByUserId = USER_ID,
            feeId = fee.id,
            amount = 1_000.0,
            paymentMethod = "cash",
            now = 3_000L
        )

        val updated = db.feeDao().getFeeById(fee.id, INSTITUTE_ID)!!
        assertEquals(1_000.0, updated.paidAmount, MONEY_DELTA)
        assertEquals(0.0, updated.dueAmount, MONEY_DELTA)
        assertEquals("paid", updated.status)
    }

    @Test
    fun overpaymentIsRejectedAndDoesNotCreateRecords() = runTest {
        val fee = createFee(amount = 1_000.0)

        assertIllegalArgument {
            repository.collectPayment(
                instituteId = INSTITUTE_ID,
                collectedByUserId = USER_ID,
                feeId = fee.id,
                amount = 1_001.0,
                paymentMethod = "cash",
                now = 4_000L
            )
        }

        val unchanged = db.feeDao().getFeeById(fee.id, INSTITUTE_ID)!!
        assertEquals(0.0, unchanged.paidAmount, MONEY_DELTA)
        assertEquals(1_000.0, unchanged.dueAmount, MONEY_DELTA)
        assertEquals(0, db.paymentDao().getAllPaymentsOnce(INSTITUTE_ID).size)
    }

    @Test
    fun receiptUsesUpdatedFeeAmounts() = runTest {
        val fee = createFee(amount = 1_000.0)

        val result = repository.collectPayment(
            instituteId = INSTITUTE_ID,
            collectedByUserId = USER_ID,
            feeId = fee.id,
            amount = 250.0,
            paymentMethod = "cash",
            receiptText = "Custom receipt",
            now = 5_000L
        )

        val receipt = db.receiptDao().getReceiptByPaymentIdOnce(INSTITUTE_ID, result.paymentId)
        assertNotNull(receipt)
        assertEquals("REC-5000", receipt!!.receiptNumber)
        assertEquals(250.0, receipt.paidAmount, MONEY_DELTA)
        assertEquals(750.0, receipt.dueAmount, MONEY_DELTA)
        assertEquals("Custom receipt", receipt.receiptText)
    }

    @Test
    fun createFeeWithInitialPaymentCalculatesDueAndReceipt() = runTest {
        val result = repository.createFeeWithInitialPayment(
            instituteId = INSTITUTE_ID,
            collectedByUserId = USER_ID,
            studentId = STUDENT_ID,
            batchId = BATCH_ID,
            feePeriod = "Jun 2026",
            feeType = "monthly_fee",
            dueDateMs = 1_000L,
            baseAmount = 1_000.0,
            discountAmount = 100.0,
            lateFeeAmount = 50.0,
            collectedAmount = 300.0,
            paymentMethod = "cash",
            paymentDateMs = 6_000L,
            note = null,
            receiptText = "Initial payment",
            now = 6_000L
        )

        val fee = db.feeDao().getFeeById(result.fee.id, INSTITUTE_ID)!!
        assertEquals(950.0, fee.totalAmount, MONEY_DELTA)
        assertEquals(300.0, fee.paidAmount, MONEY_DELTA)
        assertEquals(650.0, fee.dueAmount, MONEY_DELTA)
        assertEquals("partially_paid", fee.status)
        assertNotNull(result.paymentId)
        assertNotNull(result.paymentId?.let { db.receiptDao().getReceiptByPaymentIdOnce(INSTITUTE_ID, it) })
    }

    @Test
    fun unifiedCollectActiveDueFilterIncludesDirectNullableFees() = runTest {
        val directFee = createFee(amount = 800.0, batchId = null)

        val activeDues = db.feeDao().getAllFeesOnce(INSTITUTE_ID)
            .filter { it.studentId == STUDENT_ID && it.dueAmount > 0.0 && it.cancelledAtMs == null }

        assertTrue(activeDues.any { it.id == directFee.id })
        repository.collectPayment(
            instituteId = INSTITUTE_ID,
            collectedByUserId = USER_ID,
            feeId = directFee.id,
            amount = 800.0,
            paymentMethod = "cash",
            now = 7_000L
        )
        assertEquals("paid", db.feeDao().getFeeById(directFee.id, INSTITUTE_ID)!!.status)
    }

    @Test
    fun studentProfileCollectionPathsUseSameOverpaymentGuard() = runTest {
        val existingFee = createFee(amount = 500.0, batchId = null)

        repository.collectPayment(
            instituteId = INSTITUTE_ID,
            collectedByUserId = USER_ID,
            feeId = existingFee.id,
            amount = 200.0,
            paymentMethod = "cash",
            paymentDateMs = 8_000L,
            receiptText = "Profile existing fee",
            now = 8_000L
        )
        assertEquals(300.0, db.feeDao().getFeeById(existingFee.id, INSTITUTE_ID)!!.dueAmount, MONEY_DELTA)

        assertIllegalArgument {
            repository.createFeeWithInitialPayment(
                instituteId = INSTITUTE_ID,
                collectedByUserId = USER_ID,
                studentId = STUDENT_ID,
                batchId = null,
                feePeriod = "Jul 2026",
                feeType = "monthly_fee",
                dueDateMs = 8_500L,
                baseAmount = 300.0,
                discountAmount = 0.0,
                lateFeeAmount = 0.0,
                collectedAmount = 301.0,
                paymentMethod = "cash",
                paymentDateMs = 8_500L,
                note = null,
                receiptText = "Profile new fee",
                now = 8_500L
            )
        }
    }

    private suspend fun createFee(
        amount: Double,
        batchId: String? = BATCH_ID
    ): FeeEntity = repository.createFee(
        instituteId = INSTITUTE_ID,
        studentId = STUDENT_ID,
        batchId = batchId,
        feePeriod = "May 2026",
        feeType = "monthly_fee",
        dueDateMs = 1_000L,
        baseAmount = amount,
        discountAmount = 0.0,
        lateFeeAmount = 0.0,
        now = 1_000L
    )

    private suspend fun assertIllegalArgument(block: suspend () -> Unit) {
        var thrown: IllegalArgumentException? = null
        try {
            block()
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertNotNull(thrown)
    }

    private companion object {
        const val INSTITUTE_ID = "inst-1"
        const val USER_ID = "user-1"
        const val STUDENT_ID = "student-1"
        const val BATCH_ID = "batch-1"
        const val MONEY_DELTA = 0.0001
    }
}

