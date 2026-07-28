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

        while (year < targetYear || (year == targetYear && month <= targetMonth)) {
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
}
