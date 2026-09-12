package com.batchfee.edu.domain

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StudentSessionFailurePolicyTest {
    @Test fun onlyConnectivityFailuresPreserveCredentials() {
        assertTrue(isTemporaryStudentSessionFailure(FirebaseNetworkException("offline")))
        assertTrue(isTemporaryStudentSessionFailure(FirebaseFirestoreException("timeout", FirebaseFirestoreException.Code.DEADLINE_EXCEEDED)))
        assertFalse(isTemporaryStudentSessionFailure(FirebaseFirestoreException("revoked", FirebaseFirestoreException.Code.PERMISSION_DENIED)))
        assertFalse(isTemporaryStudentSessionFailure(IllegalStateException("identity mismatch")))
    }
}
