package com.batchfee.edu.data.repository

import androidx.room.withTransaction
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.FinanceSyncHelper
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.data.models.PaymentEntity
import com.batchfee.edu.data.models.ReceiptEntity
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

class FeeCollectionRepository(private val db: AppDatabase) {

    suspend fun createFee(
        instituteId: String,
        studentId: String,
        batchId: String?,
        feePeriod: String,
        feeType: String,
        dueDateMs: Long,
        baseAmount: Double,
        discountAmount: Double,
        lateFeeAmount: Double,
        note: String? = null,
        now: Long = System.currentTimeMillis()
    ): FeeEntity = db.withTransaction {
        val totalAmount = calculateTotal(baseAmount, discountAmount, lateFeeAmount)
        val fee = FeeEntity(
            id = UUID.randomUUID().toString(),
            instituteId = instituteId,
            studentId = studentId,
            batchId = batchId,
            feePeriod = feePeriod,
            feeType = normalizeFeeType(feeType),
            dueDateMs = dueDateMs,
            baseAmount = baseAmount,
            discountAmount = discountAmount,
            lateFeeAmount = lateFeeAmount,
            totalAmount = totalAmount,
            paidAmount = 0.0,
            dueAmount = totalAmount,
            status = statusForNewFee(totalAmount),
            note = note,
            createdAtMs = now,
            updatedAtMs = now,
            cancelledAtMs = null
        )
        FinanceSyncHelper.upsertFee(fee)
        db.feeDao().insertFee(fee)
        fee
    }

    suspend fun createFeeWithInitialPayment(
        instituteId: String,
        collectedByUserId: String,
        studentId: String,
        batchId: String?,
        feePeriod: String,
        feeType: String,
        dueDateMs: Long,
        baseAmount: Double,
        discountAmount: Double,
        lateFeeAmount: Double,
        collectedAmount: Double,
        paymentMethod: String,
        paymentDateMs: Long,
        note: String?,
        receiptText: String?,
        now: Long = System.currentTimeMillis()
    ): FeeCreationResult = db.withTransaction {
        val totalAmount = calculateTotal(baseAmount, discountAmount, lateFeeAmount)
        if (collectedAmount < 0.0) {
            throw IllegalArgumentException("Collected amount cannot be negative.")
        }
        if (collectedAmount - totalAmount > EPSILON) {
            throw IllegalArgumentException("Payment rejected - amount exceeds remaining due.")
        }

        val fee = FeeEntity(
            id = UUID.randomUUID().toString(),
            instituteId = instituteId,
            studentId = studentId,
            batchId = batchId,
            feePeriod = feePeriod,
            feeType = normalizeFeeType(feeType),
            dueDateMs = dueDateMs,
            baseAmount = baseAmount,
            discountAmount = discountAmount,
            lateFeeAmount = lateFeeAmount,
            totalAmount = totalAmount,
            paidAmount = 0.0,
            dueAmount = totalAmount,
            status = statusForNewFee(totalAmount),
            note = note,
            createdAtMs = now,
            updatedAtMs = now,
            cancelledAtMs = null
        )
        FinanceSyncHelper.upsertFee(fee)
        db.feeDao().insertFee(fee)

        if (collectedAmount <= 0.0) {
            FeeCreationResult(fee = fee)
        } else {
            val result = collectFromFee(
                fee = fee,
                instituteId = instituteId,
                collectedByUserId = collectedByUserId,
                amount = collectedAmount,
                paymentMethod = paymentMethod,
                paymentDateMs = paymentDateMs,
                note = note,
                receiptText = receiptText,
                now = now
            )
            FeeCreationResult(
                fee = result.updatedFee,
                paymentId = result.paymentId,
                receiptNumber = result.receiptNumber
            )
        }
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
        now: Long = System.currentTimeMillis()
    ): FeeCollectionResult = db.withTransaction {
        val fee = db.feeDao().getFeeById(feeId, instituteId)
            ?: throw IllegalArgumentException("Fee not found.")
        collectFromFee(
            fee = fee,
            instituteId = instituteId,
            collectedByUserId = collectedByUserId,
            amount = amount,
            paymentMethod = paymentMethod,
            paymentDateMs = paymentDateMs,
            note = note,
            receiptText = receiptText,
            now = now
        )
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
        now: Long = System.currentTimeMillis()
    ): FeeCollectionResult = db.withTransaction {
        val fee = db.feeDao().getFeeById(feeId, instituteId)
            ?: throw IllegalArgumentException("Fee not found.")
        if (fee.cancelledAtMs != null) {
            throw IllegalArgumentException("Fee is cancelled.")
        }
        if (newBaseAmount < 0.0) {
            throw IllegalArgumentException("Fee amount cannot be negative.")
        }
        if (discountPercent < 0.0 || discountPercent > 100.0) {
            throw IllegalArgumentException("Discount must be between 0 and 100%.")
        }

        val discountAmount = newBaseAmount * discountPercent / 100.0
        val totalAmount = calculateTotal(newBaseAmount, discountAmount, 0.0)
        if (totalAmount + EPSILON < fee.paidAmount) {
            throw IllegalArgumentException("Adjusted fee total cannot be less than already paid.")
        }
        val adjustedDue = (totalAmount - fee.paidAmount).coerceAtLeast(0.0)
        if (collectedAmount - adjustedDue > EPSILON) {
            throw IllegalArgumentException("Payment rejected - amount exceeds remaining due.")
        }

        val adjustedFee = fee.copy(
            baseAmount = newBaseAmount,
            discountAmount = discountAmount,
            lateFeeAmount = 0.0,
            totalAmount = totalAmount,
            feePeriod = feePeriod,
            dueAmount = adjustedDue,
            status = statusForAdjustedFee(adjustedDue, fee.paidAmount),
            updatedAtMs = now
        )
        FinanceSyncHelper.upsertFee(adjustedFee)
        db.feeDao().updateFee(adjustedFee)

        collectFromFee(
            fee = adjustedFee,
            instituteId = instituteId,
            collectedByUserId = collectedByUserId,
            amount = collectedAmount,
            paymentMethod = paymentMethod,
            paymentDateMs = now,
            note = note,
            receiptText = buildString {
                append("Receipt\n")
                append("Period: $feePeriod\n")
                append("Base: BDT $newBaseAmount\n")
                append("Discount (${discountPercent.toInt()}%): BDT ${"%.2f".format(discountAmount)}\n")
                append("Payable: BDT ${"%.2f".format(totalAmount)}\n")
                append("Collected Now: BDT ${"%.2f".format(collectedAmount)}")
            },
            now = now
        )
    }

