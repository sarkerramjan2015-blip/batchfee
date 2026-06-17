package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.models.SubscriptionPlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionPlanDao {
    @Query("SELECT * FROM subscription_plans ORDER BY tierLevel ASC")
    fun getAllPlans(): Flow<List<SubscriptionPlanEntity>>

    @Query("SELECT * FROM subscription_plans WHERE id = :planId")
    suspend fun getPlanById(planId: String): SubscriptionPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlans(plans: List<SubscriptionPlanEntity>)

    @Query("DELETE FROM subscription_plans WHERE id = :planId")
    suspend fun deletePlanById(planId: String)

    @Query("DELETE FROM subscription_plans")
    suspend fun clearAll()

    suspend fun replaceAll(plans: List<SubscriptionPlanEntity>) {
        clearAll()
        if (plans.isNotEmpty()) {
            insertPlans(plans)
        }
    }
}
