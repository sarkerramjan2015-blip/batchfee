package com.batchfee.edu.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StudentCredentialMigrationTest {

    @Test
    fun migration20To21PurgesCredentialColumnsAndPreservesOperationalData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "p004-migration-${UUID.randomUUID()}.db"
        val callback = object : SupportSQLiteOpenHelper.Callback(20) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
            override fun onUpgrade(
                db: androidx.sqlite.db.SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(callback)
                .build()
        )

        try {
            val db = helper.writableDatabase
            db.execSQL(
                """
                CREATE TABLE students (
                    id TEXT NOT NULL PRIMARY KEY,
                    instituteId TEXT NOT NULL,
                    studentCode TEXT NOT NULL,
                    fullName TEXT NOT NULL,
                    photoUri TEXT,
                    gender TEXT,
                    dateOfBirthMs INTEGER,
                    phone TEXT,
                    email TEXT,
                    address TEXT,
                    schoolName TEXT,
                    className TEXT,
                    guardianName TEXT,
                    guardianPhone TEXT,
                    guardianEmail TEXT,
                    emergencyContact TEXT,
                    bloodGroup TEXT,
                    admissionDateMs INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    notes TEXT,
                    createdAtMs INTEGER NOT NULL,
                    updatedAtMs INTEGER NOT NULL,
                    archivedAtMs INTEGER,
                    isAppAccessEnabled INTEGER NOT NULL DEFAULT 0,
                    studentPasswordHash TEXT,
                    appAccessEmail TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO students (
                    id, instituteId, studentCode, fullName, admissionDateMs,
                    status, createdAtMs, updatedAtMs, isAppAccessEnabled,
                    studentPasswordHash, appAccessEmail
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "student-1", "institute-1", "S-001", "Safe Name", 1L,
                    "active", 2L, 3L, 1, "legacy-hash", "legacy@s.batchfee.app"
                )
            )

            AppDatabase.MIGRATION_20_21.migrate(db)

            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(students)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertFalse("studentPasswordHash must be physically removed", "studentPasswordHash" in columns)
            assertFalse("appAccessEmail must be physically removed", "appAccessEmail" in columns)
            assertTrue("isAppAccessEnabled must remain", "isAppAccessEnabled" in columns)

            db.query(
                "SELECT instituteId, studentCode, fullName, isAppAccessEnabled FROM students WHERE id = ?",
                arrayOf("student-1")
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("institute-1", cursor.getString(0))
                assertEquals("S-001", cursor.getString(1))
                assertEquals("Safe Name", cursor.getString(2))
                assertEquals(1, cursor.getInt(3))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }
}
