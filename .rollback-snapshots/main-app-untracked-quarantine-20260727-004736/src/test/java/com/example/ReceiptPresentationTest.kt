package com.batchfee.edu

import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.PaymentEntity
import com.batchfee.edu.data.models.ReceiptEntity
import com.batchfee.edu.domain.ReceiptPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptPresentationTest {
    @Test
    fun snapshotKeepsReceiptIndependentFromLaterInstituteChanges() {
        val document = ReceiptPresentation.create(
            receipt = receipt(
                instituteNameSnapshot = "ICT TOPPERS",
                studentNameSnapshot = "Shajeda Akter Azmi",
                feePeriodSnapshot = "Jul 2026",
                grossAmountSnapshot = 1_000.0,
                discountAmountSnapshot = 100.0,
                collectorNameSnapshot = "Noman Sir"
            ),
            payment = payment(amount = 900.0),
            institute = institute(name = "Renamed Institute"),
            student = null,
            batch = null,
            fee = null,
            collector = null,
            staff = null
        )

        assertEquals("ICT TOPPERS", document.instituteName)
        assertEquals("Shajeda Akter Azmi", document.studentName)
        assertEquals("Jul 2026", document.feePeriod)
        assertEquals(1_000.0, document.grossAmount!!, 0.0001)
        assertEquals(100.0, document.discountAmount!!, 0.0001)
        assertEquals(900.0, document.receivedAmount!!, 0.0001)
        assertEquals("Noman Sir", document.collectorName)
        assertFalse(document.isLegacy)
    }

    @Test
    fun missingLogoUsesNoFakeBrandAsset() {
        val document = ReceiptPresentation.create(
            receipt = receipt(instituteNameSnapshot = "ICT TOPPERS", instituteLogoUriSnapshot = null),
            payment = payment(),
            institute = institute(profilePhotoUri = null),
            student = null, batch = null, fee = null, collector = null, staff = null
        )

        assertEquals("ICT TOPPERS", document.instituteName)
        assertNull(document.instituteLogoUri)
    }

    @Test
    fun exactProblemSixAllocationPeriodIsPreserved() {
        val document = ReceiptPresentation.create(
            receipt = receipt(feePeriodSnapshot = "Jul 2026", feeTypeSnapshot = "monthly_fee"),
            payment = payment(amount = 600.0),
            institute = institute(), student = null, batch = null, fee = null, collector = null, staff = null
        )

        assertEquals("Jul 2026", document.feePeriod)
        assertEquals("monthly_fee", document.feeType)
        assertEquals(600.0, document.receivedAmount!!, 0.0001)
    }

    @Test
    fun partialDiscountReferenceAndStatusUsePersistedPaymentData() {
        val document = ReceiptPresentation.create(
            receipt = receipt(discountAmountSnapshot = 100.0, paymentStatusSnapshot = "partially_paid"),
            payment = payment(amount = 600.0, reference = "TXN-123", note = "bKash payment"),
            institute = institute(), student = null, batch = null, fee = null, collector = null, staff = null
        )

        assertEquals("Partially Paid", document.status)
        assertEquals(100.0, document.discountAmount!!, 0.0001)
        assertEquals("TXN-123", document.paymentReference)
        assertEquals("bKash payment", document.remarks)
        assertEquals(400.0, document.remainingDue, 0.0001)
    }

    @Test
    fun legacyReceiptUsesOnlyMatchingInstituteFallback() {
        val document = ReceiptPresentation.create(
            receipt = receipt(),
            payment = payment(),
            institute = institute(name = "ICT TOPPERS"),
            student = null, batch = null, fee = null, collector = null, staff = null
        )

        assertTrue(document.isLegacy)
        assertEquals("ICT TOPPERS", document.instituteName)
    }

    @Test
    fun consolidatedReceiptUsesExactAllocationsAndStoredPaymentTotal() {
        val consolidated = ReceiptPresentation.consolidate(
            listOf(
                document(period = "Jun 2026", received = 1_000.0, payable = 1_000.0, remaining = 0.0, status = "completed"),
                document(period = "Jul 2026", received = 600.0, payable = 1_000.0, remaining = 400.0, status = "partially_paid"),
                document(period = "Aug 2026", received = 1_000.0, payable = 1_000.0, remaining = 0.0, status = "completed")
            )
        )

        assertEquals("Multiple exact months", consolidated.feePeriod)
        assertEquals(3, consolidated.allocations.size)
        assertEquals(listOf("Jun 2026", "Jul 2026", "Aug 2026"), consolidated.allocations.map { it.feePeriod })
        assertEquals(2_600.0, consolidated.receivedAmount!!, 0.0001)
        assertEquals(3_000.0, consolidated.payableAmount, 0.0001)
        assertEquals(400.0, consolidated.remainingDue, 0.0001)
        assertEquals("Partially Paid", consolidated.status)
        assertEquals(600.0, consolidated.allocations[1].receivedAmount!!, 0.0001)
        assertEquals(400.0, consolidated.allocations[1].remainingDue, 0.0001)
    }

    private fun document(
        period: String,
        received: Double,
        payable: Double,
        remaining: Double,
        status: String
    ) = ReceiptPresentation.create(
        receipt = receipt(
            feePeriodSnapshot = period,
            grossAmountSnapshot = payable,
            paymentStatusSnapshot = status,
            totalAmount = payable,
            dueAmount = remaining
        ),
        payment = payment(amount = received),
        institute = institute(), student = null, batch = null, fee = null, collector = null, staff = null
    )

    private fun receipt(
        instituteNameSnapshot: String? = null,
        instituteLogoUriSnapshot: String? = null,
        studentNameSnapshot: String? = null,
        feePeriodSnapshot: String? = null,
        feeTypeSnapshot: String? = null,
        grossAmountSnapshot: Double? = null,
        discountAmountSnapshot: Double? = null,
        collectorNameSnapshot: String? = null,
        paymentStatusSnapshot: String? = null,
        totalAmount: Double = 900.0,
        dueAmount: Double = 400.0
    ) = ReceiptEntity(
        id = "receipt", instituteId = "inst", paymentId = "payment", feeId = "fee", studentId = "student",
        receiptNumber = "REC-100", receiptDateMs = 1_784_905_200_000L,
        totalAmount = totalAmount, paidAmount = totalAmount - dueAmount, dueAmount = dueAmount,
        paymentMethod = "bkash", receiptText = null, createdAtMs = 1_784_905_200_000L,
        instituteNameSnapshot = instituteNameSnapshot,
        instituteLogoUriSnapshot = instituteLogoUriSnapshot,
        studentNameSnapshot = studentNameSnapshot,
        feePeriodSnapshot = feePeriodSnapshot,
        feeTypeSnapshot = feeTypeSnapshot,
        grossAmountSnapshot = grossAmountSnapshot,
        discountAmountSnapshot = discountAmountSnapshot,
        collectorNameSnapshot = collectorNameSnapshot,
        paymentStatusSnapshot = paymentStatusSnapshot
    )

    private fun payment(amount: Double = 900.0, reference: String? = null, note: String? = null) = PaymentEntity(
        id = "payment", instituteId = "inst", feeId = "fee", studentId = "student", amount = amount,
        paymentMethod = "bkash", transactionId = reference, receiptNumber = "REC-100",
        paymentDateMs = 1_784_905_200_000L, collectedByUserId = "collector", status = "completed",
        note = note, createdAtMs = 1_784_905_200_000L, updatedAtMs = 1_784_905_200_000L
    )

    private fun institute(name: String = "ICT TOPPERS", profilePhotoUri: String? = null) = InstituteEntity(
        id = "inst", name = name, currentPlanId = "basic", subscriptionStatus = "active",
        trialStartDateMs = 0L, trialEndDateMs = 0L, currentPeriodEndMs = 0L, createdAtMs = 0L,
        profilePhotoUri = profilePhotoUri
    )
}
