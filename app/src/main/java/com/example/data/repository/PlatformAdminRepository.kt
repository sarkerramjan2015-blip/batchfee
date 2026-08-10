package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class PlatformInstituteDraft(
    val instituteName: String,
    val ownerName: String,
    val ownerEmail: String,
    val phone: String = "",
    val address: String = "",
    val instituteCode: String = "",
    val planId: String = "plan_free_trial"
)

data class ImportPreviewRow(
    val row: Int,
    val ownerEmail: String,
    val valid: Boolean,
    val issues: List<String>
)

data class PlatformProvisionResult(
    val instituteId: String = "",
    val instituteName: String = "",
    val ownerEmail: String = "",
    val ownerUid: String = "",
    val recoveryLink: String = ""
)

data class PlatformDashboardMetrics(
    val snapshotAtMs: Long,
    val totalInstitutes: Int,
    val activeInstitutes: Int,
    val expiringIn7Days: Int,
    val expiringIn30Days: Int,
    val totalStudents: Int,
    val totalStaff: Int,
    val lifetimeRevenue: Double,
    val thisMonthRevenue: Double,
    val canonicalReceiptCount: Int
)

/** All privileged platform writes are routed to commitPlatformAdminOperation. */
class PlatformAdminRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1")
) {
    suspend fun createInstitute(
        draft: PlatformInstituteDraft,
        operationId: String = UUID.randomUUID().toString()
    ): PlatformProvisionResult = call(
        "create_institute", operationId, mapOf(
            "instituteName" to draft.instituteName.trim(),
            "ownerName" to draft.ownerName.trim(),
            "ownerEmail" to draft.ownerEmail.trim(),
            "phone" to draft.phone.trim(),
            "address" to draft.address.trim(),
            "instituteCode" to draft.instituteCode.trim(),
            "planId" to draft.planId
        )
    ).toProvisionResult()

    suspend fun previewInstituteImport(rows: List<PlatformInstituteDraft>): List<ImportPreviewRow> {
        require(rows.isNotEmpty() && rows.size <= 100) { "Import must contain 1 to 100 rows." }
        val output = call(
            "preview_institute_import",
            UUID.randomUUID().toString(),
            mapOf("rows" to rows.map { draftToMap(it) })
        )
        @Suppress("UNCHECKED_CAST")
        return (output["rows"] as? List<Map<String, Any?>>).orEmpty().map { row ->
            ImportPreviewRow(
                row = (row["row"] as? Number)?.toInt() ?: 0,
                ownerEmail = row["ownerEmail"] as? String ?: "",
                valid = row["valid"] as? Boolean ?: false,
                issues = (row["issues"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            )
        }
    }

    suspend fun transferOwner(
        instituteId: String,
        ownerName: String,
        ownerEmail: String,
        reason: String,
        operationId: String = UUID.randomUUID().toString()
    ): PlatformProvisionResult = call(
        "transfer_owner", operationId, mapOf(
            "instituteId" to instituteId,
            "ownerName" to ownerName.trim(),
            "ownerEmail" to ownerEmail.trim(),
            "reason" to reason.trim()
        )
    ).toProvisionResult()

    suspend fun ownerRecovery(
        instituteId: String,
        reason: String,
        operationId: String = UUID.randomUUID().toString()
    ): PlatformProvisionResult = call(
        "send_owner_recovery", operationId,
        mapOf("instituteId" to instituteId, "reason" to reason.trim())
    ).toProvisionResult()

    suspend fun provisionPlatformAdmin(
        name: String,
        email: String,
        platformRole: String,
        operationId: String = UUID.randomUUID().toString()
    ): PlatformProvisionResult = call(
        "manage_platform_admin", operationId,
        mapOf("name" to name.trim(), "email" to email.trim(), "platformRole" to platformRole)
    ).toProvisionResult()

    suspend fun dashboard(): PlatformDashboardMetrics {
        val data = call("get_platform_dashboard", UUID.randomUUID().toString(), emptyMap())
        return PlatformDashboardMetrics(
            snapshotAtMs = data.number("snapshotAtMs").toLong(),
            totalInstitutes = data.number("totalInstitutes").toInt(),
            activeInstitutes = data.number("activeInstitutes").toInt(),
            expiringIn7Days = data.number("expiringIn7Days").toInt(),
            expiringIn30Days = data.number("expiringIn30Days").toInt(),
            totalStudents = data.number("totalStudents").toInt(),
            totalStaff = data.number("totalStaff").toInt(),
            lifetimeRevenue = data.number("lifetimeRevenue").toDouble(),
            thisMonthRevenue = data.number("thisMonthRevenue").toDouble(),
            canonicalReceiptCount = data.number("canonicalReceiptCount").toInt()
        )
    }

    private suspend fun call(action: String, operationId: String, values: Map<String, Any>): Map<String, Any?> = try {
        val response = functions.getHttpsCallable("commitPlatformAdminOperation")
            .call(values + mapOf("action" to action, "operationId" to operationId))
            .await()
        @Suppress("UNCHECKED_CAST")
        response.data as? Map<String, Any?> ?: error("Invalid platform service response.")
    } catch (error: FirebaseFunctionsException) {
        when (error.code) {
            FirebaseFunctionsException.Code.INVALID_ARGUMENT,
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.ALREADY_EXISTS,
            FirebaseFunctionsException.Code.NOT_FOUND,
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.UNAUTHENTICATED -> throw IllegalArgumentException(
                error.message ?: "Platform operation was rejected.", error
            )
            else -> throw error
        }
    }

    private fun draftToMap(draft: PlatformInstituteDraft) = mapOf(
        "instituteName" to draft.instituteName.trim(), "ownerName" to draft.ownerName.trim(),
        "ownerEmail" to draft.ownerEmail.trim(), "phone" to draft.phone.trim(),
        "address" to draft.address.trim(), "instituteCode" to draft.instituteCode.trim(), "planId" to draft.planId
    )
}

private fun Map<String, Any?>.toProvisionResult() = PlatformProvisionResult(
    instituteId = this["instituteId"] as? String ?: "",
    instituteName = this["instituteName"] as? String ?: "",
    ownerEmail = this["ownerEmail"] as? String ?: "",
    ownerUid = this["ownerUid"] as? String ?: this["userId"] as? String ?: "",
    recoveryLink = this["recoveryLink"] as? String ?: ""
)

private fun Map<String, Any?>.number(key: String): Number =
    this[key] as? Number ?: error("Missing $key in platform response.")
