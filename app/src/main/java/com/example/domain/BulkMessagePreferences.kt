package com.example.domain

import android.content.Context

object BulkMessagePreferences {
    private const val PREFS_NAME = "batchfee_prefs"
    private const val KEY_DELAY_MS = "bulk_send_delay_ms"
    const val DEFAULT_DELAY_MS = 3000L

    fun getDelayMs(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_DELAY_MS, DEFAULT_DELAY_MS)

    fun setDelayMs(context: Context, delayMs: Long) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_DELAY_MS, delayMs.coerceAtLeast(0L)).apply()
    }
}
