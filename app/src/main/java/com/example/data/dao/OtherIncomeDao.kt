package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.batchfee.edu.data.models.OtherIncomeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OtherIncomeDao {
    @Query("SELECT * FROM other_income WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY incomeDateMs DESC")
    fun getIncomeByInstitute(instituteId: String): Flow<List<OtherIncomeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncome(income: OtherIncomeEntity)

    @Query("DELETE FROM other_income WHERE id = :incomeId AND instituteId = :instituteId")
    suspend fun deleteIncome(instituteId: String, incomeId: String)
}
