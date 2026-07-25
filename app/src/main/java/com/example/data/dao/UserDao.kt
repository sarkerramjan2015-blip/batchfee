package com.batchfee.edu.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.batchfee.edu.data.models.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun getUserByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    fun getUserFlow(id: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun getUserById(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE instituteId = :instituteId")
    fun getUsersByInstitute(instituteId: String): Flow<List<UserEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @androidx.room.Update
    suspend fun updateUser(user: UserEntity)

    @Query("UPDATE users SET failedAttempts = :attempts, lockedUntilMs = :lockedUntilMs WHERE email = :email COLLATE NOCASE")
    suspend fun updateFailedAttempts(email: String, attempts: Int, lockedUntilMs: Long?)

    @Query("UPDATE users SET failedAttempts = 0, lockedUntilMs = NULL WHERE email = :email COLLATE NOCASE")
    suspend fun resetFailedAttempts(email: String)
}
