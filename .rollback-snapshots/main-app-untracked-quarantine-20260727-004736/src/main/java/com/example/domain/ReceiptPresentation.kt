package com.batchfee.edu.domain

import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.PaymentEntity
import com.batchfee.edu.data.models.ReceiptEntity
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.data.models.UserEntity

/**
 * A render-only view of a receipt.  New receipts prefer immutable values saved
 * at collection time; legacy receipts may use only matching institute records
 * as a best-effort fallback and otherwise disclose that a value is unavailable.
 */
data class ReceiptDocument(
    val instituteName: String,
    val instituteAddress: String?,
    val institutePhone: String?,
    val instituteEmail: String?,
    val instituteLogoUri: String?,
    val receiptNumber: String,
    val receiptDateMs: Long,
    val status: String,
    val studentName: String?,
    val studentCode: String?,
    val studentPhone: String?,
    val batchName: String?,
    val feePeriod: String?,
    val feeType: String?,
    val grossAmount: Double?,
    val discountAmount: Double?,
    val payableAmount: Double,
    val receivedAmount: Double?,
    val remainingDue: Double,
    val paymentMethod: String,
    val paymentReference: String?,
    val remarks: String?,
    val collectorName: String?,
    val collectorRole: String?,
    val collectorStaffCode: String?,
    val isLegacy: Boolean,
    val allocations: List<ReceiptAllocation> = emptyList()
)

/** One exact stored payment-to-fee allocation on a consolidated receipt. */
data class ReceiptAllocation(
    val feePeriod: String?,
    val status: String,
    val grossAmount: Double?,
    val discountAmount: Double?,
    val payableAmount: Double,
    val receivedAmount: Double?,
    val remainingDue: Double
)

object ReceiptPresentation {
    fun create(
        receipt: ReceiptEntity,
        payment: PaymentEntity?,
        institute: InstituteEntity?,
        student: StudentEntity?,
        batch: BatchEntity?,
        fee: FeeEntity?,
        collector: UserEntity?,
        staff: StaffEntity?
    ): ReceiptDocument {
        require(payment == null || payment.instituteId == receipt.instituteId) {
            "Payment must belong to the receipt institute."
        }
        require(institute == null || institute.id == receipt.instituteId) {
            "Institute must match the receipt institute."
        }
        require(student == null || student.instituteId == receipt.instituteId) {
            "Student must belong to the receipt institute."
        }
        require(batch == null || batch.instituteId == receipt.instituteId) {
            "Batch must belong to the receipt institute."
        }
        require(fee == null || fee.instituteId == receipt.instituteId) {
            "Fee must belong to the receipt institute."
        }

        val hasSnapshot = receipt.instituteNameSnapshot != null ||
            receipt.studentNameSnapshot != null || receipt.feePeriodSnapshot != null
        return ReceiptDocument(
            instituteName = receipt.instituteNameSnapshot.orNonBlank(institute?.name) ?: "Institute",
            instituteAddress = receipt.instituteAddressSnapshot.orNonBlank(institute?.address),
            institutePhone = receipt.institutePhoneSnapshot.orNonBlank(institute?.phone),
            instituteEmail = receipt.instituteEmailSnapshot.orNonBlank(institute?.email),
            instituteLogoUri = receipt.instituteLogoUriSnapshot.orNonBlank(institute?.profilePhotoUri),
            receiptNumber = receipt.receiptNumber.ifBlank { "Not available" },
            receiptDateMs = receipt.receiptDateMs,
            status = displayStatus(receipt.paymentStatusSnapshot.orNonBlank(payment?.status)),
            studentName = receipt.studentNameSnapshot.orNonBlank(student?.fullName),
            studentCode = receipt.studentCodeSnapshot.orNonBlank(student?.studentCode),
            studentPhone = receipt.studentPhoneSnapshot.orNonBlank(student?.phone ?: student?.guardianPhone),
            batchName = receipt.batchNameSnapshot.orNonBlank(batch?.name),
            feePeriod = receipt.feePeriodSnapshot.orNonBlank(fee?.feePeriod),
            feeType = receipt.feeTypeSnapshot.orNonBlank(fee?.feeType),
            grossAmount = receipt.grossAmountSnapshot ?: fee?.baseAmount,
            discountAmount = receipt.discountAmountSnapshot ?: fee?.discountAmount,
            payableAmount = receipt.totalAmount,
            receivedAmount = payment?.amount,
            remainingDue = receipt.dueAmount,
            paymentMethod = receipt.paymentMethod.ifBlank { payment?.paymentMethod.orEmpty() },
            paymentReference = receipt.paymentReferenceSnapshot.orNonBlank(payment?.transactionId),
            remarks = receipt.paymentNoteSnapshot.orNonBlank(payment?.note),
            collectorName = receipt.collectorNameSnapshot.orNonBlank(collector?.name ?: staff?.fullName),
            collectorRole = receipt.collectorRoleSnapshot.orNonBlank(collector?.role ?: staff?.roleTitle),
            collectorStaffCode = receipt.collectorStaffCodeSnapshot.orNonBlank(staff?.staffCode),
            isLegacy = !hasSnapshot
        )
    }

    /**
     * Builds a read-only consolidated view from the individual receipts created
     * by one Problem 06 collection action. It performs no financial writes.
     */
    fun consolidate(documents: List<ReceiptDocument>): ReceiptDocument {
        require(documents.isNotEmpty()) { "At least one receipt is required." }
        require(documents.map { it.receiptNumber }.distinct().size == 1) {
            "Only receipts from the same collection action can be consolidated."
        }
        if (documents.size == 1) return documents.single()

        val primary = documents.first()
        val allocations = documents.map { document ->
            ReceiptAllocation(
                feePeriod = document.feePeriod,
                status = document.status,
                grossAmount = document.grossAmount,
                discountAmount = document.discountAmount,
                payableAmount = document.payableAmount,
                receivedAmount = document.receivedAmount,
                remainingDue = document.remainingDue
            )
        }
        return primary.copy(
            feePeriod = "Multiple exact months",
            grossAmount = documents.sumNullable { it.grossAmount },
            discountAmount = documents.sumNullable { it.discountAmount },
            payableAmount = documents.sumOf { it.payableAmount },
            receivedAmount = documents.sumNullable { it.receivedAmount },
            remainingDue = documents.sumOf { it.remainingDue },
            status = consolidatedStatus(documents),
            isLegacy = documents.any { it.isLegacy },
            allocations = allocations
        )
    }

    private fun displayStatus(status: String?): String = when (status?.trim()?.lowercase()) {
        "completed", "paid" -> "Paid"
        "partially_paid", "partial" -> "Partially Paid"
        "reversed" -> "Reversed"
        "refunded" -> "Refunded"
        "partially_refunded" -> "Partially Refunded"
        "cancelled", "canceled" -> "Cancelled"
        "reissued" -> "Reissued"
        null, "" -> "Not available"
        else -> status.trim().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun String?.orNonBlank(fallback: String?): String? =
        this?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }

    private fun List<ReceiptDocument>.sumNullable(value: (ReceiptDocument) -> Double?): Double? =
        if (all { value(it) != null }) sumOf { value(it) ?: 0.0 } else null

    private fun consolidatedStatus(documents: List<ReceiptDocument>): String = when {
        documents.all { it.status == "Paid" } -> "Paid"
        documents.any { it.status == "Partially Paid" || it.remainingDue > 0.0 } -> "Partially Paid"
        else -> documents.first().status
    }
}