    private suspend fun collectFromFee(
        fee: FeeEntity,
        instituteId: String,
        collectedByUserId: String,
        amount: Double,
        paymentMethod: String,
        paymentDateMs: Long,
        note: String?,
        receiptText: String?,
        now: Long
    ): FeeCollectionResult {
        if (fee.cancelledAtMs != null) {
            throw IllegalArgumentException("Fee is cancelled.")
        }
        if (amount <= 0.0) {
            throw IllegalArgumentException("Amount must be greater than zero.")
        }
        val remainingDue = fee.dueAmount.coerceAtLeast(0.0)
        if (remainingDue <= EPSILON) {
            throw IllegalArgumentException("Fee already settled.")
        }
        if (amount - remainingDue > EPSILON) {
            throw IllegalArgumentException("Payment rejected - amount exceeds remaining due.")
        }

        val normalizedAmount = if (abs(remainingDue - amount) <= EPSILON) remainingDue else amount
        val newDue = (remainingDue - normalizedAmount).let { if (it <= EPSILON) 0.0 else it }
        val newPaid = if (newDue <= EPSILON) fee.totalAmount else fee.paidAmount + normalizedAmount
        val updatedFee = fee.copy(
            paidAmount = newPaid,
            dueAmount = newDue,
            status = statusAfterPayment(newDue),
            updatedAtMs = now
        )
        FinanceSyncHelper.upsertFee(updatedFee)
        db.feeDao().updateFee(updatedFee)

        val paymentId = UUID.randomUUID().toString()
        val receiptNumber = "REC-$now"
        val payment = PaymentEntity(
            id = paymentId,
            instituteId = instituteId,
            feeId = fee.id,
            studentId = fee.studentId,
            amount = normalizedAmount,
            paymentMethod = paymentMethod.lowercase(),
            transactionId = null,
            receiptNumber = receiptNumber,
            paymentDateMs = paymentDateMs,
            collectedByUserId = collectedByUserId,
            status = "completed",
            note = note,
            createdAtMs = now,
            updatedAtMs = now
        )
        FinanceSyncHelper.upsertPayment(payment)
        db.paymentDao().insertPayment(payment)
        val receipt = ReceiptEntity(
            id = UUID.randomUUID().toString(),
            instituteId = instituteId,
            paymentId = paymentId,
            feeId = fee.id,
            studentId = fee.studentId,
            receiptNumber = receiptNumber,
            receiptDateMs = paymentDateMs,
            totalAmount = updatedFee.totalAmount,
            paidAmount = updatedFee.paidAmount,
            dueAmount = updatedFee.dueAmount,
            paymentMethod = paymentMethod.lowercase(),
            receiptText = receiptText ?: "Payment of $normalizedAmount received.",
            createdAtMs = now
        )
        FinanceSyncHelper.upsertReceipt(receipt)
        db.receiptDao().insertReceipt(receipt)
        return FeeCollectionResult(paymentId, receiptNumber, updatedFee)
    }

    private fun calculateTotal(baseAmount: Double, discountAmount: Double, lateFeeAmount: Double): Double {
        if (baseAmount < 0.0) {
            throw IllegalArgumentException("Fee amount cannot be negative.")
        }
        if (discountAmount < 0.0 || lateFeeAmount < 0.0) {
            throw IllegalArgumentException("Discount and late fee cannot be negative.")
        }
        val total = baseAmount - discountAmount + lateFeeAmount
        if (total < -EPSILON) {
            throw IllegalArgumentException("Total fee cannot be negative.")
        }
        return total.coerceAtLeast(0.0)
    }

    private fun statusForNewFee(dueAmount: Double): String =
        if (dueAmount <= EPSILON) "paid" else "unpaid"

    private fun statusForAdjustedFee(dueAmount: Double, paidAmount: Double): String =
        when {
            dueAmount <= EPSILON -> "paid"
            paidAmount > EPSILON -> "partially_paid"
            else -> "unpaid"
        }

    private fun statusAfterPayment(dueAmount: Double): String =
        if (dueAmount <= EPSILON) "paid" else "partially_paid"

    private fun normalizeFeeType(feeType: String): String =
        feeType.trim().lowercase().ifBlank { "monthly_fee" }

    private companion object {
        const val EPSILON = 0.001
    }
}

