package com.batchfee.edu

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.batchfee.edu.data.database.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExamResultMigrationTest {
    @Test
    fun v17ToV18PreservesAcademicHistoryAndAddsSafeDefaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "exam-result-migration-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        try {
            createVersion17Database(context, name).also { helper ->
                helper.writableDatabase
                helper.close()
            }

            val upgradedHelper = openVersion18Database(context, name)
            val upgraded = upgradedHelper.writableDatabase
            upgraded.query("SELECT examName, totalMarks, passingMarks, status, examType, includeInReportCard FROM exams WHERE id = 'exam-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Monthly Test", cursor.getString(0))
                assertEquals(100.0, cursor.getDouble(1), 0.0001)
                assertEquals(40.0, cursor.getDouble(2), 0.0001)
                assertEquals("published", cursor.getString(3))
                assertEquals("Other", cursor.getString(4))
                assertEquals(1, cursor.getInt(5))
            }
            upgraded.query("SELECT marksObtained, grade, position, published, isAbsent FROM results WHERE id = 'result-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(88.5, cursor.getDouble(0), 0.0001)
                assertEquals("A+", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(1, cursor.getInt(3))
                assertEquals(0, cursor.getInt(4))
            }
            upgraded.query("PRAGMA table_info(exams)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(1)
                assertTrue("examType column is present", "examType" in columns)
                assertTrue("includeInReportCard column is present", "includeInReportCard" in columns)
            }
            upgraded.query("PRAGMA table_info(results)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(1)
                assertTrue("isAbsent column is present", "isAbsent" in columns)
                assertFalse("Existing results must not be removed", "marksObtained" !in columns)
            }
            upgradedHelper.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun createVersion17Database(context: Context, name: String): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE exams (id TEXT NOT NULL PRIMARY KEY, instituteId TEXT NOT NULL, batchId TEXT NOT NULL, examName TEXT NOT NULL, subject TEXT, examDateMs INTEGER NOT NULL, totalMarks REAL NOT NULL, passingMarks REAL NOT NULL, teacherName TEXT, note TEXT, status TEXT NOT NULL, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, archivedAtMs INTEGER)")
                        db.execSQL("CREATE TABLE results (id TEXT NOT NULL PRIMARY KEY, instituteId TEXT NOT NULL, examId TEXT NOT NULL, batchId TEXT NOT NULL, studentId TEXT NOT NULL, marksObtained REAL NOT NULL, grade TEXT, position INTEGER, remarks TEXT, published INTEGER NOT NULL, createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO exams VALUES ('exam-1', 'inst-1', 'batch-1', 'Monthly Test', 'Math', 1000, 100.0, 40.0, 'Teacher', NULL, 'published', 1000, 1000, NULL)")
                        db.execSQL("INSERT INTO results VALUES ('result-1', 'inst-1', 'exam-1', 'batch-1', 'student-1', 88.5, 'A+', 1, 'Excellent', 1, 1000, 1000)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

    private fun openVersion18Database(context: Context, name: String): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(18) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("Expected existing v17 database.")

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(17, oldVersion)
                        assertEquals(18, newVersion)
                        AppDatabase.MIGRATION_17_18.migrate(db)
                    }
                })
                .build()
        )
}
