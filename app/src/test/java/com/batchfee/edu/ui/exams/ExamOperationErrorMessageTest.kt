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
    fun authorizedSessionDoesNotExposeRawPermissionError() {
        assertEquals(
            "Your exam access could not be verified. Please log out, log in again, and retry.",
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
