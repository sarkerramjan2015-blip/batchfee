package com.batchfee.edu.ui.dashboard

import com.batchfee.edu.domain.AccessControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardQuickActionPolicyTest {
    @Test
    fun centralMenuDefinesEachQuickActionOnceAndUsesValidRoutes() {
        val routes = DashboardQuickActionPolicy.centralActionRoutes
        assertEquals(routes.size, routes.toSet().size)
        assertEquals(
            setOf("AddStudentRoute", "AddStaffRoute", "AddBatchRoute", "CreateExamRoute", "AddExpenseRoute", "UnifiedCollectRoute", "AttendanceRoute"),
            routes.toSet()
        )
        assertTrue(routes.all(AccessControl::isKnownRoute))
    }

    @Test
    fun centralMenuShowsOnlyAuthorizedActions() {
        val allowed = setOf("AddStudentRoute", "AttendanceRoute")
        assertEquals(allowed.toList(), DashboardQuickActionPolicy.authorizedRoutes { it in allowed })
        assertTrue(DashboardQuickActionPolicy.authorizedRoutes { false }.isEmpty())
    }

    @Test
    fun ownerEquivalentAccessRetainsEveryCentralAction() {
        assertEquals(
            DashboardQuickActionPolicy.centralActionRoutes,
            DashboardQuickActionPolicy.authorizedRoutes { true }
        )
    }
}
