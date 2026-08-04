package com.batchfee.edu.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionManagerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("batchfee_session_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        SessionManager.initialize(context)
        SessionManager.logout()
    }

    @After
    fun tearDown() {
        SessionManager.logout()
    }

    @Test
    fun expiryClearsSessionAndPersistsNoticeAcrossInitialization() {
        SessionManager.login("user-1", "institute-1", "InstituteOwner")
        SessionManager.expireSession()

        assertFalse(SessionManager.isLoggedIn())
        assertEquals(SessionManager.SESSION_EXPIRED_MESSAGE, SessionManager.sessionNotice.value)

        // Simulate an Activity/process recreation which previously lost the expiry message.
        SessionManager.initialize(context)
        assertEquals(SessionManager.SESSION_EXPIRED_MESSAGE, SessionManager.sessionNotice.value)

        SessionManager.clearSessionNotice()
        assertNull(SessionManager.sessionNotice.value)
    }

    @Test
    fun inactivityCheckUsesTheConfiguredTimeout() {
        SessionManager.login("user-1", "institute-1", "InstituteOwner")
        val exactlyAtDeadline = System.currentTimeMillis() + SessionManager.SESSION_TIMEOUT_MS

        assertTrue(SessionManager.isSessionInactive(exactlyAtDeadline))
    }
}
