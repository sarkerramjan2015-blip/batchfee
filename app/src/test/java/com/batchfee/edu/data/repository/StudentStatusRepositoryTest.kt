package com.batchfee.edu.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.StudentEntity
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StudentStatusRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var gateway: ScriptedStudentStatusGateway
    private lateinit var repository: StudentStatusRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = ScriptedStudentStatusGateway()
        repository = StudentStatusRepository(db, gateway)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun validOwnerCanActivateStudent() = runTest {
        db.studentDao().insertStudent(student(status = "inactive"))
        gateway.responder = { }

        repository.setStatus(student(status = "inactive"), becomingActive = true)

        val stored = db.studentDao().getStudentById(STUDENT_ID, INSTITUTE_ID).first()!!
        assertEquals("active", stored.status)
        assertEquals("active", gateway.requests.single().status)
    }

    @Test
    fun validOwnerCanInactivateStudent() = runTest {
        db.studentDao().insertStudent(student(status = "active"))
        gateway.responder = { }

        repository.setStatus(student(status = "active"), becomingActive = false)

        val stored = db.studentDao().getStudentById(STUDENT_ID, INSTITUTE_ID).first()!!
        assertEquals("inactive", stored.status)
        assertEquals("inactive", gateway.requests.single().status)
    }

    @Test
    fun missingFirebaseUserChangesNothingAndReportsSessionExpired() = runTest {
        db.studentDao().insertStudent(student(status = "active"))
        gateway.responder = { throw StudentStatusSessionException() }

        assertThrowsSuspend<StudentStatusSessionException> {
            repository.setStatus(student(status = "active"), becomingActive = false)
        }

        assertEquals(
            "active",
            db.studentDao().getStudentById(STUDENT_ID, INSTITUTE_ID).first()!!.status
        )
        assertEquals(
            "Session expired. Please sign in again.",
            studentStatusErrorMessage(StudentStatusSessionException())
        )
    }

    @Test
    fun permissionDeniedDoesNotRetryOrLogOutAndKeepsStatus() = runTest {
        db.studentDao().insertStudent(student(status = "active"))
        gateway.responder = {
            throw functionsException(
                FirebaseFunctionsException.Code.PERMISSION_DENIED,
                "forbidden"
            )
        }

        assertThrowsSuspend<FirebaseFunctionsException> {
            repository.setStatus(student(status = "active"), becomingActive = false)
        }

        assertEquals(1, gateway.requestCount)
        assertEquals(
            "active",
            db.studentDao().getStudentById(STUDENT_ID, INSTITUTE_ID).first()!!.status
        )
        assertEquals(
            "You do not have permission to update this student's status.",
            studentStatusErrorMessage(
                functionsException(
                    FirebaseFunctionsException.Code.PERMISSION_DENIED,
                    "forbidden"
                )
            )
        )
    }

    @Test
    fun offlineFailureDoesNotFalselyChangeStatus() = runTest {
        db.studentDao().insertStudent(student(status = "inactive"))
        gateway.responder = { throw FirebaseNetworkException("offline") }

        assertThrowsSuspend<FirebaseNetworkException> {
            repository.setStatus(student(status = "inactive"), becomingActive = true)
        }

        assertEquals(
            "inactive",
            db.studentDao().getStudentById(STUDENT_ID, INSTITUTE_ID).first()!!.status
        )
        assertEquals(
            "Student status could not be updated. Check your connection and try again.",
            studentStatusErrorMessage(FirebaseNetworkException("offline"))
        )
    }

    private suspend inline fun <reified T : Exception> assertThrowsSuspend(
        crossinline block: suspend () -> Unit
    ) {
        var thrown: Exception? = null
        try {
            block()
        } catch (error: Exception) {
            thrown = error
        }
        assertTrue("Expected ${T::class.java.simpleName}", thrown is T)
    }

    private fun student(status: String) = StudentEntity(
        id = STUDENT_ID,
        instituteId = INSTITUTE_ID,
        studentCode = "ST-9001",
        fullName = "Ayesha Rahman",
        photoUri = null,
        gender = null,
        dateOfBirthMs = null,
        phone = "01710000000",
        email = null,
        address = null,
        schoolName = null,
        className = null,
        guardianName = null,
        guardianPhone = null,
        guardianEmail = null,
        emergencyContact = null,
        bloodGroup = null,
        admissionDateMs = 1_700_000_000_000L,
        status = status,
        notes = null,
        createdAtMs = 1L,
        updatedAtMs = 1L,
        archivedAtMs = null,
        isAppAccessEnabled = false
    )

    private companion object {
        const val INSTITUTE_ID = "inst-status"
        const val STUDENT_ID = "student-status"
    }
}

private class ScriptedStudentStatusGateway : StudentStatusGateway {
    val requests = mutableListOf<StudentEntity>()
    var responder: suspend (StudentEntity) -> Unit = { error("No response configured") }

    val requestCount: Int get() = requests.size

    override suspend fun updateStatus(student: StudentEntity) {
        requests += student
        responder(student)
    }
}
