package com.batchfee.edu.domain

import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.max

/**
 * Pure monthly-obligation planning. Persistence and remote writes are kept out
 * of this class so a reconciliation never mutates an existing financial record.
 */
object MonthlyFeeSchedule {
    data class BillingMonth(val year: Int, val month: Int)

    data class Enrollment(
        val studentId: String,
        val batchId: String,
        val admissionDateMs: Long,
        val joinedAtMs: Long,
        val monthlyFeeAmount: Double
    )

    data class ExistingFee(
        val id: String,
        val studentId: String,
        val batchId: String?,
        val feePeriod: String,
        val feeType: String,
        val cancelled: Boolean
    )

    data class Obligation(
        val id: String,
        val studentId: String,
        val batchId: String,
        val year: Int,
        val month: Int,
        val dueDateMs: Long,
        val feePeriod: String,
        val amount: Double
    )

    fun planMissingObligations(
        instituteId: String,
        enrollments: List<Enrollment>,
        existingFees: List<ExistingFee>,
        asOfMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<Obligation> = enrollments.flatMap { enrollment ->
        if (enrollment.monthlyFeeAmount <= 0.0) return@flatMap emptyList()

        val feeStartMs = if (enrollment.joinedAtMs > 0L) {
            max(enrollment.admissionDateMs, enrollment.joinedAtMs)
        } else {
            enrollment.admissionDateMs
        }
        payableMonths(feeStartMs, asOfMs, timeZone).mapNotNull { month ->
            val id = monthlyFeeId(instituteId, enrollment.studentId, enrollment.batchId, month.year, month.month)
            val alreadyRepresented = existingFees.any { fee ->
                fee.studentId == enrollment.studentId &&
                    fee.batchId == enrollment.batchId &&
                    (fee.id == id || feePeriodCoversMonth(fee, month.year, month.month))
            }
            if (alreadyRepresented) {
                null
            } else {
                Obligation(
                    id = id,
                    studentId = enrollment.studentId,
                    batchId = enrollment.batchId,
                    year = month.year,
                    month = month.month,
                    dueDateMs = monthStartMs(month.year, month.month, timeZone),
                    feePeriod = monthLabel(month.year, month.month),
                    amount = enrollment.monthlyFeeAmount
                )
            }
        }
    }

    fun monthlyFeeId(instituteId: String, studentId: String, batchId: String, year: Int, month: Int): String {
        val stableKey = "$instituteId|$studentId|$batchId|$year|$month"
        return "monthly_due_${UUID.nameUUIDFromBytes(stableKey.toByteArray(StandardCharsets.UTF_8))}"
    }

    fun billingMonthAt(timeMs: Long, timeZone: TimeZone = TimeZone.getDefault()): BillingMonth {
        val calendar = calendarFor(timeMs, timeZone)
        return BillingMonth(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
    }

    fun monthStartMs(year: Int, month: Int, timeZone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(timeZone).run {
            clear()
            set(year, month - 1, 1, 0, 0, 0)
            timeInMillis
        }

    fun monthLabel(year: Int, month: Int): String = "${shortMonthNames[month - 1]} $year"

    private fun payableMonths(startMs: Long, asOfMs: Long, timeZone: TimeZone): List<BillingMonth> {
        if (startMs <= 0L || asOfMs <= 0L) return emptyList()
        val start = calendarFor(startMs, timeZone)
        val end = calendarFor(asOfMs, timeZone)
        val startIndex = start.get(Calendar.YEAR) * 12 + start.get(Calendar.MONTH)
        val endIndex = end.get(Calendar.YEAR) * 12 + end.get(Calendar.MONTH)
        if (startIndex > endIndex) return emptyList()

        return (startIndex..endIndex).map { index ->
            BillingMonth(year = index / 12, month = index % 12 + 1)
        }
    }

    private fun feePeriodCoversMonth(fee: ExistingFee, targetYear: Int, targetMonth: Int): Boolean {
        if (!isMonthlyFeeType(fee.feeType)) return false
        val months = monthPattern.findAll(fee.feePeriod).mapNotNull { match ->
            val month = monthNames[match.groupValues[1].lowercase(Locale.US)] ?: return@mapNotNull null
            val year = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            BillingMonth(year, month)
        }.toList()
        if (months.isEmpty() || months.size > 2) return false

        val first = months.first()
        val last = months.last()
        val firstIndex = first.year * 12 + first.month - 1
        val lastIndex = last.year * 12 + last.month - 1
        val targetIndex = targetYear * 12 + targetMonth - 1
        return targetIndex in minOf(firstIndex, lastIndex)..maxOf(firstIndex, lastIndex)
    }

    private fun isMonthlyFeeType(feeType: String): Boolean = when (
        feeType.trim().lowercase(Locale.US).replace(' ', '_')
    ) {
        "monthly", "monthly_fee" -> true
        else -> false
    }

    private fun calendarFor(timeMs: Long, timeZone: TimeZone): Calendar =
        Calendar.getInstance(timeZone).apply { timeInMillis = timeMs }

    private val shortMonthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val monthNames = mapOf(
        "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4, "may" to 5, "jun" to 6, "june" to 6,
        "jul" to 7, "july" to 7, "aug" to 8, "august" to 8, "sep" to 9, "sept" to 9,
        "september" to 9, "oct" to 10, "october" to 10, "nov" to 11, "november" to 11,
        "dec" to 12, "december" to 12
    )

    private val monthPattern = Regex(
        "(?i)\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+(\\d{4})\\b"
    )
}
