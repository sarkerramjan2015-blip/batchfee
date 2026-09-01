package com.batchfee.edu.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.BatchStudentEntity
import com.batchfee.edu.data.models.StudentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Protects the Home/List/Attendance definition of an operational student. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StudentOperationalCountTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun dashboardAndAttendanceExcludeInactiveOrArchivedStudents() = runTest {
        val active = student(id = "active", status = "active")
        val inactive = student(id = "inactive", status = "inactive")
        val legacyClosed = student(id = "legacy-closed", status = "closed")
        val archived = student(id = "archived", status = "active", archivedAtMs = 1L)
        listOf(active, inactive, legacyClosed, archived).forEach { db.studentDao().insertStudent(it) }

        listOf(active, inactive, legacyClosed, archived).forEach { student ->
            db.batchStudentDao().enrollStudent(
                BatchStudentEntity(
                    id = "enrollment-${student.id}",
                    instituteId = INSTITUTE_ID,
                    batchId = BATCH_ID,
                    studentId = student.id,
                    joinedAtMs = 1L,
                    status = "active",
                    leftAtMs = null
                )
            )
        }

        assertEquals(1, db.studentDao().countActiveStudents(INSTITUTE_ID).first())
        assertEquals(
            listOf(active.id),
            db.batchStudentDao().getStudentsForBatchOnce(BATCH_ID, INSTITUTE_ID).map { it.id }
        )
    }

    private fun student(
        id: String,
        status: String,
        archivedAtMs: Long? = null
    ) = StudentEntity(
        id = id,
        instituteId = INSTITUTE_ID,
        studentCode = "ST-$id",
        fullName = id,
        photoUri = null,
        gender = null,
        dateOfBirthMs = null,
        phone = "01700000000",
        email = null,
        address = null,
        schoolName = null,
        className = null,
        guardianName = null,
        guardianPhone = null,
        guardianEmail = null,
        emergencyContact = null,
        bloodGroup = null,
        admissionDateMs = 1L,
        status = status,
        notes = null,
        createdAtMs = 1L,
        updatedAtMs = 1L,
        archivedAtMs = archivedAtMs
    )

    private companion object {
        const val INSTITUTE_ID = "institute-1"
        const val BATCH_ID = "batch-1"
    }
}
