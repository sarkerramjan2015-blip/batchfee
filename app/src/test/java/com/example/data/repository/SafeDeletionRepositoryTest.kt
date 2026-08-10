package com.batchfee.edu.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.data.models.StudentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafeDeletionRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var gateway: ScriptedDeletionGateway
    private lateinit var repository: SafeDeletionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = ScriptedDeletionGateway()
        repository = SafeDeletionRepository(db, gateway)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun canonicalStudentArchiveHidesOnlyStudentAndRetainsFinancialHistory() = runTest {
        db.studentDao().insertStudent(student())
        db.feeDao().insertFee(fee())
        gateway.responder = { request -> archiveResult(request) }

        repository.archiveStudent(student(), "Student left the institute")

        val archived = db.studentDao().getStudentById(STUDENT_ID, INSTITUTE_ID).first()!!
        assertEquals("archived", archived.status)
        assertNotNull(archived.archivedAtMs)
        assertEquals(false, archived.isAppAccessEnabled)
        assertEquals(1, db.feeDao().getFeeIdsForStudent(INSTITUTE_ID, STUDENT_ID).size)
        assertEquals("completed", db.safeDeletionDao()
            .getOperation(INSTITUTE_ID, gateway.requests.single().getValue("operationId") as String)?.status)
    }

    @Test
    fun uncertainCloudResultKeepsLocalRecordAndReplaysExactOperation() = runTest {
        db.studentDao().insertStudent(student())
        gateway.responder = { throw IllegalStateException("response lost") }

        assertThrowsSuspend<SafeDeletionPendingException> {
            repository.archiveStudent(student(), "Student left the institute")
        }

        assertNull(db.studentDao().getStudentById(STUDENT_ID, INSTITUTE_ID).first()!!.archivedAtMs)
        val operationId = gateway.requests.single().getValue("operationId") as String
        assertEquals("pending", db.safeDeletionDao().getOperation(INSTITUTE_ID, operationId)?.status)

        gateway.responder = { request -> archiveResult(request) }
        repository.replayAllPending()

        assertNotNull(db.studentDao().getStudentById(STUDENT_ID, INSTITUTE_ID).first()!!.archivedAtMs)
        assertEquals(listOf(operationId, operationId), gateway.requests.map { it["operationId"] })
        assertEquals("completed", db.safeDeletionDao().getOperation(INSTITUTE_ID, operationId)?.status)
    }

    @Test
    fun batchArchiveNeverDeletesBatchOrPostedFee() = runTest {
        db.batchDao().insertBatch(batch())
        db.feeDao().insertFee(fee())
        gateway.responder = { request -> archiveResult(request) }

        repository.archiveBatch(batch(), "Batch completed")

        val archived = db.batchDao().getBatchById(BATCH_ID, INSTITUTE_ID).first()!!
        assertEquals("archived", archived.status)
        assertNotNull(archived.archivedAtMs)
        assertNotNull(db.feeDao().getFeeById(FEE_ID, INSTITUTE_ID))
    }

    @Test
    fun trustedRejectionDoesNotMutateRoomOrAutoRetry() = runTest {
        db.studentDao().insertStudent(student())
        gateway.responder = { throw SafeDeletionRejectedException("Not allowed") }

        assertThrowsSuspend<SafeDeletionRejectedException> {
            repository.archiveStudent(student(), "Unauthorized archive")
        }

        val operationId = gateway.requests.single().getValue("operationId") as String
        assertNull(db.studentDao().getStudentById(STUDENT_ID, INSTITUTE_ID).first()!!.archivedAtMs)
        assertEquals("failed", db.safeDeletionDao().getOperation(INSTITUTE_ID, operationId)?.status)
        repository.replayAllPending()
        assertEquals(1, gateway.requests.size)
    }

    private fun archiveResult(request: Map<String, Any?>) = SafeDeletionResult(
        operationId = request.getValue("operationId") as String,
        instituteId = request.getValue("instituteId") as String,
        entityType = request.getValue("entityType") as String,
        entityId = request.getValue("entityId") as String,
        action = "archive",
        status = "archived",
        archivedAtMs = 10_000L,
        retentionUntilMs = 20_000L,
        isAppAccessEnabled = false,
        subscriptionStatus = if (request["entityType"] == "institute") "deletion_pending" else null,
        authCleanupState = "complete",
        mediaCleanupState = "retained",
        hardDeleteAllowed = false
    )

    private fun student() = StudentEntity(
        id = STUDENT_ID,
        instituteId = INSTITUTE_ID,
        studentCode = "ST-1",
        fullName = "Retained Student",
        photoUri = "https://media.example/student.jpg",
        gender = null,
        dateOfBirthMs = null,
        phone = null,
        email = null,
        address = null,
        schoolName = null,
        className = null,
        guardianName = null,
        guardianPhone = null,
        guardianEmail = null,
        emergencyContact = null,
        bloodGroup = null,
        admissionDateMs = 1_000L,
        status = "active",
        notes = null,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        archivedAtMs = null,
        isAppAccessEnabled = true
    )

    private fun batch() = BatchEntity(
        id = BATCH_ID,
        instituteId = INSTITUTE_ID,
        batchCode = "BAT-1",
        name = "Retained Batch",
        subject = null,
        className = null,
        teacherName = null,
        monthlyFeeAmount = 1_000.0,
        admissionFeeAmount = 0.0,
        startDateMs = 1_000L,
        endDateMs = null,
        scheduleDays = null,
        startTime = null,
        endTime = null,
        maxStudents = 50,
        status = "active",
        description = null,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        archivedAtMs = null
    )

    private fun fee() = FeeEntity(
        id = FEE_ID,
        instituteId = INSTITUTE_ID,
        studentId = STUDENT_ID,
        batchId = BATCH_ID,
        feePeriod = "May 2026",
        feeType = "monthly_fee",
        dueDateMs = 2_000L,
        baseAmount = 1_000.0,
        discountAmount = 0.0,
        lateFeeAmount = 0.0,
        totalAmount = 1_000.0,
        paidAmount = 500.0,
        dueAmount = 500.0,
        status = "partially_paid",
        note = null,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        cancelledAtMs = null,
        businessKey = "retained-fee-key",
        ledgerVersion = 1
    )

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

    private companion object {
        const val INSTITUTE_ID = "inst-delete"
        const val STUDENT_ID = "student-delete"
        const val BATCH_ID = "batch-delete"
        const val FEE_ID = "fee-retained"
    }
}

private class ScriptedDeletionGateway : SafeDeletionGateway {
    val requests = mutableListOf<Map<String, Any?>>()
    var responder: suspend (Map<String, Any?>) -> SafeDeletionResult = {
        error("No safe-deletion response configured")
    }

    override suspend fun commit(request: Map<String, Any?>): SafeDeletionResult {
        requests += request
        return responder(request)
    }
}
