package com.batchfee.edu.domain

import android.content.Context

/** Stores a role and non-sensitive login identifier only. Passwords and tokens are never stored. */
class UnifiedLoginPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadRole(): String = preferences.getString(KEY_ROLE, "Admin") ?: "Admin"
    fun loadIdentifier(): String = preferences.getString(KEY_IDENTIFIER, "") ?: ""

    fun save(role: String, identifier: String) {
        preferences.edit()
            .putString(KEY_ROLE, role)
            .putString(KEY_IDENTIFIER, identifier.trim())
            .apply()
    }

    private companion object {
        const val PREFS = "batchfee_unified_login"
        const val KEY_ROLE = "selected_role"
        const val KEY_IDENTIFIER = "last_identifier"
    }
}
