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
class ReceiptMigrationTest {
    @Test
    fun v15ToV16KeepsExistingReceiptAndPaymentFinancialValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "receipt-migration-" + System.nanoTime() + ".db"
        context.deleteDatabase(name)
        try {
            createVersion15Database(context, name).also { helper ->
                helper.writableDatabase
                helper.close()
            }

            val upgradedHelper = openVersion16Database(context, name)
            val upgraded = upgradedHelper.writableDatabase
            upgraded.query(
                "SELECT paidAmount, dueAmount, totalAmount, instituteNameSnapshot FROM receipts WHERE id = 'receipt-1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(600.0, cursor.getDouble(0), 0.0001)
                assertEquals(400.0, cursor.getDouble(1), 0.0001)
                assertEquals(1_000.0, cursor.getDouble(2), 0.0001)
                assertTrue(cursor.isNull(3))
            }
            upgraded.query("SELECT amount, paymentMethod FROM payments WHERE id = 'payment-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(600.0, cursor.getDouble(0), 0.0001)
                assertEquals("bkash", cursor.getString(1))
            }
            upgraded.query("PRAGMA table_info(receipts)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(1)
                assertTrue("New nullable snapshot column is present", "collectorNameSnapshot" in columns)
                assertTrue("New nullable snapshot column is present", "paymentStatusSnapshot" in columns)
                assertFalse("Migration must not remove the legacy receipt text column", "receiptText" !in columns)
            }
            upgradedHelper.close()
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun createVersion15Database(context: Context, name: String): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE receipts (" +
                                "id TEXT NOT NULL PRIMARY KEY, instituteId TEXT NOT NULL, paymentId TEXT NOT NULL, " +
                                "feeId TEXT NOT NULL, studentId TEXT NOT NULL, receiptNumber TEXT NOT NULL, " +
                                "receiptDateMs INTEGER NOT NULL, totalAmount REAL NOT NULL, paidAmount REAL NOT NULL, " +
                                "dueAmount REAL NOT NULL, paymentMethod TEXT NOT NULL, receiptText TEXT, createdAtMs INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE payments (" +
                                "id TEXT NOT NULL PRIMARY KEY, instituteId TEXT NOT NULL, feeId TEXT NOT NULL, " +
                                "studentId TEXT NOT NULL, amount REAL NOT NULL, paymentMethod TEXT NOT NULL, " +
                                "transactionId TEXT, receiptNumber TEXT NOT NULL, paymentDateMs INTEGER NOT NULL, " +
                                "collectedByUserId TEXT NOT NULL, status TEXT NOT NULL, note TEXT, " +
                                "createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "INSERT INTO receipts VALUES " +
                                "('receipt-1', 'inst', 'payment-1', 'fee-1', 'student-1', 'REC-1', 1000, " +
                                "1000.0, 600.0, 400.0, 'bkash', 'legacy receipt', 1000)"
                        )
                        db.execSQL(
                            "INSERT INTO payments VALUES " +
                                "('payment-1', 'inst', 'fee-1', 'student-1', 600.0, 'bkash', NULL, 'REC-1', " +
                                "1000, 'collector-1', 'completed', NULL, 1000, 1000)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

    private fun openVersion16Database(context: Context, name: String): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("Expected existing v15 database.")

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(15, oldVersion)
                        assertEquals(16, newVersion)
                        AppDatabase.MIGRATION_15_16.migrate(db)
                    }
                })
                .build()
        )
}
