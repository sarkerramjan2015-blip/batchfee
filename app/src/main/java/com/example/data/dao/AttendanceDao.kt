package com.example.data.dao

import androidx.room.*
import com.example.data.models.AttendanceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance WHERE instituteId = :instituteId AND batchId = :batchId AND attendanceDateMs = :dateMs")
    fun getAttendanceForBatchByDate(instituteId: String, batchId: String, dateMs: Long): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE instituteId = :instituteId AND batchId = :batchId AND attendanceDateMs BETWEEN :startMs AND :endMs ORDER BY attendanceDateMs ASC")
    fun getAttendanceForBatchByDateRange(instituteId: String, batchId: String, startMs: Long, endMs: Long): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE instituteId = :instituteId AND attendanceDateMs BETWEEN :startMs AND :endMs ORDER BY attendanceDateMs ASC")
    fun getAttendanceByInstituteDateRange(instituteId: String, startMs: Long, endMs: Long): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE instituteId = :instituteId AND studentId = :studentId AND batchId = :batchId ORDER BY attendanceDateMs DESC")
    fun getAttendanceForStudent(instituteId: String, studentId: String, batchId: String): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE instituteId = :instituteId AND studentId = :studentId AND attendanceDateMs BETWEEN :startMs AND :endMs ORDER BY attendanceDateMs DESC")
    fun getAttendanceForStudentByDateRange(instituteId: String, studentId: String, startMs: Long, endMs: Long): Flow<List<AttendanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAttendance(attendance: AttendanceEntity)

    @Query("DELETE FROM attendance WHERE instituteId = :instituteId AND studentId = :studentId AND batchId = :batchId AND attendanceDateMs = :dateMs")
    suspend fun deleteAttendance(instituteId: String, studentId: String, batchId: String, dateMs: Long)
}
