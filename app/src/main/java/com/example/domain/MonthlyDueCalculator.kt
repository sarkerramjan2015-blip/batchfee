package com.batchfee.edu.domain

import com.batchfee.edu.data.models.FeeEntity
import java.util.Calendar

data class ComputedMonthDue(
    val period: String,
    val monthlyFeeAmount: Double,
    val paidAmount: Double,
    val outstanding: Double,
    val batchId: String,
    val batchName: String
)

object MonthlyDueCalculator {
    private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /**
     * Returns per-month outstanding items for one batch from admission to current month.
     * Never creates or modifies database rows.
     */
    fun computeMonthlyOutstandingItems(
        admissionDateMs: Long,
        monthlyFeeAmount: Double,
        batchId: String,
        batchName: String,
        existingMonthlyFees: List<FeeEntity>
    ): List<ComputedMonthDue> {
        if (admissionDateMs <= 0L || monthlyFeeAmount <= 0.0) return emptyList()

        val startCal = Calendar.getInstance().apply { timeInMillis = admissionDateMs }
        val currentCal = Calendar.getInstance()
        if (startCal.timeInMillis > currentCal.timeInMillis) return emptyList()

        val paidByPeriod = existingMonthlyFees.associate { it.feePeriod to it.paidAmount }
        val feeByPeriod = existingMonthlyFees.associate { it.feePeriod to it }

        val targetYear = currentCal.get(Calendar.YEAR)
        val targetMonth = currentCal.get(Calendar.MONTH)
        var year = startCal.get(Calendar.YEAR)
        var month = startCal.get(Calendar.MONTH)
        val result = mutableListOf<ComputedMonthDue>()

        while (year < targetYear || (year == targetYear && month < targetMonth)) {
            val period = "${monthNames[month]} $year"
            val paid = paidByPeriod[period] ?: 0.0
            val existingFee = feeByPeriod[period]
            val required = existingFee?.totalAmount ?: monthlyFeeAmount
            val outstanding = (required - paid).coerceAtLeast(0.0)
            if (outstanding > 0.0) {
                result += ComputedMonthDue(
                    period = period,
                    monthlyFeeAmount = monthlyFeeAmount,
                    paidAmount = paid,
                    outstanding = outstanding,
                    batchId = batchId,
                    batchName = batchName
                )
            }
            month++
            if (month > 11) { month = 0; year++ }
        }

        return result
    }

    fun isMonthlyFeeType(feeType: String): Boolean =
        feeType.trim().lowercase() in setOf("monthly", "monthly_fee", "monthly fee")

    /**
     * Checks whether a fee period string (e.g. "Jul 2026") represents a month
     * that is strictly BEFORE the current month.
     * Returns false for the current month and all future months.
     */
    fun isPastMonth(period: String): Boolean {
        // Parse "MMM yyyy" or "MMM-yyyy" or similar
        val cleaned = period.trim().take(3) + " " + period.trim().filter { it.isDigit() }.take(4)
        val parts = cleaned.split(" ")
        if (parts.size < 2) return false
        val monthIdx = monthNames.indexOfFirst { it.equals(parts[0], ignoreCase = true) }
        val year = parts[1].toIntOrNull() ?: return false
        if (monthIdx < 0) return false

        val now = Calendar.getInstance()
        val feeYearMonth = year * 12 + monthIdx
        val currentYearMonth = now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)
        return feeYearMonth < currentYearMonth
    }
}
