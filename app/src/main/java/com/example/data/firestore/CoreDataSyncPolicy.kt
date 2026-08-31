package com.batchfee.edu.data.firestore

import com.batchfee.edu.domain.StaffPermissions

internal data class CoreDataSyncPlan(
    val syncSubscriptionPlans: Boolean = false,
    val syncInstitute: Boolean = false,
    val replayDeletions: Boolean = false,
    val syncStudents: Boolean = false,
    val syncBatches: Boolean = false,
    val syncEnrollments: Boolean = false,
    val syncStaff: Boolean = false,
    val replayFinanceOperations: Boolean = false,
    val syncFinance: Boolean = false,
    val syncAttendance: Boolean = false,
    val syncStaffAttendance: Boolean = false,
    val syncEnquiries: Boolean = false,
    val syncExams: Boolean = false,
    val syncExpenses: Boolean = false,
    val syncSalaries: Boolean = false,
    val syncReminders: Boolean = false,
    val syncAuditLogs: Boolean = false
)

/**
 * Mirrors the collection-level reads in firestore.rules for one cache refresh.
 * Keeping this policy separate prevents a limited staff session from issuing owner-only queries.
 */
internal object CoreDataSyncPolicy {
    fun forSession(role: String?, permissions: Set<String>): CoreDataSyncPlan {
        val realtime = RealtimeListenerPolicy.forSession(role, permissions)
        return when (normalizeRole(role)) {
            "instituteowner", "instituteadmin", "owner", "admin" -> CoreDataSyncPlan(
                syncSubscriptionPlans = true,
                syncInstitute = true,
                replayDeletions = true,
                syncStudents = true,
                syncBatches = true,
                syncEnrollments = true,
                syncStaff = true,
                replayFinanceOperations = true,
                syncFinance = true,
                syncAttendance = true,
                syncStaffAttendance = true,
                syncEnquiries = true,
                syncExams = true,
                syncExpenses = true,
                syncSalaries = true,
                syncReminders = true,
                syncAuditLogs = true
            )

            "staff" -> CoreDataSyncPlan(
                syncSubscriptionPlans = true,
                syncInstitute = realtime.listenInstitute,
                syncStudents = realtime.listenStudents,
                syncBatches = realtime.listenBatchStructure,
                syncEnrollments = realtime.listenBatchStructure,
                syncStaff = realtime.listenStaff,
                replayFinanceOperations = StaffPermissions.COLLECT_FEE in permissions,
                syncFinance = realtime.listenFinance,
                syncAttendance = permissions.any {
                    it == StaffPermissions.TAKE_ATTENDANCE ||
                        it == StaffPermissions.VIEW_ATTENDANCE_REPORTS ||
                        it == StaffPermissions.VIEW_REPORTS
                },
                syncStaffAttendance = permissions.any {
                    it == StaffPermissions.MANAGE_STAFF_ATTENDANCE ||
                        it == StaffPermissions.MANAGE_STAFF ||
                        it == StaffPermissions.VIEW_REPORTS
                },
                syncEnquiries = StaffPermissions.VIEW_REPORTS in permissions,
                syncExams = StaffPermissions.MANAGE_EXAMS in permissions ||
                    StaffPermissions.VIEW_REPORTS in permissions,
                syncExpenses = realtime.listenExpenses,
                syncSalaries = StaffPermissions.MANAGE_SALARY in permissions ||
                    StaffPermissions.VIEW_REPORTS in permissions,
                syncReminders = StaffPermissions.MANAGE_REMINDERS in permissions ||
                    StaffPermissions.SEND_DUE_MESSAGE in permissions,
                syncAuditLogs = StaffPermissions.MANAGE_STAFF in permissions ||
                    StaffPermissions.VIEW_REPORTS in permissions
            )

            else -> CoreDataSyncPlan()
        }
    }

    fun allowsScope(plan: CoreDataSyncPlan, scope: InstituteRefreshScope): Boolean = when (scope) {
        InstituteRefreshScope.STUDENTS -> plan.syncStudents
        InstituteRefreshScope.BATCHES -> plan.syncBatches
        InstituteRefreshScope.ENROLLMENTS -> plan.syncEnrollments
        InstituteRefreshScope.STAFF -> plan.syncStaff
        InstituteRefreshScope.FINANCE -> plan.syncFinance
        InstituteRefreshScope.EXPENSES -> plan.syncExpenses
        InstituteRefreshScope.ATTENDANCE -> plan.syncAttendance || plan.syncStaffAttendance
    }

    private fun normalizeRole(role: String?): String = role
        .orEmpty()
        .trim()
        .replace("_", "")
        .lowercase()
}
