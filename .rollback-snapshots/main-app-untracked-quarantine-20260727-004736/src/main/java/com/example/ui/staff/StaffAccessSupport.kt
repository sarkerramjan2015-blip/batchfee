package com.batchfee.edu.ui.staff

import com.batchfee.edu.data.models.StaffEntity

/** Values passed directly from successful creation to the one-time invite UI. */
data class StaffInviteDetails(
    val staffId: String,
    val staffName: String,
    val loginId: String,
    val email: String,
    val instituteName: String,
    val roleTitle: String,
    val temporaryPassword: String
) {
    fun shareText(): String = buildString {
        appendLine("BatchFee staff access")
        appendLine("Institute: $instituteName")
        appendLine("Name: $staffName")
        appendLine("Role: $roleTitle")
        appendLine("Login ID: $loginId")
        appendLine("Email: $email")
        appendLine("Temporary password: $temporaryPassword")
        append("Open the BatchFee app and sign in with your email or Staff ID. Change the temporary password with your administrator after first login.")
    }
}

object StaffPresentation {
    fun initials(name: String): String = name
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifBlank { "?" }

    fun canPresentAction(isAdmin: Boolean, effectivePermissions: Set<String>, permission: String): Boolean =
        isAdmin || permission in effectivePermissions

    fun isActive(staff: StaffEntity): Boolean = staff.status == "active" && staff.archivedAtMs == null
}
