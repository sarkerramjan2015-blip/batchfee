package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.batchfee.edu.data.models.ExpenseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY expenseDateMs DESC")
    fun getExpensesByInstitute(instituteId: String): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE instituteId = :instituteId AND archivedAtMs IS NULL ORDER BY expenseDateMs DESC")
    suspend fun getExpensesByInstituteAsList(instituteId: String): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE id = :expenseId AND instituteId = :instituteId LIMIT 1")
    suspend fun getExpenseById(expenseId: String, instituteId: String): ExpenseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity)

    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    @Query("UPDATE expenses SET archivedAtMs = :archivedAtMs WHERE id = :expenseId AND instituteId = :instituteId")
    suspend fun archiveExpense(instituteId: String, expenseId: String, archivedAtMs: Long)
}
