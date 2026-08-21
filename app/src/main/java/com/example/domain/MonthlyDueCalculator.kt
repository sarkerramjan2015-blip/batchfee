package com.batchfee.edu.domain

import com.batchfee.edu.data.models.FeeEntity
import java.util.Calendar
import java.util.TimeZone
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
    private val billingTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Dhaka")

    /**
     * Returns per-month outstanding items for one batch from admission through
     * the last completed billing month. The running month is never a due yet;
     * it becomes due only after that month ends. A removed enrollment is capped before its
     * departure month, so an old batch keeps its historic arrears without
     * charging that same month again after a batch shift.
     * Never creates or modifies database rows.
     */
    fun computeMonthlyOutstandingItems(
        admissionDateMs: Long,
        monthlyFeeAmount: Double,
        batchId: String,
        batchName: String,
        existingMonthlyFees: List<FeeEntity>,
        firstMonthFeePeriod: String? = null,
        firstMonthFeeAmount: Double? = null,
        customMonthlyFeeAmount: Double? = null,
        customFeeEffectiveFromPeriod: String? = null,
        billingEndedAtMs: Long? = null,
        asOfMs: Long = System.currentTimeMillis()
    ): List<ComputedMonthDue> {
        if (admissionDateMs <= 0L || monthlyFeeAmount <= 0.0) return emptyList()

        val startCal = Calendar.getInstance(billingTimeZone).apply { timeInMillis = admissionDateMs }
        val endCal = Calendar.getInstance(billingTimeZone).apply { timeInMillis = asOfMs }
        if (billingEndedAtMs != null && billingEndedAtMs > 0L) {
            // A student who left during August remains responsible for all
            // completed months up to July. The departure month itself belongs
            // to the new batch (or is not due yet), so the normal strict
            // before-month loop must stop at the departure month.
            val endedCal = Calendar.getInstance(billingTimeZone).apply {
                timeInMillis = billingEndedAtMs
                set(Calendar.DAY_OF_MONTH, 1)
            }
            if (endedCal.timeInMillis < endCal.timeInMillis) {
                endCal.timeInMillis = endedCal.timeInMillis
            }
        }
        if (startCal.timeInMillis > endCal.timeInMillis) return emptyList()

        val targetYear = endCal.get(Calendar.YEAR)
        val targetMonth = endCal.get(Calendar.MONTH)
        var year = startCal.get(Calendar.YEAR)
        var month = startCal.get(Calendar.MONTH)
        val result = mutableListOf<ComputedMonthDue>()

        // Monthly fees are arrears: only months strictly before the current
        // billing month can become outstanding. One-time fees (exam/admission)
        // are handled by their own callers and remain due immediately.
        val resolvedFirstMonthPeriod = firstMonthFeePeriod
            ?.takeIf { it.equals(periodFor(admissionDateMs), ignoreCase = true) }
            ?: periodFor(admissionDateMs)
        val resolvedFirstMonthAmount = firstMonthFeeAmount
            ?.takeIf { firstMonthFeePeriod.equals(resolvedFirstMonthPeriod, ignoreCase = true) }
            ?: calculateFirstMonthFee(monthlyFeeAmount, admissionDateMs)

        while (year < targetYear || (year == targetYear && month < targetMonth)) {
            val period = "${monthNames[month]} $year"
            // A real monthly/advance record owns every month written in its
            // label. It is displayed as that same record when due; never make
            // a second virtual fee for one of its covered months.
            val hasActualRecord = existingMonthlyFees.any { fee ->
                billingPeriodsCoveredBy(fee.feePeriod).any { covered ->
                    covered.equals(period, ignoreCase = true)
                }
            }
            if (!hasActualRecord) {
                val required = monthlyFeeAmountForPeriod(
                    period = period,
                    monthlyFeeAmount = monthlyFeeAmount,
                    firstMonthFeePeriod = resolvedFirstMonthPeriod,
                    firstMonthFeeAmount = resolvedFirstMonthAmount,
                    customMonthlyFeeAmount = customMonthlyFeeAmount,
                    customFeeEffectiveFromPeriod = customFeeEffectiveFromPeriod
                )
                if (required > 0.0) {
                    result += ComputedMonthDue(
                        period = period,
                        monthlyFeeAmount = required,
                        paidAmount = 0.0,
                        outstanding = required,
                        batchId = batchId,
                        batchName = batchName
                    )
                }
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
        val calendar = Calendar.getInstance(billingTimeZone).apply { timeInMillis = admissionDateMs }
        val admissionDay = calendar.get(Calendar.DAY_OF_MONTH).coerceAtMost(30)
        val billableDays = (31 - admissionDay).coerceAtLeast(1)
        return round((monthlyFeeAmount / 30.0) * billableDays)
    }

    fun periodFor(admissionDateMs: Long): String {
        if (admissionDateMs <= 0L) return ""
        val calendar = Calendar.getInstance(billingTimeZone).apply { timeInMillis = admissionDateMs }
        return "${monthNames[calendar.get(Calendar.MONTH)]} ${calendar.get(Calendar.YEAR)}"
    }

    /**
     * Resolves the contractual start of one batch enrollment.
     *
     * New and shifted enrollments freeze their own first billing period, which
     * must win over the student-level admission date. Legacy enrollments have
     * no frozen terms, so the admission date is their source of truth; their
     * later local/cloud sync timestamp must not silently erase valid dues.
     */
    fun effectiveBillingStartMs(
        studentAdmissionDateMs: Long,
        enrollmentJoinedAtMs: Long,
        firstMonthFeePeriod: String? = null
    ): Long {
        periodStartMs(firstMonthFeePeriod)?.let { return it }
        return studentAdmissionDateMs.takeIf { it > 0L }
            ?: enrollmentJoinedAtMs.takeIf { it > 0L }
            ?: 0L
    }

    /**
     * Returns true only when every month in a saved monthly/advance fee sits
     * inside its enrollment's valid billing window.  A mixed old record is
     * deliberately hidden rather than asking the owner to collect an amount
     * containing an invalid month. Fully invalid unpaid legacy rows are then
     * safely cancelled by the trusted ledger repair.
     */
    fun isMonthlyFeeWithinEnrollmentWindow(
        feePeriod: String,
        studentAdmissionDateMs: Long,
        enrollmentJoinedAtMs: Long,
        firstMonthFeePeriod: String? = null,
        billingEndedAtMs: Long? = null
    ): Boolean {
        val startMs = effectiveBillingStartMs(
            studentAdmissionDateMs,
            enrollmentJoinedAtMs,
            firstMonthFeePeriod
        )
        val startKey = periodKey(periodFor(startMs)) ?: return false
        val endKey = billingEndedAtMs
            ?.takeIf { it > 0L }
            ?.let { periodKey(periodFor(it)) }
        val coveredKeys = billingPeriodsCoveredBy(feePeriod).mapNotNull { periodKey(it) }
        return coveredKeys.isNotEmpty() && coveredKeys.all { key ->
            key >= startKey && (endKey == null || key < endKey)
        }
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
        firstMonthFeeAmount: Double?,
        customMonthlyFeeAmount: Double? = null,
        customFeeEffectiveFromPeriod: String? = null
    ): Double {
        val customApplies = customMonthlyFeeAmount != null && customMonthlyFeeAmount > 0.0 &&
            !customFeeEffectiveFromPeriod.isNullOrBlank() &&
            comparePeriods(period, customFeeEffectiveFromPeriod) >= 0
        return if (customApplies) {
            customMonthlyFeeAmount
        } else if (
        !firstMonthFeePeriod.isNullOrBlank() &&
        firstMonthFeeAmount != null &&
        period.equals(firstMonthFeePeriod, ignoreCase = true)
    ) {
        firstMonthFeeAmount
    } else {
        monthlyFeeAmount
    }
    }

    /** Compares "MMM yyyy" values without relying on the phone locale. */
    private fun comparePeriods(left: String, right: String): Int {
        val leftKey = periodKey(left)
        val rightKey = periodKey(right)
        return if (leftKey != null && rightKey != null) leftKey.compareTo(rightKey) else -1
    }

    private fun periodKey(value: String): Int? {
        val cleaned = value.trim()
        val month = monthNames.indexOfFirst { cleaned.startsWith(it, ignoreCase = true) }
        val year = Regex("\\d{4}").find(cleaned)?.value?.toIntOrNull()
        return if (month >= 0 && year != null) year * 12 + month else null
    }

    private fun periodStartMs(value: String?): Long? {
        val key = value?.let(::periodKey) ?: return null
        return Calendar.getInstance(billingTimeZone).apply {
            clear()
            set(key / 12, key % 12, 1, 12, 0, 0)
        }.timeInMillis
    }

    /**
     * All of these are installment labels, not one-time charges. Older builds
     * saved a manually selected future month as `advance_fee`; it must follow
     * the same month-end rule as a normal monthly fee, otherwise September or
     * October leaks into the Due Fees reminder list during August.
     */
    fun isMonthlyFeeType(feeType: String): Boolean =
        feeType.trim().lowercase() in setOf(
            "monthly", "monthly_fee", "monthly fee",
            "advance", "advance_fee", "advance fee",
            "due", "due_fee", "due fee",
            "running_month", "running month",
            "mixed_period", "mixed period",
            "overdue"
        )

    /**
     * Expands a saved label such as "Aug 2026 - Sep 2026" to its monthly
     * installments. A single label returns one canonical period. Invalid
     * labels deliberately return no periods so they cannot be reminded early.
     */
    fun billingPeriodsCoveredBy(feePeriod: String): List<String> {
        val regex = Regex("""(?i)\b([a-z]{3,9})\s+(\d{4})\b""")
        val matches = regex.findAll(feePeriod).mapNotNull { match ->
            val month = monthNames.indexOfFirst { it.equals(match.groupValues[1].take(3), ignoreCase = true) }
            val year = match.groupValues[2].toIntOrNull()
            if (month >= 0 && year != null) year * 12 + month else null
        }.toList()
        val first = matches.firstOrNull() ?: return emptyList()
        val last = matches.lastOrNull() ?: first
        if (last < first || last - first > 35) return emptyList()
        return (first..last).map { key ->
            "${monthNames[key % 12]} ${key / 12}"
        }
    }

    /** A monthly installment is due only after the final month it covers ends. */
    fun isMonthlyInstallmentDue(
        feeType: String,
        feePeriod: String,
        asOfMs: Long = System.currentTimeMillis()
    ): Boolean = isMonthlyFeeType(feeType) &&
        billingPeriodsCoveredBy(feePeriod).lastOrNull()?.let { isPastMonth(it, asOfMs) } == true

    /**
     * Checks whether a fee period string (e.g. "Jul 2026") represents a month
     * that is strictly BEFORE the current month.
     * Returns false for the current month and all future months.
     */
    fun isPastMonth(period: String, asOfMs: Long = System.currentTimeMillis()): Boolean {
        // Parse "MMM yyyy" or "MMM-yyyy" or similar
        val cleaned = period.trim().take(3) + " " + period.trim().filter { it.isDigit() }.take(4)
        val parts = cleaned.split(" ")
        if (parts.size < 2) return false
        val monthIdx = monthNames.indexOfFirst { it.equals(parts[0], ignoreCase = true) }
        val year = parts[1].toIntOrNull() ?: return false
        if (monthIdx < 0) return false

        val now = Calendar.getInstance(billingTimeZone).apply { timeInMillis = asOfMs }
        val feeYearMonth = year * 12 + monthIdx
        val currentYearMonth = now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)
        return feeYearMonth < currentYearMonth
    }
}
