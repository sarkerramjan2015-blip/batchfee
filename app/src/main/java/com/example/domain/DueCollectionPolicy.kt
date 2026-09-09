package com.batchfee.edu.domain

import com.batchfee.edu.data.models.FeeEntity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * Pure collection rules for the due-fee picker. A selection key is a UI-only
 * identifier: it must never be sent to Firestore as a fee ID.
 */
object DueCollectionPolicy {
    private const val EPSILON = 0.001

    data class Quote(
        val baseAmount: Double,
        val discountPercent: Double,
        val discountAmount: Double,
        val totalAmount: Double,
        val alreadyPaid: Double,
        val outstandingAmount: Double
    )

    fun selectionKey(fee: FeeEntity): String =
        fee.id.takeIf { it.isNotBlank() }?.let { "fee:$it" }
            ?: listOf(
                "virtual",
                normalized(fee.feeType),
                normalized(fee.batchId.orEmpty()),
                normalized(fee.feePeriod),
                normalized(fee.sourceId.orEmpty())
            ).joinToString(":")

    fun currentDiscountPercent(fee: FeeEntity): Double =
        if (fee.baseAmount <= EPSILON) 0.0
        else ((fee.discountAmount / fee.baseAmount) * 100.0).coerceIn(0.0, 100.0)

    /**
     * This release deliberately limits live fee adjustment to one-time fees
     * that have no late-fee component. Monthly/custom-fee policy and waivers
     * stay in their own versioned workflows.
     */
    fun supportsDiscountAdjustment(fee: FeeEntity): Boolean =
        fee.lateFeeAmount <= EPSILON && when (fee.feeType.trim().lowercase()) {
            "admission_fee", "admission", "advance_fee" -> true
            else -> false
        }

    /**
     * Calculates the same amount model sent to the trusted ledger. The server
     * remains authoritative and repeats the immutable-paid validation.
     */
    fun quote(fee: FeeEntity, requestedDiscountPercent: Double): Quote {
        require(requestedDiscountPercent in 0.0..100.0) {
            "Discount must be between 0 and 100%."
        }

        if (!supportsDiscountAdjustment(fee)) {
            require(abs(requestedDiscountPercent - currentDiscountPercent(fee)) <= EPSILON) {
                "Discount is not available for this fee."
            }
            return Quote(
                baseAmount = fee.baseAmount,
                discountPercent = currentDiscountPercent(fee),
                discountAmount = fee.discountAmount,
                totalAmount = fee.totalAmount,
                alreadyPaid = fee.paidAmount,
                outstandingAmount = fee.dueAmount.coerceAtLeast(0.0)
            )
        }

        val discountAmount = money(fee.baseAmount * requestedDiscountPercent / 100.0)
        val totalAmount = money(fee.baseAmount - discountAmount)
        require(totalAmount + EPSILON >= fee.paidAmount) {
            "Discount cannot reduce the fee below the amount already paid."
        }
        return Quote(
            baseAmount = fee.baseAmount,
            discountPercent = requestedDiscountPercent,
            discountAmount = discountAmount,
            totalAmount = totalAmount,
            alreadyPaid = fee.paidAmount,
            outstandingAmount = money(max(0.0, totalAmount - fee.paidAmount))
        )
    }

    fun requiresAdjustment(fee: FeeEntity, quote: Quote): Boolean =
        fee.id.isNotBlank() && supportsDiscountAdjustment(fee) &&
            (abs(fee.baseAmount - quote.baseAmount) > EPSILON ||
                abs(fee.discountAmount - quote.discountAmount) > EPSILON ||
                abs(fee.totalAmount - quote.totalAmount) > EPSILON)

    /** Admission is shown first; monthly dues then follow calendar order. */
    fun compareForDisplay(first: FeeEntity, second: FeeEntity): Int {
        val firstKey = displaySortKey(first)
        val secondKey = displaySortKey(second)
        return firstKey.compareTo(secondKey)
    }

    private fun displaySortKey(fee: FeeEntity): String {
        val normalizedType = fee.feeType.trim().lowercase()
        if (normalizedType == "admission_fee" || normalizedType == "admission") {
            return "0:admission:${selectionKey(fee)}"
        }
        val month = parseMonth(fee.feePeriod)
        if (month != null) {
            return "1:%04d:%02d:%s".format(month.first, month.second, selectionKey(fee))
        }
        val oneTimeRank = when (normalizedType) {
            "course_fee", "course" -> "2:course"
            "exam_fee", "exam" -> "3:exam"
            "advance_fee", "advance" -> "4:advance"
            else -> "5:${normalized(fee.feePeriod)}"
        }
        return "$oneTimeRank:${selectionKey(fee)}"
    }

    private fun parseMonth(value: String): Pair<Int, Int>? {
        val match = Regex("^([A-Za-z]{3,9})\\s+(\\d{4})$").matchEntire(value.trim()) ?: return null
        val month = when (match.groupValues[1].take(3).lowercase()) {
            "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4
            "may" -> 5; "jun" -> 6; "jul" -> 7; "aug" -> 8
            "sep" -> 9; "oct" -> 10; "nov" -> 11; "dec" -> 12
            else -> return null
        }
        return match.groupValues[2].toIntOrNull()?.let { it to month }
    }

    private fun normalized(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun money(value: Double): Double = round(value * 100.0) / 100.0
}
