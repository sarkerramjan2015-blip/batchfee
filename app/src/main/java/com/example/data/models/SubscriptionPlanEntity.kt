package com.example.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "subscription_plans")
data class SubscriptionPlanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val priceBdt: Double,
    val priceInr: Double,
    val maxStudents: Int,
    val maxBatches: Int,
    val maxUsers: Int,
    val maxBranches: Int,
    val tag: String, // e.g., "Popular", "Enterprise"
    val tierLevel: Int // 0=Free, 1=Starter, 2=Growth, 3=Pro, 4=Institute, 5=Enterprise
)
