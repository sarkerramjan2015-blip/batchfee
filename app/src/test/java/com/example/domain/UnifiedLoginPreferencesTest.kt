package com.batchfee.edu.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnifiedLoginPreferencesTest {
    @Test fun savesRoleAndIdentifierButNotPasswords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("batchfee_unified_login", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = UnifiedLoginPreferences(context)
        assertEquals("Admin", preferences.loadRole())
        assertEquals("", preferences.loadIdentifier())
        preferences.save("Student", " ST-1025 ")
        assertEquals("Student", preferences.loadRole())
        assertEquals("ST-1025", preferences.loadIdentifier())
        assertEquals(false, context.getSharedPreferences("batchfee_unified_login", Context.MODE_PRIVATE).contains("password"))
    }
}
