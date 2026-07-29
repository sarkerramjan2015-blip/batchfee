package com.batchfee.edu.domain

import java.text.DateFormat
import java.util.Date

object SubscriptionDateFormatter {
    fun format(timestampMs: Long?, dateFormat: DateFormat): String {
        val validTimestamp = timestampMs?.takeIf { it > 0L } ?: return "Not available"
        return dateFormat.format(Date(validTimestamp))
    }
}
