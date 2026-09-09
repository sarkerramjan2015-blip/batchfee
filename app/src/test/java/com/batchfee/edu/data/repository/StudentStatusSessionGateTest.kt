package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StudentStatusSessionGateTest {

    @Test
    fun missingFirebaseUserStopsBeforeAnyCall() = runTest {
        var invocations = 0
        val error = assertThrows<StudentStatusSessionException> {
            callStudentStatusUpdate(
                hasUser = { false },
                invoke = { invocations++; "unreachable" }
            )
        }
        assertEquals("Session expired. Please sign in again.", error.message)
        assertEquals(0, invocations)
    }

    @Test
    fun expiredTokenRefreshesOnceAndThenSucceeds() = runTest {
        var invocations = 0
        var refreshes = 0
        val result = callTrustedFunctionOnce(
            refreshToken = { refreshes++; true },
            invoke = {
                invocations++
                if (invocations == 1) {
                    throw functionsException(
                        FirebaseFunctionsException.Code.UNAUTHENTICATED,
                        "Sign in is required."
                    )
                }
                "confirmed"
            }
        )
        assertEquals("confirmed", result)
        assertEquals(1, refreshes)
        assertEquals(2, invocations)
    }

    @Test
    fun repeatedUnauthenticatedRefreshesOnlyOnce() = runTest {
        var invocations = 0
        var refreshes = 0
        val error = assertThrows<FirebaseFunctionsException> {
            callTrustedFunctionOnce(
                refreshToken = { refreshes++; true },
                invoke = {
                    invocations++
                    throw functionsException(
                        FirebaseFunctionsException.Code.UNAUTHENTICATED,
                        "Sign in is required."
                    )
                }
            )
        }
        assertEquals(FirebaseFunctionsException.Code.UNAUTHENTICATED, error.code)
        assertEquals(1, refreshes)
        assertEquals(2, invocations)
    }

    @Test
    fun unauthenticatedWithoutRefreshableTokenIsNotRetried() = runTest {
        var invocations = 0
        val error = assertThrows<FirebaseFunctionsException> {
            callTrustedFunctionOnce(
                refreshToken = { false },
                invoke = {
                    invocations++
                    throw functionsException(
                        FirebaseFunctionsException.Code.UNAUTHENTICATED,
                        "Sign in is required."
                    )
                }
            )
        }
        assertEquals(FirebaseFunctionsException.Code.UNAUTHENTICATED, error.code)
        assertEquals(1, invocations)
    }

    @Test
    fun permissionDeniedIsNeverRefreshedOrRetried() = runTest {
        var invocations = 0
        var refreshes = 0
        val error = assertThrows<FirebaseFunctionsException> {
            callTrustedFunctionOnce(
                refreshToken = { refreshes++; true },
                invoke = {
                    invocations++
                    throw functionsException(
                        FirebaseFunctionsException.Code.PERMISSION_DENIED,
                        "forbidden"
                    )
                }
            )
        }
        assertEquals(FirebaseFunctionsException.Code.PERMISSION_DENIED, error.code)
        assertEquals(0, refreshes)
        assertEquals(1, invocations)
    }

    @Test
    fun sessionGateConvertsPersistentUnauthenticatedToSessionExpired() = runTest {
        val error = assertThrows<StudentStatusSessionException> {
            callStudentStatusUpdate(
                hasUser = { true },
                invoke = {
                    throw functionsException(
                        FirebaseFunctionsException.Code.UNAUTHENTICATED,
                        "Sign in is required."
                    )
                }
            )
        }
        assertEquals("Session expired. Please sign in again.", error.message)
    }

    @Test
    fun sessionGatePassesPermissionDeniedThroughUnchanged() = runTest {
        val error = assertThrows<FirebaseFunctionsException> {
            callStudentStatusUpdate(
                hasUser = { true },
                invoke = {
                    throw functionsException(
                        FirebaseFunctionsException.Code.PERMISSION_DENIED,
                        "forbidden"
                    )
                }
            )
        }
        assertEquals(FirebaseFunctionsException.Code.PERMISSION_DENIED, error.code)
    }

    @Test
    fun successReturnsBackendConfirmation() = runTest {
        val result = callStudentStatusUpdate(hasUser = { true }, invoke = { "ok" })
        assertEquals("ok", result)
    }

    private inline fun <reified T : Exception> assertThrows(block: () -> Unit): T {
        var thrown: Exception? = null
        try {
            block()
        } catch (error: Exception) {
            thrown = error
        }
        assertTrue("Expected ${T::class.java.simpleName}", thrown is T)
        @Suppress("UNCHECKED_CAST")
        return thrown as T
    }
}
