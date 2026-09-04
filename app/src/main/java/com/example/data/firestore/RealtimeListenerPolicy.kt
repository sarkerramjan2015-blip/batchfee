package com.batchfee.edu.data.firestore

import com.batchfee.edu.domain.StaffPermissions

internal data class RealtimeListenerPlan(
    val listenInstitute: Boolean = false,
    val listenStudents: Boolean = false,
    val listenBatchStructure: Boolean = false,
    val listenStaff: Boolean = false,
    val listenSalary: Boolean = false,
    val listenFinance: Boolean = false,
    val listenExpenses: Boolean = false
)

/** Mirrors the collection-level read permissions in firestore.rules. */
internal object RealtimeListenerPolicy {
    private val studentPermissions = setOf(
        StaffPermissions.VIEW_STUDENTS,
        StaffPermissions.MANAGE_STUDENTS,
        StaffPermissions.VIEW_BATCHES,
        StaffPermissions.MANAGE_BATCHES,
        StaffPermissions.VIEW_FEE_SUMMARY,
        StaffPermissions.COLLECT_FEE,
        StaffPermissions.SEND_DUE_MESSAGE,
        StaffPermissions.TAKE_ATTENDANCE,
        StaffPermissions.VIEW_ATTENDANCE_REPORTS,
        StaffPermissions.VIEW_REPORTS,
        StaffPermissions.MANAGE_EXAMS,
        StaffPermissions.GENERATE_ID_CARDS,
        StaffPermissions.BIRTHDAY_REMINDERS
    )

    private val batchPermissions = setOf(
        StaffPermissions.VIEW_BATCHES,
        StaffPermissions.MANAGE_BATCHES,
        StaffPermissions.VIEW_STUDENTS,
        StaffPermissions.MANAGE_STUDENTS,
        StaffPermissions.VIEW_FEE_SUMMARY,
        StaffPermissions.COLLECT_FEE,
        StaffPermissions.TAKE_ATTENDANCE,
        StaffPermissions.VIEW_ATTENDANCE_REPORTS,
        StaffPermissions.VIEW_REPORTS,
        StaffPermissions.MANAGE_EXAMS
    )

    private val financePermissions = setOf(
        StaffPermissions.VIEW_FEE_SUMMARY,
        StaffPermissions.COLLECT_FEE,
        StaffPermissions.SEND_DUE_MESSAGE,
        StaffPermissions.VIEW_REPORTS
    )

    fun forSession(role: String?, permissions: Set<String>): RealtimeListenerPlan {
        return when (normalizeRole(role)) {
            "instituteowner", "instituteadmin", "owner", "admin" ->
                RealtimeListenerPlan(
                    listenInstitute = true,
                    listenStudents = true,
                    listenBatchStructure = true,
                    listenStaff = true,
                    listenSalary = true,
                    listenFinance = true,
                    listenExpenses = true
                )

            "staff" -> RealtimeListenerPlan(
                listenInstitute = true,
                listenStudents = permissions.any(studentPermissions::contains),
                listenBatchStructure = permissions.any(batchPermissions::contains),
                listenStaff = StaffPermissions.MANAGE_STAFF in permissions,
                listenSalary = StaffPermissions.MANAGE_SALARY in permissions ||
                    StaffPermissions.VIEW_REPORTS in permissions,
                listenFinance = permissions.any(financePermissions::contains),
                listenExpenses = StaffPermissions.MANAGE_EXPENSES in permissions ||
                    StaffPermissions.VIEW_REPORTS in permissions
            )

            else -> RealtimeListenerPlan()
        }
    }

    private fun normalizeRole(role: String?): String = role
        .orEmpty()
        .trim()
        .replace("_", "")
        .lowercase()
}
