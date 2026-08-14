package com.batchfee.edu.domain

import com.batchfee.edu.data.models.SubscriptionPlanEntity

/**
 * Handles SaaS feature locking logic on the client-side based on the current subscription plan.
 * Prepared for offline-first constraints and future cloud synchronization.
 */
object FeatureManager {

    enum class Feature {
        BASIC_REPORTS,
        ADVANCED_ANALYTICS,
        STUDENT_PORTAL,
        PARENT_PORTAL,
        SALARY_MANAGEMENT,
        MULTI_BRANCH,
        ONLINE_PAYMENT,
        CUSTOM_ROLES,
        API_ACCESS,
        ENTERPRISE_BRANDING,
        CUSTOM_DOMAIN,
        UNLIMITED_USERS
    }

    fun hasFeature(plan: SubscriptionPlanEntity?, feature: Feature): Boolean {
        if (plan == null) return false
        val tier = plan.tierLevel // 0=Free Trial, 1=Starter, 2=Growth, 3=Pro, 4=Institute, 5=Enterprise

        return when (feature) {
            Feature.BASIC_REPORTS -> tier >= 0
            Feature.ADVANCED_ANALYTICS -> tier >= 3
            Feature.STUDENT_PORTAL -> tier >= 3
            Feature.PARENT_PORTAL -> tier >= 3
            Feature.SALARY_MANAGEMENT -> tier >= 3
            Feature.MULTI_BRANCH -> tier >= 4
            Feature.ONLINE_PAYMENT -> tier >= 3
            Feature.CUSTOM_ROLES -> tier >= 4
            Feature.API_ACCESS -> tier >= 4
            Feature.ENTERPRISE_BRANDING -> tier >= 5
            Feature.CUSTOM_DOMAIN -> tier >= 5
            Feature.UNLIMITED_USERS -> tier >= 5
        }
    }
    
    fun canAddStudent(currentCount: Int, plan: SubscriptionPlanEntity?): Boolean {
        if (plan == null) return false
        if (plan.id == "plan_free_trial") return true
        return currentCount < plan.maxStudents
    }
    
    fun canAddBatch(currentCount: Int, plan: SubscriptionPlanEntity?): Boolean {
        if (plan == null) return false
        return currentCount < plan.maxBatches
    }
}

