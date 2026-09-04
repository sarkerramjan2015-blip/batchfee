package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.batchfee.edu.data.models.BulkMessageLogEntity

@Dao
interface BulkMessageLogDao {
    @Insert
    suspend fun insert(log: BulkMessageLogEntity)

    @Query(
        "SELECT COUNT(*) FROM bulk_message_log " +
            "WHERE instituteId = :instituteId AND studentId = :studentId " +
            "AND channel = :channel AND messageText = :messageText AND status = 'sent'"
    )
    suspend fun hasSent(instituteId: String, studentId: String, channel: String, messageText: String): Int
}
