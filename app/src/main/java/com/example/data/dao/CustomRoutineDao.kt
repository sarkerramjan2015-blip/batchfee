package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.batchfee.edu.data.models.CustomRoutineEntity
import com.batchfee.edu.data.models.CustomRoutineEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomRoutineDao {

    @Query("SELECT * FROM custom_routines WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY createdAtMs DESC")
    fun getRoutines(instituteId: String): Flow<List<CustomRoutineEntity>>

    @Query("SELECT * FROM custom_routines WHERE id = :routineId AND instituteId = :instituteId LIMIT 1")
    fun getRoutine(routineId: String, instituteId: String): Flow<CustomRoutineEntity?>

    @Query("SELECT * FROM custom_routines WHERE id = :routineId AND instituteId = :instituteId LIMIT 1")
    suspend fun getRoutineOnce(routineId: String, instituteId: String): CustomRoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoutine(routine: CustomRoutineEntity)

    @Query("UPDATE custom_routines SET archivedAtMs = :archivedAtMs, updatedAtMs = :updatedAtMs WHERE id = :routineId AND instituteId = :instituteId")
    suspend fun archiveRoutine(routineId: String, instituteId: String, archivedAtMs: Long, updatedAtMs: Long)

    @Query("SELECT * FROM custom_routine_entries WHERE routineId = :routineId ORDER BY dayIndex ASC, sortOrder ASC, startMinutes ASC")
    fun getEntries(routineId: String): Flow<List<CustomRoutineEntryEntity>>

    @Query("SELECT * FROM custom_routine_entries WHERE routineId = :routineId ORDER BY dayIndex ASC, sortOrder ASC, startMinutes ASC")
    suspend fun getEntriesOnce(routineId: String): List<CustomRoutineEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(entries: List<CustomRoutineEntryEntity>)

    @Query("DELETE FROM custom_routine_entries WHERE routineId = :routineId AND id NOT IN (:keepIds)")
    suspend fun deleteEntriesNotIn(routineId: String, keepIds: List<String>)

    @Query("DELETE FROM custom_routine_entries WHERE routineId = :routineId")
    suspend fun deleteAllEntries(routineId: String)
}
