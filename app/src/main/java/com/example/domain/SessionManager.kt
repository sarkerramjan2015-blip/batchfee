package com.batchfee.edu.domain

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionManager {
    const val SESSION_TIMEOUT_MS = 300_000L
    const val SESSION_EXPIRED_MESSAGE = "Your session has expired. Please log in again."

    private const val PREFS_LOGIN = "batchfee_login_prefs"
    private const val KEY_LAST_LOGIN_ID = "last_login_id"
    private const val PREFS_SESSION = "batchfee_session_prefs"
    private const val KEY_PENDING_SESSION_NOTICE = "pending_session_notice"

    private var appContext: Context? = null

    /** Restores an expiry notice after process recreation so the user is never logged out silently. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        _sessionNotice.value = appContext
            ?.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)
            ?.getString(KEY_PENDING_SESSION_NOTICE, null)
    }

    fun saveLastLoginId(context: Context, loginId: String) {
        context.applicationContext.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_LOGIN_ID, loginId).apply()
    }

    fun getLastLoginId(context: Context): String? {
        return context.applicationContext.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
            .getString(KEY_LAST_LOGIN_ID, null)
    }

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _currentInstituteId = MutableStateFlow<String?>(null)
    val currentInstituteId: StateFlow<String?> = _currentInstituteId.asStateFlow()

    private val _currentUserRole = MutableStateFlow<String?>(null)
    val currentUserRole: StateFlow<String?> = _currentUserRole.asStateFlow()

    private val _currentStaffPermissions = MutableStateFlow<Set<String>>(emptySet())
    val currentStaffPermissions: StateFlow<Set<String>> = _currentStaffPermissions.asStateFlow()

    private val _sessionNotice = MutableStateFlow<String?>(null)
    val sessionNotice: StateFlow<String?> = _sessionNotice.asStateFlow()

    private val _lastActivityAtMs = MutableStateFlow(System.currentTimeMillis())
    val lastActivityAtMs: StateFlow<Long> = _lastActivityAtMs.asStateFlow()

    fun login(userId: String, instituteId: String?, role: String, staffPermissions: String? = null) {
        clearSessionNotice()
        _currentUserId.value = userId
        _currentInstituteId.value = instituteId
        _currentUserRole.value = role
        _currentStaffPermissions.value = parsePermissions(staffPermissions)
        _lastActivityAtMs.value = System.currentTimeMillis()
    }

    fun logout(expired: Boolean = false) {
        if (expired) {
            _sessionNotice.value = SESSION_EXPIRED_MESSAGE
            appContext?.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)
                ?.edit()?.putString(KEY_PENDING_SESSION_NOTICE, SESSION_EXPIRED_MESSAGE)?.apply()
        } else {
            clearSessionNotice()
        }
        _currentUserId.value = null
        _currentInstituteId.value = null
        _currentUserRole.value = null
        _currentStaffPermissions.value = emptySet()
        _lastActivityAtMs.value = System.currentTimeMillis()
        try { FirebaseAuth.getInstance().signOut() } catch (_: Exception) {}
    }

    fun expireSession() = logout(expired = true)

    fun clearSessionNotice() {
        _sessionNotice.value = null
        appContext?.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)
            ?.edit()?.remove(KEY_PENDING_SESSION_NOTICE)?.apply()
    }

    fun markActivity() {
        if (_currentUserId.value != null) {
            _lastActivityAtMs.value = System.currentTimeMillis()
        }
    }

    fun isSessionInactive(nowMs: Long = System.currentTimeMillis()): Boolean {
        return _currentUserId.value != null && nowMs - _lastActivityAtMs.value >= SESSION_TIMEOUT_MS
    }

    fun isLoggedIn(): Boolean = _currentUserId.value != null

    // Role helpers for access control
    fun isAdmin(): Boolean {
        val role = _currentUserRole.value ?: return false
        return role in listOf("InstituteOwner", "SuperAdmin", "InstituteAdmin")
    }

    fun isStaff(): Boolean {
        val role = _currentUserRole.value ?: return false
        return role == "Staff"
    }

    fun updateStaffPermissions(staffPermissions: String?) {
        _currentStaffPermissions.value = parsePermissions(staffPermissions)
    }

    fun hasPermission(permission: String, staffPermissions: String? = null): Boolean {
        if (isAdmin()) return true
        val permissions = staffPermissions?.let(::parsePermissions) ?: _currentStaffPermissions.value
        return permission in permissions
    }

    fun hasAnyPermission(vararg permissions: String): Boolean {
        if (isAdmin()) return true
        return permissions.any { hasPermission(it) }
    }

    private fun parsePermissions(raw: String?): Set<String> {
        return raw
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }
}

