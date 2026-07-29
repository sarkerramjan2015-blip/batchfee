package com.batchfee.edu.domain

import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

enum class FeeMonthStatus { DISABLED, PAID, PARTIAL, DUE, CURRENT, ADVANCE }

data class MonthlyFeeSnapshot(
    val id: String,
    val period: MonthlyFeeSchedule.BillingMonth,
    val baseAmount: Double,
    val discountAmount: Double,
    val lateFeeAmount: Double,
    val totalAmount: Double,
    val paidAmount: Double,
    val dueAmount: Double
)

data class SmartFeeMonth(
    val period: MonthlyFeeSchedule.BillingMonth,
    val status: FeeMonthStatus,
    val fee: MonthlyFeeSnapshot?,
    val monthlyAmount: Double
) {
    val label: String get() = MonthlyFeeSchedule.monthLabel(period.year, period.month)
    val enabled: Boolean get() = status != FeeMonthStatus.DISABLED && status != FeeMonthStatus.PAID
    val remainingDue: Double get() = fee?.dueAmount ?: monthlyAmount
}

data class PlannedFeeAllocation(
    val period: MonthlyFeeSchedule.BillingMonth,
    val feeId: String?,
    val collectedAmount: Double,
    val discountPercent: Double,
    val payableAfterDiscount: Double
)

object SmartFeePeriodPlanner {
    fun buildMonths(
        availableMonths: List<MonthlyFeeSchedule.BillingMonth>,
        admissionDateMs: Long,
        joinedAtMs: Long,
        monthlyAmount: Double,
        feeSnapshots: List<MonthlyFeeSnapshot>,
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<SmartFeeMonth> {
        val feeStartMs = if (joinedAtMs > 0L) max(admissionDateMs, joinedAtMs) else admissionDateMs
        val feeStart = MonthlyFeeSchedule.billingMonthAt(feeStartMs, timeZone)
        val current = MonthlyFeeSchedule.billingMonthAt(nowMs, timeZone)
        val feeByMonth = feeSnapshots.associateBy { it.period }
        return availableMonths.distinct().sortedBy { indexOf(it) }.map { period ->
            val fee = feeByMonth[period]
            SmartFeeMonth(
                period = period,
                status = when {
                    indexOf(period) < indexOf(feeStart) -> FeeMonthStatus.DISABLED
                    fee != null && fee.dueAmount <= EPSILON -> FeeMonthStatus.PAID
                    fee != null && fee.paidAmount > EPSILON -> FeeMonthStatus.PARTIAL
                    indexOf(period) < indexOf(current) -> FeeMonthStatus.DUE
                    period == current -> FeeMonthStatus.CURRENT
                    else -> FeeMonthStatus.ADVANCE
                },
                fee = fee,
                monthlyAmount = monthlyAmount
            )
        }
    }

    fun defaultStart(months: List<SmartFeeMonth>): SmartFeeMonth? =
        months.firstOrNull { it.status == FeeMonthStatus.PARTIAL } ?:
            months.firstOrNull { it.status == FeeMonthStatus.DUE } ?:
            months.firstOrNull { it.status == FeeMonthStatus.CURRENT && it.enabled } ?:
            months.firstOrNull { it.status == FeeMonthStatus.ADVANCE && it.enabled }

    fun range(months: List<SmartFeeMonth>, start: MonthlyFeeSchedule.BillingMonth, end: MonthlyFeeSchedule.BillingMonth): List<SmartFeeMonth> {
        val first = min(indexOf(start), indexOf(end))
        val last = max(indexOf(start), indexOf(end))
        return months.filter { indexOf(it.period) in first..last }
    }

    fun autoSelection(months: List<SmartFeeMonth>): List<SmartFeeMonth> = months
        .filter { it.enabled }
        .sortedWith(compareBy<SmartFeeMonth> { autoPriority(it.status) }.thenBy { indexOf(it.period) })

    fun hasEarlierUnselectedDue(selected: List<SmartFeeMonth>, allMonths: List<SmartFeeMonth>): Boolean {
        val firstSelected = selected.minOfOrNull { indexOf(it.period) } ?: return false
        return allMonths.any {
            indexOf(it.period) < firstSelected &&
                (it.status == FeeMonthStatus.PARTIAL || it.status == FeeMonthStatus.DUE)
        }
    }

    fun allocate(
        selected: List<SmartFeeMonth>,
        receivedAmount: Double,
        discountPercent: Double,
        autoOrder: Boolean
    ): List<PlannedFeeAllocation> {
        require(receivedAmount >= 0.0) { "Received amount cannot be negative." }
        require(discountPercent in 0.0..100.0) { "Discount must be between 0 and 100%." }
        val ordered = if (autoOrder) autoSelection(selected) else selected.filter { it.enabled }.sortedBy { indexOf(it.period) }
        var remainingReceived = receivedAmount
        return ordered.map { month ->
            val payable = payableAfterDiscount(month, discountPercent)
            val collected = min(remainingReceived, payable)
            remainingReceived -= collected
            PlannedFeeAllocation(month.period, month.fee?.id, collected, discountPercent, payable)
        }.filter { it.payableAfterDiscount > EPSILON || it.discountPercent >= 100.0 }
    }

    fun payableAfterDiscount(month: SmartFeeMonth, discountPercent: Double): Double {
        require(discountPercent in 0.0..100.0) { "Discount must be between 0 and 100%." }
        val fee = month.fee
        val base = fee?.baseAmount ?: month.monthlyAmount
        val existingDiscount = fee?.discountAmount ?: 0.0
        val adjustedDiscount = max(existingDiscount, base * discountPercent / 100.0)
        val totalAfterDiscount = (base - adjustedDiscount + (fee?.lateFeeAmount ?: 0.0)).coerceAtLeast(0.0)
        return (totalAfterDiscount - (fee?.paidAmount ?: 0.0)).coerceAtLeast(0.0)
    }

    private fun autoPriority(status: FeeMonthStatus): Int = when (status) {
        FeeMonthStatus.PARTIAL -> 0
        FeeMonthStatus.DUE -> 1
        FeeMonthStatus.CURRENT -> 2
        FeeMonthStatus.ADVANCE -> 3
        FeeMonthStatus.PAID, FeeMonthStatus.DISABLED -> 4
    }

    private fun indexOf(month: MonthlyFeeSchedule.BillingMonth): Int = month.year * 12 + month.month - 1

    private const val EPSILON = 0.001
}
