package com.example.data.dao

import androidx.room.*
import com.example.data.models.ReminderTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderTemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: ReminderTemplateEntity)
    
    @Query("SELECT * FROM reminder_templates WHERE instituteId = :instituteId")
    fun getTemplatesForInstitute(instituteId: String): Flow<List<ReminderTemplateEntity>>
}
