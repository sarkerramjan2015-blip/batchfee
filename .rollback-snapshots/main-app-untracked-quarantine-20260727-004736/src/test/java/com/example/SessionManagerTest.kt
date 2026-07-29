package com.batchfee.edu

import com.batchfee.edu.domain.SessionErrorClassifier
import com.batchfee.edu.domain.SessionFailureKind
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.SessionState
import com.batchfee.edu.domain.SubscriptionDateFormatter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class SessionManagerTest {
    @After
    fun resetSession() {
        SessionManager.logout()
    }

    @Test
    fun validLoginPublishesAuthenticatedAccountState() {
        SessionManager.login("user-1", "institute-1", "Staff", "fees, students")

        assertEquals(SessionState.Authenticated, SessionManager.sessionState.value)
        assertEquals("user-1", SessionManager.currentUserId.value)
        assertEquals("institute-1", SessionManager.currentInstituteId.value)
        assertTrue(SessionManager.hasPermission("fees"))
    }

    @Test
    fun restoringStateIsNotAuthenticatedAndInvalidationCanEndIt() {
        SessionManager.beginRestoringSession()

        assertEquals(SessionState.Loading, SessionManager.sessionState.value)
        assertFalse(SessionManager.isLoggedIn())
        assertTrue(SessionManager.expireSession())
        assertEquals(SessionState.SessionExpired, SessionManager.sessionState.value)
    }

    @Test
    fun confirmedExpiryClearsAccountStateExactlyOnce() {
        SessionManager.login("user-1", "institute-1", "Staff", "fees")

        assertTrue(SessionManager.expireSession())
        assertFalse(SessionManager.expireSession())
        assertEquals(SessionState.SessionExpired, SessionManager.sessionState.value)
        assertEquals(
            "Your session has expired. Please log in again.",
            SessionManager.sessionNotice.value
        )
        assertNull(SessionManager.currentUserId.value)
        assertNull(SessionManager.currentInstituteId.value)
        assertNull(SessionManager.currentUserRole.value)
        assertTrue(SessionManager.currentStaffPermissions.value.isEmpty())
    }

    @Test
    fun expiryNoticeIsConsumedOnlyWhenExplicitlyAcknowledged() {
        SessionManager.login("user-1", "institute-1", "InstituteOwner")
        SessionManager.expireSession()

        val notice = SessionManager.sessionNotice.value!!
        assertEquals("Your session has expired. Please log in again.", notice)
        SessionManager.consumeSessionNotice(notice)

        assertNull(SessionManager.sessionNotice.value)
        assertEquals(SessionState.SessionExpired, SessionManager.sessionState.value)
    }

    @Test
    fun manualLogoutClearsStateWithoutExpiredNotice() {
        SessionManager.login("user-1", "institute-1", "InstituteOwner")
        SessionManager.logout()

        assertEquals(SessionState.Unauthenticated, SessionManager.sessionState.value)
        assertNull(SessionManager.currentUserId.value)
        assertNull(SessionManager.sessionNotice.value)
    }

    @Test
    fun unauthenticatedAndPermissionDeniedAreClassifiedSeparately() {
        assertEquals(
            SessionFailureKind.SESSION_INVALID,
            SessionErrorClassifier.classifyFirestoreCode("UNAUTHENTICATED")
        )
        assertEquals(
            SessionFailureKind.PERMISSION_DENIED,
            SessionErrorClassifier.classifyFirestoreCode("PERMISSION_DENIED")
        )
        assertEquals(
            SessionFailureKind.TRANSIENT_OR_UNKNOWN,
            SessionErrorClassifier.classifyFirestoreCode("UNAVAILABLE")
        )
    }

    @Test
    fun zeroOrMissingSubscriptionTimestampIsNotEpochDate() {
        val formatter = SimpleDateFormat("dd MMM yyyy", Locale.US)

        assertEquals("Not available", SubscriptionDateFormatter.format(null, formatter))
        assertEquals("Not available", SubscriptionDateFormatter.format(0L, formatter))
        assertEquals("01 Jan 2025", SubscriptionDateFormatter.format(1_735_689_600_000L, formatter))
    }
}
