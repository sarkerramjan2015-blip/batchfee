package com.batchfee.edu

import com.batchfee.edu.domain.RegistrationApprovalRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationApprovalRulesTest {
    @Test
    fun requiredNameAndNormalizedPhoneAreValidated() {
        assertTrue(RegistrationApprovalRules.isValidSubmission("Rafi", "+880 1712-345678"))
        assertFalse(RegistrationApprovalRules.isValidSubmission(" ", "+8801712345678"))
        assertFalse(RegistrationApprovalRules.isValidSubmission("Rafi", "12345"))
        assertEquals("8801712345678", RegistrationApprovalRules.normalizePhone("+880 1712-345678"))
    }

    @Test
    fun duplicateStudentOrPendingPhoneIsDetectedDespiteFormatting() {
        assertTrue(
            RegistrationApprovalRules.isDuplicatePhone(
                "01712 345678",
                listOf("+8801712345678", "01900000000")
            )
        )
        assertFalse(RegistrationApprovalRules.isDuplicatePhone("01800000000", listOf("01712345678")))
    }

    @Test
    fun onlyPendingRegistrationCanBeApprovedOrRejectedAgain() {
        assertTrue(RegistrationApprovalRules.canApprove(RegistrationApprovalRules.PENDING))
        assertFalse(RegistrationApprovalRules.canApprove(RegistrationApprovalRules.APPROVED))
        assertFalse(RegistrationApprovalRules.canApprove(RegistrationApprovalRules.REJECTED))
    }

    @Test
    fun blankInstituteIdentityHasNoMisleadingBrandName() {
        assertEquals("BatchFee Academy", RegistrationApprovalRules.instituteDisplayName("  BatchFee Academy  "))
        assertNull(RegistrationApprovalRules.instituteDisplayName("  "))
        assertNull(RegistrationApprovalRules.instituteDisplayName(null))
    }
}
