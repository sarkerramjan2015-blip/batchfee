package com.batchfee.edu.data.repository

import com.batchfee.edu.data.firestore.profileUpdateCallPayload
import com.batchfee.edu.data.models.StudentEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thrown when the Firebase identity behind a student status update is missing
 * or could not be restored after the single forced token refresh. Callers must
 * keep the current Room/cloud status unchanged and ask the owner to sign in
 * again instead of reporting a false success.
 */
class StudentStatusSessionException : IllegalStateException("Session expired. Please sign in again.")

interface StudentStatusGateway {
    suspend fun updateStatus(student: StudentEntity)
}

/**
 * Student Active/Inactive writes go through the trusted `updateStudentProfile`
 * callable, which the backend rejects without a valid Firebase actor. The
 * write is therefore refused locally when FirebaseAuth has no current user,
 * before anything is sent or changed.
 */
class FirebaseStudentStatusGateway : StudentStatusGateway {
    private val functions = FirebaseFunctions.getInstance("asia-south1")

    override suspend fun updateStatus(student: StudentEntity) {
        withContext(Dispatchers.IO) {
            callStudentStatusUpdate(
                hasUser = { FirebaseAuth.getInstance().currentUser != null },
                invoke = {
                    callTrustedFunction(
                        functions,
                        "updateStudentProfile",
                        student.profileUpdateCallPayload()
                    )
                }
            )
        }
    }
}

internal suspend fun callStudentStatusUpdate(
    hasUser: () -> Boolean,
    invoke: suspend () -> Any?
): Any? {
    if (!hasUser()) throw StudentStatusSessionException()
    return try {
        invoke()
    } catch (error: FirebaseFunctionsException) {
        if (error.code == FirebaseFunctionsException.Code.UNAUTHENTICATED) {
            // callTrustedFunction already performed exactly one forced token
            // refresh and one replay. The identity is still unusable, so stop.
            throw StudentStatusSessionException()
        }
        throw error
    }
}
