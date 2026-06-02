package com.example.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionManager {
    const val SESSION_TIMEOUT_MS = 60_000L
    const val SESSION_EXPIRED_MESSAGE = "Session expired. Log in again."

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _currentInstituteId = MutableStateFlow<String?>(null)
    val currentInstituteId: StateFlow<String?> = _currentInstituteId.asStateFlow()

    private val _currentUserRole = MutableStateFlow<String?>(null)
    val currentUserRole: StateFlow<String?> = _currentUserRole.asStateFlow()

    private val _sessionNotice = MutableStateFlow<String?>(null)
    val sessionNotice: StateFlow<String?> = _sessionNotice.asStateFlow()

    private val _lastActivityAtMs = MutableStateFlow(System.currentTimeMillis())
    val lastActivityAtMs: StateFlow<Long> = _lastActivityAtMs.asStateFlow()

    fun login(userId: String, instituteId: String?, role: String) {
        _sessionNotice.value = null
        _currentUserId.value = userId
        _currentInstituteId.value = instituteId
        _currentUserRole.value = role
        _lastActivityAtMs.value = System.currentTimeMillis()
    }

    fun logout(expired: Boolean = false) {
        _sessionNotice.value = if (expired) SESSION_EXPIRED_MESSAGE else null
        _currentUserId.value = null
        _currentInstituteId.value = null
        _currentUserRole.value = null
    }

    fun expireSession() = logout(expired = true)

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

    fun hasPermission(permission: String, staffPermissions: String? = null): Boolean {
        if (isAdmin()) return true
        if (staffPermissions == null) return false
        return permission in staffPermissions.split(",").map { it.trim() }.toSet()
    }
}
