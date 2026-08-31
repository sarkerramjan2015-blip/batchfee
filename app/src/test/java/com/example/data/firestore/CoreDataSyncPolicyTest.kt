package com.batchfee.edu.data.firestore

import com.batchfee.edu.domain.StaffPermissions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreDataSyncPolicyTest {
    @Test
    fun ownerKeepsCompleteCacheRefreshAndOutboxReplay() {
        val plan = CoreDataSyncPolicy.forSession("InstituteOwner", emptySet())

        assertTrue(plan.syncInstitute)
        assertTrue(plan.replayDeletions)
        assertTrue(plan.replayFinanceOperations)
        assertTrue(plan.syncStudents)
        assertTrue(plan.syncStaff)
        assertTrue(plan.syncAttendance)
        assertTrue(plan.syncStaffAttendance)
        assertTrue(plan.syncAuditLogs)
    }

    @Test
    fun feeViewerDoesNotReadOrMutateUnrelatedCollections() {
        val plan = CoreDataSyncPolicy.forSession(
            "Staff",
            setOf(StaffPermissions.VIEW_FEE_SUMMARY)
        )

        assertTrue(plan.syncInstitute)
        assertTrue(plan.syncStudents)
        assertTrue(plan.syncBatches)
        assertTrue(plan.syncEnrollments)
        assertTrue(plan.syncFinance)
        assertFalse(plan.replayFinanceOperations)
        assertFalse(plan.replayDeletions)
        assertFalse(plan.syncStaff)
        assertFalse(plan.syncAttendance)
        assertFalse(plan.syncExpenses)
    }

    @Test
    fun attendanceOnlyStaffDoesNotQueryStaffAttendance() {
        val plan = CoreDataSyncPolicy.forSession(
            "Staff",
            setOf(StaffPermissions.TAKE_ATTENDANCE)
        )

        assertTrue(plan.syncAttendance)
        assertFalse(plan.syncStaffAttendance)
        assertFalse(plan.syncStaff)
        assertFalse(plan.syncFinance)
    }

    @Test
    fun staffAttendanceOnlyStaffDoesNotQueryStudentAttendance() {
        val plan = CoreDataSyncPolicy.forSession(
            "Staff",
            setOf(StaffPermissions.MANAGE_STAFF_ATTENDANCE)
        )

        assertFalse(plan.syncAttendance)
        assertTrue(plan.syncStaffAttendance)
        assertFalse(plan.syncStudents)
    }

    @Test
    fun unknownAndSuperAdminSessionsDoNotRunTenantCacheRefresh() {
        assertFalse(CoreDataSyncPolicy.forSession(null, emptySet()).syncInstitute)
        assertFalse(CoreDataSyncPolicy.forSession("SuperAdmin", emptySet()).syncInstitute)
    }
}
