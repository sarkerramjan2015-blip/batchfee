package com.batchfee.edu.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.batchfee.edu.data.models.BackgroundSyncEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundSyncQueueTest {
    @Test fun migrationKeepsExistingData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(42) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                }).build()
        )
        try {
            val sql = helper.writableDatabase
            sql.execSQL("CREATE TABLE retained_student (id TEXT NOT NULL)")
            sql.execSQL("INSERT INTO retained_student VALUES ('keep-me')")
            AppDatabase.MIGRATION_42_43.migrate(sql)
            sql.query("SELECT id FROM retained_student").use { assertTrue(it.moveToFirst()); assertEquals("keep-me", it.getString(0)) }
            sql.query("SELECT COUNT(*) FROM background_sync").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally { helper.close() }
    }

    @Test fun persistsTasksAndSeparatesAccountsInInsertionOrder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "background-sync-test-${java.util.UUID.randomUUID()}"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var db = open()
        try {
            val first = BackgroundSyncEntity("z", "actor", "tenant", "profile", "tenant", "{}", 10)
            db.backgroundSyncDao().put(first)
            db.backgroundSyncDao().put(first.copy(id = "a")) // Same millisecond, insertion order wins.
            db.backgroundSyncDao().put(first.copy(id = "other", actorUid = "another"))
            db.close()
            db = open()
            assertEquals(listOf("z", "a"), db.backgroundSyncDao().pending("actor", "tenant").map { it.id })
            assertTrue(db.backgroundSyncDao().pending("actor", "different-tenant").isEmpty())
            db.backgroundSyncDao().remove("z")
            assertEquals(listOf("a"), db.backgroundSyncDao().pending("actor", "tenant").map { it.id })
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
