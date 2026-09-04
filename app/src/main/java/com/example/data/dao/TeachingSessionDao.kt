package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.batchfee.edu.data.models.TeachingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TeachingSessionDao {
    @Query("""
        SELECT * FROM teaching_sessions
        WHERE instituteId = :instituteId AND staffId = :staffId AND deletedAtMs IS NULL
        ORDER BY sessionDateMs DESC, createdAtMs DESC
    """)
    fun getSessionsForStaff(instituteId: String, staffId: String): Flow<List<TeachingSessionEntity>>

    @Query("""
        SELECT * FROM teaching_sessions
        WHERE instituteId = :instituteId
          AND sessionDateMs >= :fromMs AND sessionDateMs < :untilMs
          AND deletedAtMs IS NULL
        ORDER BY sessionDateMs ASC, createdAtMs ASC
    """)
    suspend fun getSessionsForDate(instituteId: String, fromMs: Long, untilMs: Long): List<TeachingSessionEntity>

    @Query("""
        SELECT * FROM teaching_sessions
        WHERE instituteId = :instituteId AND staffId = :staffId
          AND sessionDateMs >= :fromMs AND sessionDateMs < :untilMs
          AND salaryId IS NULL AND deletedAtMs IS NULL
        ORDER BY sessionDateMs ASC, createdAtMs ASC
    """)
    suspend fun getUnpaidSessionsForPeriod(
        instituteId: String,
        staffId: String,
        fromMs: Long,
        untilMs: Long
    ): List<TeachingSessionEntity>

    @Query("SELECT * FROM teaching_sessions WHERE instituteId = :instituteId AND salaryId = :salaryId AND deletedAtMs IS NULL")
    suspend fun getSessionsForSalary(instituteId: String, salaryId: String): List<TeachingSessionEntity>

    @Query("SELECT * FROM teaching_sessions WHERE instituteId = :instituteId AND sessionKey = :sessionKey LIMIT 1")
    suspend fun getBySessionKey(instituteId: String, sessionKey: String): TeachingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TeachingSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<TeachingSessionEntity>)

    @Query("UPDATE teaching_sessions SET deletedAtMs = :deletedAtMs, updatedAtMs = :updatedAtMs WHERE id = :sessionId AND instituteId = :instituteId")
    suspend fun softDelete(instituteId: String, sessionId: String, deletedAtMs: Long, updatedAtMs: Long)

    @Query("DELETE FROM teaching_sessions WHERE id = :sessionId AND instituteId = :instituteId")
    suspend fun deleteSession(instituteId: String, sessionId: String)

    @Query("DELETE FROM teaching_sessions WHERE instituteId = :instituteId")
    suspend fun deleteAllForInstitute(instituteId: String)
}
