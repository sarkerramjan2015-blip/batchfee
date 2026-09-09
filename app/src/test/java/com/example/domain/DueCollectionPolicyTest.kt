package com.batchfee.edu.domain

import com.batchfee.edu.data.models.FeeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DueCollectionPolicyTest {
    @Test
    fun virtualDuesHaveStableDistinctUiKeysAndCanonicalFeesUseTheirOwnIds() {
        val admission = fee(id = "", feeType = "admission_fee", period = "Admission", batchId = "batch-a")
        val june = fee(id = "", period = "Jun 2026", batchId = "batch-a")
        val july = fee(id = "", period = "Jul 2026", batchId = "batch-a")
        val saved = fee(id = "fee-123", period = "Jun 2026", batchId = "batch-a")

        assertEquals("virtual:admission_fee:batch-a:admission:", DueCollectionPolicy.selectionKey(admission))
        assertNotEquals(DueCollectionPolicy.selectionKey(june), DueCollectionPolicy.selectionKey(july))
        assertEquals("fee:fee-123", DueCollectionPolicy.selectionKey(saved))
    }

    @Test
    fun admissionDiscountQuoteUsesBaseAndPreservesAlreadyPaidAmount() {
        val admission = fee(id = "fee-admission", feeType = "admission_fee", base = 500.0, paid = 100.0)

        val quote = DueCollectionPolicy.quote(admission, requestedDiscountPercent = 10.0)

        assertEquals(50.0, quote.discountAmount, 0.0001)
        assertEquals(450.0, quote.totalAmount, 0.0001)
        assertEquals(350.0, quote.outstandingAmount, 0.0001)
        assertTrue(DueCollectionPolicy.requiresAdjustment(admission, quote))
    }

    @Test
    fun excessiveAdmissionDiscountCannotRewriteImmutablePaidAmount() {
        val admission = fee(id = "fee-admission", feeType = "admission_fee", base = 500.0, paid = 460.0)

        val error = runCatching { DueCollectionPolicy.quote(admission, requestedDiscountPercent = 10.0) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun displayOrderIsAdmissionThenCalendarMonthsAndNoAdjustmentWhenValuesMatch() {
        val admission = fee(id = "fee-admission", feeType = "admission_fee", period = "Admission")
        val june = fee(id = "fee-june", period = "Jun 2026")
        val august = fee(id = "fee-august", period = "Aug 2026")
        val existingDiscount = fee(
            id = "fee-discounted",
            feeType = "admission_fee",
            base = 500.0,
            discount = 50.0,
            total = 450.0
        )

        assertEquals(listOf(admission, june, august), listOf(august, june, admission)
            .sortedWith { first, second -> DueCollectionPolicy.compareForDisplay(first, second) })
        val unchangedQuote = DueCollectionPolicy.quote(existingDiscount, 10.0)
        assertFalse(DueCollectionPolicy.requiresAdjustment(existingDiscount, unchangedQuote))
    }

    private fun fee(
        id: String,
        feeType: String = "monthly_fee",
        period: String = "May 2026",
        batchId: String = "batch-a",
        base: Double = 1_000.0,
        discount: Double = 0.0,
        total: Double = base - discount,
        paid: Double = 0.0
    ) = FeeEntity(
        id = id,
        instituteId = "institute-a",
        studentId = "student-a",
        batchId = batchId,
        feePeriod = period,
        feeType = feeType,
        dueDateMs = 0L,
        baseAmount = base,
        discountAmount = discount,
        lateFeeAmount = 0.0,
        totalAmount = total,
        paidAmount = paid,
        dueAmount = total - paid,
        status = if (paid > 0.0) "partially_paid" else "unpaid",
        note = null,
        createdAtMs = 0L,
        updatedAtMs = 0L,
        cancelledAtMs = null
    )
}
