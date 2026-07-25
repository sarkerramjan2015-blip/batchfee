package com.batchfee.edu.data.dao

import androidx.room.*
import com.batchfee.edu.data.models.EnquiryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EnquiryDao {
    @Query(
        "SELECT * FROM enquiries WHERE instituteId = :instituteId AND archivedAtMs IS NULL " +
            "ORDER BY enquiryDateMs DESC, createdAtMs DESC"
    )
    fun getEnquiriesByInstitute(instituteId: String): Flow<List<EnquiryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEnquiry(enquiry: EnquiryEntity)

    @Update
    suspend fun updateEnquiry(enquiry: EnquiryEntity)
}
