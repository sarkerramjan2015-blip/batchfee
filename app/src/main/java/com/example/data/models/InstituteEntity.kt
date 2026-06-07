package com.example.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "institutes")
data class InstituteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currentPlanId: String,
    val subscriptionStatus: String, // trial, active, past_due, expired, cancelled
    val trialStartDateMs: Long,
    val trialEndDateMs: Long,
    val currentPeriodEndMs: Long,
    val createdAtMs: Long,
    val phone: String? = null,
    val address: String? = null,
    val whatsappNumber: String? = null,
    val profilePhotoUri: String? = null,
    val ownerName: String? = null,
    val email: String? = null,
    val instituteCode: String? = null,
    val securityPin: String? = null
)
