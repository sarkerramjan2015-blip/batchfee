package com.batchfee.edu.domain

import android.content.Context

/**
 * Stores only the last successfully used student ID for this device. Passwords,
 * Firebase tokens, and session details are never saved here.
 */
class RememberedStudentIdStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): String = preferences.getString(KEY_LAST_STUDENT_ID, "")?.trim().orEmpty()

    fun save(studentId: String) {
        val value = studentId.trim()
        if (value.isNotEmpty()) {
            preferences.edit().putString(KEY_LAST_STUDENT_ID, value).apply()
        }
    }

    fun clear() {
        preferences.edit().remove(KEY_LAST_STUDENT_ID).apply()
    }

    private companion object {
        const val PREFS_NAME = "batchfee_student_login"
        const val KEY_LAST_STUDENT_ID = "last_successful_student_id"
    }
}
