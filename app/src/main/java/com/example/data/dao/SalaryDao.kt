package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.batchfee.edu.data.models.SalaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SalaryDao {
    @Query("SELECT * FROM salaries WHERE instituteId = :instituteId AND cancelledAtMs IS NULL ORDER BY createdAtMs DESC")
    fun getSalariesByInstitute(instituteId: String): Flow<List<SalaryEntity>>

    @Query("SELECT * FROM salaries WHERE id = :salaryId AND instituteId = :instituteId LIMIT 1")
    suspend fun getSalaryById(salaryId: String, instituteId: String): SalaryEntity?
    
    @Query("SELECT * FROM salaries WHERE id = :salaryId AND instituteId = :instituteId LIMIT 1")
    fun getSalaryByIdFlow(salaryId: String, instituteId: String): Flow<SalaryEntity?>

    @Query("SELECT * FROM salaries WHERE staffId = :staffId AND instituteId = :instituteId AND cancelledAtMs IS NULL ORDER BY createdAtMs DESC")
    fun getSalariesByStaff(staffId: String, instituteId: String): Flow<List<SalaryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSalary(salary: SalaryEntity)

    @Update
    suspend fun updateSalary(salary: SalaryEntity)
}
