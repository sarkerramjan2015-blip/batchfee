package com.batchfee.edu.domain

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemePreferences {
    private const val PREFS_NAME = "batchfee_prefs"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_THEME_SET = "theme_set"

    private val _isDarkMode = MutableStateFlow<Boolean?>(null)
    val isDarkMode: StateFlow<Boolean?> = _isDarkMode.asStateFlow()

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isDarkMode.value = if (prefs.getBoolean(KEY_THEME_SET, false)) prefs.getBoolean(KEY_DARK_MODE, false) else null
    }

    fun setDarkMode(context: Context, dark: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DARK_MODE, dark).putBoolean(KEY_THEME_SET, true).apply()
        _isDarkMode.value = dark
    }

    fun isThemeSet(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_THEME_SET, false)

    fun isDark(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_DARK_MODE, false)
}

