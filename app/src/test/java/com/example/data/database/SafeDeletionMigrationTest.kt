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
class SafeDeletionMigrationTest {
    @Test
    fun migration22To23AddsDurableDeletionOutboxWithoutTouchingExistingData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "p006-migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(22) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        try {
            val db = helper.writableDatabase
            db.execSQL("CREATE TABLE fees (id TEXT NOT NULL PRIMARY KEY, amount REAL NOT NULL)")
            db.execSQL("INSERT INTO fees VALUES ('fee-retained', 1000.0)")

            AppDatabase.MIGRATION_22_23.migrate(db)

            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM fees WHERE id = 'fee-retained'"))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM deletion_outbox"))
            assertTrue(indexNames(db, "deletion_outbox").contains("index_deletion_outbox_status"))
            assertTrue(indexNames(db, "deletion_outbox").contains(
                "index_deletion_outbox_instituteId_entityType_entityId_action_status"
            ))
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun scalarLong(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun indexNames(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String
    ): Set<String> = buildSet {
        db.query("PRAGMA index_list($table)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(name))
        }
    }
}
