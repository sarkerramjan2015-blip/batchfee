package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.BackgroundSyncEntity

@Dao
interface BackgroundSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: BackgroundSyncEntity)
    @Query("SELECT * FROM background_sync WHERE actorUid = :uid AND instituteId = :instituteId ORDER BY rowid")
    suspend fun pending(uid: String, instituteId: String): List<BackgroundSyncEntity>
    @Query("DELETE FROM background_sync WHERE id = :id")
    suspend fun remove(id: String)
    @Query("SELECT COUNT(*) FROM background_sync WHERE instituteId = :instituteId AND kind = 'profile'")
    suspend fun pendingProfiles(instituteId: String): Int
}
