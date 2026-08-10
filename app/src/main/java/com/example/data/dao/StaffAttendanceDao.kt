package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.batchfee.edu.data.models.StaffAttendanceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StaffAttendanceDao {
    @Query("SELECT * FROM staff_attendance WHERE instituteId = :instituteId AND attendanceDateMs >= :startOfDayMs AND attendanceDateMs < :endOfDayMs")
    fun getAttendanceByDate(instituteId: String, startOfDayMs: Long, endOfDayMs: Long): Flow<List<StaffAttendanceEntity>>

    @Query("SELECT * FROM staff_attendance WHERE instituteId = :instituteId AND attendanceDateMs >= :startMs AND attendanceDateMs <= :endMs")
    fun getAttendanceByDateRange(instituteId: String, startMs: Long, endMs: Long): Flow<List<StaffAttendanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAttendance(attendance: StaffAttendanceEntity)

    @Query("DELETE FROM staff_attendance WHERE id = :id")
    suspend fun deleteAttendance(id: String)
}
