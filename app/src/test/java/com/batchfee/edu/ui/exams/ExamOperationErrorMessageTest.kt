package com.batchfee.edu.ui.exams

import org.junit.Assert.assertEquals
import org.junit.Test

class ExamOperationErrorMessageTest {
    @Test
    fun missingFirebaseSessionRequestsFreshLogin() {
        assertEquals(
            "Your session has expired. Please log in again.",
            examOperationErrorMessage(null, false, false, false, true, "Save failed")
        )
    }

    @Test
    fun staffWithoutExamPermissionGetsActionableMessage() {
        assertEquals(
            "Exam access is not enabled for this staff account. Ask the institute owner to enable Manage Exams.",
            examOperationErrorMessage(
                "PERMISSION_DENIED: Missing or insufficient permissions.",
                true,
                true,
                true,
                false,
                "Save failed"
            )
        )
    }

    @Test
    fun ownerPermissionDenialExplainsSubscriptionOrAccessIssue() {
        assertEquals(
            "Exam changes are blocked because the institute subscription is inactive or your access changed. Ask the platform admin to activate the institute, then log in again.",
            examOperationErrorMessage(
                "PERMISSION_DENIED: Missing or insufficient permissions.",
                true,
                true,
                false,
                true,
                "Save failed"
            )
        )
    }
}
