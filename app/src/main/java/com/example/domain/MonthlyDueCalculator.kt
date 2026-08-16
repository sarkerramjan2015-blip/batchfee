package com.batchfee.edu.domain

import com.batchfee.edu.data.models.FeeEntity
import java.util.Calendar
import kotlin.math.round

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
        existingMonthlyFees: List<FeeEntity>,
        firstMonthFeePeriod: String? = null,
        firstMonthFeeAmount: Double? = null
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
            val required = existingFee?.totalAmount ?: monthlyFeeAmountForPeriod(
                period = period,
                monthlyFeeAmount = monthlyFeeAmount,
                firstMonthFeePeriod = firstMonthFeePeriod,
                firstMonthFeeAmount = firstMonthFeeAmount
            )
            val outstanding = (required - paid).coerceAtLeast(0.0)
            if (outstanding > 0.0) {
                result += ComputedMonthDue(
                    period = period,
                    monthlyFeeAmount = required,
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

    /**
     * The first month uses a simple 30-day rule. Admission on the 1st is a
     * full month; otherwise the amount is rounded to a whole BDT.
     */
    fun calculateFirstMonthFee(monthlyFeeAmount: Double, admissionDateMs: Long): Double {
        if (monthlyFeeAmount <= 0.0 || admissionDateMs <= 0L) return 0.0
        val calendar = Calendar.getInstance().apply { timeInMillis = admissionDateMs }
        val admissionDay = calendar.get(Calendar.DAY_OF_MONTH).coerceAtMost(30)
        val billableDays = (31 - admissionDay).coerceAtLeast(1)
        return round((monthlyFeeAmount / 30.0) * billableDays)
    }

    fun periodFor(admissionDateMs: Long): String {
        if (admissionDateMs <= 0L) return ""
        val calendar = Calendar.getInstance().apply { timeInMillis = admissionDateMs }
        return "${monthNames[calendar.get(Calendar.MONTH)]} ${calendar.get(Calendar.YEAR)}"
    }

    /**
     * Existing enrollments intentionally have no frozen first-month amount,
     * so their historic full-month behaviour remains unchanged. New
     * enrollments receive both frozen values at creation time.
     */
    fun monthlyFeeAmountForPeriod(
        period: String,
        monthlyFeeAmount: Double,
        firstMonthFeePeriod: String?,
        firstMonthFeeAmount: Double?
    ): Double = if (
        !firstMonthFeePeriod.isNullOrBlank() &&
        firstMonthFeeAmount != null &&
        period.equals(firstMonthFeePeriod, ignoreCase = true)
    ) {
        firstMonthFeeAmount
    } else {
        monthlyFeeAmount
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
