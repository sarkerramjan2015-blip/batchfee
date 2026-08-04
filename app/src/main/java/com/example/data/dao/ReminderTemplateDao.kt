package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.ReminderTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderTemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: ReminderTemplateEntity)
    
    @Query("SELECT * FROM reminder_templates WHERE instituteId = :instituteId")
    fun getTemplatesForInstitute(instituteId: String): Flow<List<ReminderTemplateEntity>>

    @Query("SELECT * FROM reminder_templates WHERE instituteId = :instituteId AND type = :type LIMIT 1")
    suspend fun getTemplateByTypeOnce(instituteId: String, type: String): ReminderTemplateEntity?
}
