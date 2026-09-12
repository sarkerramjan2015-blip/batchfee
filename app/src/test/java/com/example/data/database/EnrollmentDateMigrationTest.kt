package com.batchfee.edu.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnrollmentDateMigrationTest {
    @Test fun preservesLegacyRowsAndAddsNullablePolicy() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext<Context>())
                .name(null).callback(object : SupportSQLiteOpenHelper.Callback(41) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build()
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("CREATE TABLE batch_students(id TEXT PRIMARY KEY NOT NULL, joinedAtMs INTEGER NOT NULL)")
            db.execSQL("INSERT INTO batch_students VALUES ('retained', 123)")
            AppDatabase.MIGRATION_41_42.migrate(db)
            db.query("SELECT joinedAtMs, admissionDateLinked FROM batch_students WHERE id='retained'").use {
                assertTrue(it.moveToFirst())
                assertEquals(123L, it.getLong(0))
                assertTrue(it.isNull(1))
            }
        } finally { helper.close() }
    }

    @Test fun customFeeTimelineMigrationPreservesLegacyEnrollmentData() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext<Context>())
                .name(null).callback(object : SupportSQLiteOpenHelper.Callback(43) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build()
        )
        try {
            val db = helper.writableDatabase
            db.execSQL(
                "CREATE TABLE batch_students(" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "customMonthlyFeeAmount REAL, " +
                    "customFeeEffectiveFromPeriod TEXT)"
            )
            db.execSQL("INSERT INTO batch_students VALUES ('retained', 700, 'Sep 2026')")

            AppDatabase.MIGRATION_43_44.migrate(db)

            db.query(
                "SELECT customMonthlyFeeAmount, customFeeEffectiveFromPeriod, " +
                    "customFeePolicyTimeline FROM batch_students WHERE id='retained'"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(700.0, it.getDouble(0), 0.0)
                assertEquals("Sep 2026", it.getString(1))
                assertTrue(it.isNull(2))
            }
        } finally { helper.close() }
    }

    @Test fun attendanceLateFieldsMigrationPreservesLegacyRows() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext<Context>())
                .name(null).callback(object : SupportSQLiteOpenHelper.Callback(44) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build()
        )
        try {
            val db = helper.writableDatabase
            db.execSQL(
                "CREATE TABLE attendance(" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "status TEXT NOT NULL)"
            )
            db.execSQL("INSERT INTO attendance VALUES ('legacy-holiday', 'holiday')")

            AppDatabase.MIGRATION_44_45.migrate(db)

            db.query(
                "SELECT status, arrivalTimeMs, scheduledStartTimeMs, lateByMinutes " +
                    "FROM attendance WHERE id='legacy-holiday'"
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("holiday", it.getString(0))
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
                assertTrue(it.isNull(3))
            }
        } finally { helper.close() }
    }
}
