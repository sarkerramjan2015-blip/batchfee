package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.batchfee.edu.data.models.StaffEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StaffDao {
    @Query("SELECT * FROM staff WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY fullName ASC")
    fun getStaffByInstitute(instituteId: String): Flow<List<StaffEntity>>

    @Query("SELECT * FROM staff WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY fullName ASC")
    suspend fun getStaffByInstituteAsList(instituteId: String): List<StaffEntity>

    @Query("SELECT * FROM staff WHERE instituteId = :instituteId AND status = 'active' AND archivedAtMs IS NULL ORDER BY fullName ASC")
    fun getActiveStaff(instituteId: String): Flow<List<StaffEntity>>

    @Query("SELECT * FROM staff WHERE id = :staffId AND instituteId = :instituteId LIMIT 1")
    fun getStaffById(staffId: String, instituteId: String): Flow<StaffEntity?>

    @Query("SELECT * FROM staff WHERE id = :staffId AND instituteId = :instituteId LIMIT 1")
    suspend fun getStaffByIdOnce(staffId: String, instituteId: String): StaffEntity?

    @Query("SELECT * FROM staff WHERE staffCode = :staffCode AND archivedAtMs IS NULL LIMIT 1")
    suspend fun getStaffByCodeOnce(staffCode: String): StaffEntity?

    @Query("""
        SELECT * FROM staff
        WHERE instituteId = :instituteId
          AND archivedAtMs IS NULL
          AND (
            fullName LIKE '%' || :query || '%'
            OR roleTitle LIKE '%' || :query || '%'
            OR staffCode LIKE '%' || :query || '%'
            OR phone LIKE '%' || :query || '%'
          )
        ORDER BY fullName ASC
    """)
    fun searchStaff(instituteId: String, query: String): Flow<List<StaffEntity>>

    @Query("SELECT COUNT(*) FROM staff WHERE instituteId = :instituteId AND archivedAtMs IS NULL")
    fun countStaff(instituteId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStaff(staff: StaffEntity)

    @Update
    suspend fun updateStaff(staff: StaffEntity)

    @Query("UPDATE staff SET archivedAtMs = :archivedAtMs WHERE id = :staffId AND instituteId = :instituteId")
    suspend fun archiveStaff(instituteId: String, staffId: String, archivedAtMs: Long)
}
