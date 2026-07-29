package com.batchfee.edu

import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.ui.staff.StaffInviteDetails
import com.batchfee.edu.ui.staff.StaffPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffAccessSupportTest {
    @Test
    fun unauthorizedActionIsNotPresentedButGrantedActionIs() {
        assertFalse(StaffPresentation.canPresentAction(false, setOf("view_student"), "manage_staff"))
        assertTrue(StaffPresentation.canPresentAction(false, setOf("manage_staff"), "manage_staff"))
        assertTrue(StaffPresentation.canPresentAction(true, emptySet(), "manage_staff"))
    }

    @Test
    fun missingPhotoUsesStableInitialsFallback() {
        assertEquals("RA", StaffPresentation.initials("  Rahim   Ahmed "))
        assertEquals("?", StaffPresentation.initials("   "))
    }

    @Test
    fun inviteContainsAccountAndInstituteDetailsOnlyForTheCreationHandoff() {
        val invite = StaffInviteDetails(
            staffId = "staff-1",
            staffName = "Rahim Ahmed",
            loginId = "STF001",
            email = "rahim@example.com",
            instituteName = "ICT TOPPERS",
            roleTitle = "Teacher",
            temporaryPassword = "Temp-1234"
        )

        val text = invite.shareText()
        assertTrue(text.contains("ICT TOPPERS"))
        assertTrue(text.contains("STF001"))
        assertTrue(text.contains("rahim@example.com"))
        assertTrue(text.contains("Temp-1234"))
        assertFalse(text.contains("passwordHash"))
    }

    @Test
    fun inactiveOrArchivedStaffIsNotAnActiveAccount() {
        val base = StaffEntity(
            id = "staff-1", instituteId = "inst-1", staffCode = "STF001", fullName = "Rahim Ahmed",
            photoUri = null, roleTitle = "Teacher", phone = null, email = null, address = null,
            joiningDateMs = null, monthlySalary = 0.0, assignedBatchIds = null, status = "active",
            notes = null, permissions = null, createdAtMs = 1L, updatedAtMs = 1L, archivedAtMs = null
        )
        assertTrue(StaffPresentation.isActive(base))
        assertFalse(StaffPresentation.isActive(base.copy(status = "inactive")))
        assertFalse(StaffPresentation.isActive(base.copy(archivedAtMs = 2L)))
    }
}
