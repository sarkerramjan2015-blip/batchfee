package com.batchfee.edu.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinancialLedgerMigrationTest {

    @Test
    fun migration21To22PreservesLegacyLedgerAndAddsIntegrityMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "p005-migration-${UUID.randomUUID()}.db"
        val callback = object : SupportSQLiteOpenHelper.Callback(21) {
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
                "CREATE TABLE fees (id TEXT NOT NULL PRIMARY KEY, instituteId TEXT NOT NULL, totalAmount REAL NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE payments (id TEXT NOT NULL PRIMARY KEY, instituteId TEXT NOT NULL, amount REAL NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE receipts (id TEXT NOT NULL PRIMARY KEY, instituteId TEXT NOT NULL, receiptNumber TEXT NOT NULL)"
            )
            db.execSQL("INSERT INTO fees VALUES ('fee-legacy', 'inst-1', 1000.0)")
            db.execSQL("INSERT INTO payments VALUES ('payment-legacy', 'inst-1', 400.0)")
            db.execSQL("INSERT INTO receipts VALUES ('receipt-legacy', 'inst-1', 'REC-legacy')")

            AppDatabase.MIGRATION_21_22.migrate(db)

            assertEquals(setOf("businessKey", "ledgerVersion"), addedColumns(db, "fees"))
            assertEquals(setOf("operationId", "ledgerVersion"), addedColumns(db, "payments"))
            assertEquals(setOf("operationId", "ledgerVersion"), addedColumns(db, "receipts"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM fees WHERE id = 'fee-legacy'"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM payments WHERE id = 'payment-legacy'"))
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM receipts WHERE id = 'receipt-legacy'"))
            assertEquals(0L, scalarLong(db, "SELECT ledgerVersion FROM fees WHERE id = 'fee-legacy'"))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM payment_reversals"))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM financial_outbox"))
            assertTrue(indexNames(db, "fees").contains("index_fees_instituteId_businessKey"))
            assertTrue(indexNames(db, "payments").contains("index_payments_instituteId_operationId"))
            assertTrue(indexNames(db, "receipts").contains("index_receipts_instituteId_operationId"))
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun addedColumns(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String
    ): Set<String> {
        val expected = when (table) {
            "fees" -> setOf("businessKey", "ledgerVersion")
            else -> setOf("operationId", "ledgerVersion")
        }
        val actual = mutableSetOf<String>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) actual += cursor.getString(nameIndex)
        }
        return actual.intersect(expected)
    }

    private fun indexNames(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String
    ): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA index_list($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) names += cursor.getString(nameIndex)
        }
        return names
    }

    private fun scalarLong(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        query: String
    ): Long = db.query(query).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }
}
