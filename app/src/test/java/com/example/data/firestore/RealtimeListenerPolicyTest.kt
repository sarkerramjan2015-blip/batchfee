package com.batchfee.edu.data.firestore

import com.batchfee.edu.domain.StaffPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeListenerPolicyTest {
    @Test
    fun ownerReceivesCompleteRealtimePlan() {
        val plan = RealtimeListenerPolicy.forSession("InstituteOwner", emptySet())

        assertEquals(
            RealtimeListenerPlan(
                listenInstitute = true,
                listenStudents = true,
                listenBatchStructure = true,
                listenStaff = true,
                listenFinance = true,
                listenExpenses = true
            ),
            plan
        )
    }

    @Test
    fun feeOnlyStaffDoesNotOpenUnrelatedCollections() {
        val plan = RealtimeListenerPolicy.forSession(
            "Staff",
            setOf(StaffPermissions.COLLECT_FEE)
        )

        assertTrue(plan.listenInstitute)
        assertTrue(plan.listenStudents)
        assertTrue(plan.listenBatchStructure)
        assertTrue(plan.listenFinance)
        assertFalse(plan.listenStaff)
        assertFalse(plan.listenExpenses)
    }

    @Test
    fun expenseOnlyStaffOpensOnlyExpenseProtectedCollection() {
        val plan = RealtimeListenerPolicy.forSession(
            "Staff",
            setOf(StaffPermissions.MANAGE_EXPENSES)
        )

        assertTrue(plan.listenInstitute)
        assertTrue(plan.listenExpenses)
        assertFalse(plan.listenStudents)
        assertFalse(plan.listenBatchStructure)
        assertFalse(plan.listenStaff)
        assertFalse(plan.listenFinance)
    }

    @Test
    fun superAdminAndUnknownRolesOpenNoInstituteListeners() {
        assertEquals(
            RealtimeListenerPlan(),
            RealtimeListenerPolicy.forSession("SuperAdmin", emptySet())
        )
        assertEquals(
            RealtimeListenerPlan(),
            RealtimeListenerPolicy.forSession(null, emptySet())
        )
    }
}
