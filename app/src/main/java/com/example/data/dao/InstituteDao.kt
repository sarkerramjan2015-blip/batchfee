package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.models.InstituteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InstituteDao {
    @Query("SELECT * FROM institutes WHERE id = :id")
    fun getInstituteFlow(id: String): Flow<InstituteEntity?>

    @Query("SELECT * FROM institutes WHERE id = :id")
    suspend fun getInstitute(id: String): InstituteEntity?

    @Query("SELECT * FROM institutes")
    fun getAllInstitutes(): Flow<List<InstituteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstitute(institute: InstituteEntity)

    @Update
    suspend fun updateInstitute(institute: InstituteEntity)
}
