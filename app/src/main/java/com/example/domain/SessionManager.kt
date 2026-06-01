package com.example.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionManager {
    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _currentInstituteId = MutableStateFlow<String?>(null)
    val currentInstituteId: StateFlow<String?> = _currentInstituteId.asStateFlow()

    private val _currentUserRole = MutableStateFlow<String?>(null)
    val currentUserRole: StateFlow<String?> = _currentUserRole.asStateFlow()

    fun login(userId: String, instituteId: String?, role: String) {
        _currentUserId.value = userId
        _currentInstituteId.value = instituteId
        _currentUserRole.value = role
    }

    fun logout() {
        _currentUserId.value = null
        _currentInstituteId.value = null
        _currentUserRole.value = null
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
