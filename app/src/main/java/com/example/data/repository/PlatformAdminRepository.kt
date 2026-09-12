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

/** Safe, credential-free representation returned only by the trusted platform service. */
data class PlatformTeamMember(
    val userId: String,
    val name: String,
    val email: String,
    val platformRole: String,
    val status: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/** Server-filtered, secret-free directory query. Never built from a local list. */
data class InstituteDirectoryFilter(
    val query: String = "",
    val planId: String = "",
    val status: String = "all",
    val renewalWindow: String = "all",
    val activityWindow: String = "all",
    val minStudentCount: Int? = null,
    val maxStudentCount: Int? = null
)

data class InstituteDirectoryRow(
    val instituteId: String,
    val instituteName: String,
    val ownerName: String,
    val ownerEmail: String,
    val phone: String,
    val instituteCode: String,
    val currentPlanId: String,
    val subscriptionStatus: String,
    val currentPeriodEndMs: Long,
    val createdAtMs: Long,
    val lastActiveAtMs: Long,
    val studentCount: Int,
    val staffCount: Int,
    val batchCount: Int
)

data class InstituteDirectoryPage(
    val results: List<InstituteDirectoryRow>,
    val pageSize: Int,
    val hasMore: Boolean,
    val nextCursor: String,
    val scannedDocuments: Int
)

/** Immutable, allowlisted platform event. Sensitive support reasons stay server-only. */
data class InstituteActivityEvent(
    val eventId: String,
    val action: String,
    val actorRole: String,
    val targetType: String,
    val targetId: String,
    val outcome: String,
    val summary: String,
    val occurredAtMs: Long
)

data class InstituteActivityPage(
    val events: List<InstituteActivityEvent>,
    val hasMore: Boolean,
    val nextCursor: String
)

/** Initial support search result; it intentionally contains no contact or guardian data. */
data class StudentSupportResult(
    val instituteId: String,
    val instituteName: String,
    val studentId: String,
    val studentCode: String,
    val fullName: String,
    val status: String,
    val batchName: String
)

data class StudentSupportPage(
    val results: List<StudentSupportResult>,
    val hasMore: Boolean,
    val nextCursor: String,
    val scannedDocuments: Int
)

/** Returned only after Root provides a support reason; never cache it in Room. */
data class StudentSupportDetails(
    val instituteId: String,
    val instituteName: String,
    val studentId: String,
    val studentCode: String,
    val fullName: String,
    val status: String,
    val phone: String,
    val className: String,
    val admissionDateMs: Long,
    val batchNames: List<String>
)

data class ClientNote(
    val noteId: String,
    val title: String,
    val body: String,
    val status: String,
    val followUpAtMs: Long,
    val createdAtMs: Long,
    val createdByName: String,
    val createdByRole: String
)

data class ClientNotesPage(
    val notes: List<ClientNote>,
    val hasMore: Boolean,
    val nextCursor: String
)

/** Safe, allowlisted SMS recharge request row returned by the trusted wallet callable. */
data class SmsRechargeReviewRequest(
    val requestId: String,
    val status: String,
    val packageId: String,
    val packageName: String,
    val layer: String,
    val smsCount: Int,
    val baseAmount: Double,
    val chargeAmount: Double,
    val payableAmount: Double,
    val paymentMethod: String,
    val senderPhone: String,
    val instituteId: String,
    val createdAtMs: Long,
    val reviewedAtMs: Long,
    val reviewerNote: String
)

/** Root-only SMS accounting summary: collected, credited, used, cost, and profit. */
data class SmsRechargeAccounting(
    val rechargeRequestCount: Int,
    val pendingRequestCount: Int,
    val totalCollectedTaka: Double,
    val totalCreditedSms: Int,
    val totalUsedSms: Int,
    val outstandingBalance: Int,
    val smsUnitCostPaisa: Int,
    val totalCostTaka: Double,
    val profitTaka: Double
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

    suspend fun listPlatformMembers(): List<PlatformTeamMember> {
        val output = call("list_platform_admins", UUID.randomUUID().toString(), emptyMap())
        return (output["members"] as? List<*>)
            .orEmpty()
            .mapNotNull { raw -> (raw as? Map<*, *>)?.toPlatformTeamMember() }
    }

    suspend fun updatePlatformMember(
        targetUserId: String,
        platformRole: String? = null,
        status: String? = null,
        reason: String,
        operationId: String = UUID.randomUUID().toString()
    ): PlatformTeamMember {
        require(targetUserId.isNotBlank()) { "Platform member is required." }
        require(reason.trim().length >= 3) { "A reason is required." }
        val values = mutableMapOf<String, Any>(
            "targetUserId" to targetUserId,
            "reason" to reason.trim()
        )
        platformRole?.trim()?.takeIf { it.isNotBlank() }?.let { values["platformRole"] = it }
        status?.trim()?.takeIf { it.isNotBlank() }?.let { values["status"] = it }
        return call("update_platform_admin", operationId, values).toPlatformTeamMember()
    }

    suspend fun queryInstituteDirectory(
        filters: InstituteDirectoryFilter,
        pageSize: Int = 50,
        cursor: String? = null
    ): InstituteDirectoryPage {
        require(pageSize in setOf(25, 50, 100)) { "Directory page size must be 25, 50, or 100." }
        val filterValues = mutableMapOf<String, Any>(
            "status" to filters.status,
            "renewalWindow" to filters.renewalWindow,
            "activityWindow" to filters.activityWindow
        )
        filters.planId.trim().takeIf { it.isNotBlank() }?.let { filterValues["planId"] = it }
        filters.minStudentCount?.let { filterValues["minStudentCount"] = it }
        filters.maxStudentCount?.let { filterValues["maxStudentCount"] = it }
        val values = mutableMapOf<String, Any>(
            "query" to filters.query.trim(),
            "pageSize" to pageSize,
            "filters" to filterValues
        )
        cursor?.takeIf { it.isNotBlank() }?.let { values["cursor"] = it }
        val output = call("query_institute_directory", UUID.randomUUID().toString(), values)
        return InstituteDirectoryPage(
            results = (output["results"] as? List<*>)
                .orEmpty()
                .mapNotNull { raw -> (raw as? Map<*, *>)?.toInstituteDirectoryRow() },
            pageSize = (output["pageSize"] as? Number)?.toInt() ?: pageSize,
            hasMore = output["hasMore"] as? Boolean ?: false,
            nextCursor = output["nextCursor"] as? String ?: "",
            scannedDocuments = (output["scannedDocuments"] as? Number)?.toInt() ?: 0
        )
    }

    suspend fun queryInstituteTimeline(
        instituteId: String,
        pageSize: Int = 25,
        cursor: String? = null
    ): InstituteActivityPage {
        require(instituteId.isNotBlank()) { "Institute is required." }
        require(pageSize in setOf(25, 50, 100)) { "Timeline page size must be 25, 50, or 100." }
        val values = mutableMapOf<String, Any>("instituteId" to instituteId, "pageSize" to pageSize)
        cursor?.takeIf { it.isNotBlank() }?.let { values["cursor"] = it }
        val output = call("query_institute_timeline", UUID.randomUUID().toString(), values)
        return InstituteActivityPage(
            events = (output["events"] as? List<*>).orEmpty()
                .mapNotNull { raw -> (raw as? Map<*, *>)?.toInstituteActivityEvent() },
            hasMore = output["hasMore"] as? Boolean ?: false,
            nextCursor = output["nextCursor"] as? String ?: ""
        )
    }

    suspend fun searchStudentsForSupport(
        query: String,
        instituteId: String? = null,
        pageSize: Int = 25,
        cursor: String? = null
    ): StudentSupportPage {
        require(query.trim().length >= 3) { "Enter at least 3 characters." }
        require(pageSize in setOf(25, 50)) { "Student-support page size must be 25 or 50." }
        val values = mutableMapOf<String, Any>("query" to query.trim(), "pageSize" to pageSize)
        instituteId?.trim()?.takeIf { it.isNotBlank() }?.let { values["instituteId"] = it }
        cursor?.takeIf { it.isNotBlank() }?.let { values["cursor"] = it }
        val output = call("query_student_support", UUID.randomUUID().toString(), values)
        return StudentSupportPage(
            results = (output["results"] as? List<*>).orEmpty()
                .mapNotNull { raw -> (raw as? Map<*, *>)?.toStudentSupportResult() },
            hasMore = output["hasMore"] as? Boolean ?: false,
            nextCursor = output["nextCursor"] as? String ?: "",
            scannedDocuments = (output["scannedDocuments"] as? Number)?.toInt() ?: 0
        )
    }

    suspend fun getStudentSupportDetails(
        instituteId: String,
        studentId: String,
        reason: String,
        operationId: String = UUID.randomUUID().toString()
    ): StudentSupportDetails {
        require(instituteId.isNotBlank() && studentId.isNotBlank()) { "Student and institute are required." }
        require(reason.trim().length >= 10) { "A support reason of at least 10 characters is required." }
        return call("get_student_support_details", operationId, mapOf(
            "instituteId" to instituteId,
            "studentId" to studentId,
            "reason" to reason.trim()
        )).toStudentSupportDetails()
    }

    suspend fun listClientNotes(
        instituteId: String,
        pageSize: Int = 25,
        cursor: String? = null
    ): ClientNotesPage {
        require(instituteId.isNotBlank()) { "Institute is required." }
        require(pageSize in setOf(25, 50)) { "Client-note page size must be 25 or 50." }
        val values = mutableMapOf<String, Any>("instituteId" to instituteId, "pageSize" to pageSize)
        cursor?.takeIf { it.isNotBlank() }?.let { values["cursor"] = it }
        val output = call("list_client_notes", UUID.randomUUID().toString(), values)
        return ClientNotesPage(
            notes = (output["notes"] as? List<*>).orEmpty()
                .mapNotNull { raw -> (raw as? Map<*, *>)?.toClientNote() },
            hasMore = output["hasMore"] as? Boolean ?: false,
            nextCursor = output["nextCursor"] as? String ?: ""
        )
    }

    suspend fun createClientNote(
        instituteId: String,
        title: String,
        body: String,
        status: String,
        followUpAtMs: Long = 0L,
        operationId: String = UUID.randomUUID().toString()
    ): ClientNote {
        require(instituteId.isNotBlank()) { "Institute is required." }
        require(body.trim().length >= 3) { "Write a client note first." }
        require(status in setOf("open", "follow_up", "resolved")) { "Invalid client note status." }
        return call("create_client_note", operationId, mapOf(
            "instituteId" to instituteId,
            "title" to title.trim(),
            "body" to body.trim(),
            "status" to status,
            "followUpAtMs" to followUpAtMs
        )).toClientNote()
    }

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

    /** Root/Billing view of SMS recharge requests, newest first. */
    suspend fun listSmsRechargeRequests(): List<SmsRechargeReviewRequest> = try {
        val response = functions.getHttpsCallable("commitSmsWalletOperation")
            .call(mapOf("action" to "list_recharge_requests", "operationId" to UUID.randomUUID().toString()))
            .await()
        @Suppress("UNCHECKED_CAST")
        val data = response.data as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        (data["requests"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.toSmsReviewRequest() }
    } catch (error: FirebaseFunctionsException) {
        throw when (error.code) {
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.UNAUTHENTICATED -> IllegalArgumentException(
                error.message ?: "SMS recharge list was rejected.", error
            )
            else -> error
        }
    }

    /** Root/Billing review: only the trusted approval credits the institute wallet. */
    suspend fun reviewSmsRechargeRequest(
        instituteId: String,
        requestId: String,
        decision: String,
        note: String? = null,
        operationId: String = UUID.randomUUID().toString()
    ): SmsRechargeReviewRequest {
        require(decision in setOf("approve", "reject")) { "Decision must be approve or reject." }
        val data = callSmsWallet(
            action = "review_recharge_request",
            operationId = operationId,
            values = mapOf(
                "instituteId" to instituteId,
                "requestId" to requestId,
                "decision" to decision,
                "note" to note.orEmpty()
            )
        )
        @Suppress("UNCHECKED_CAST")
        val request = data["request"] as? Map<*, *> ?: error("The reviewed request was not returned.")
        return request.toSmsReviewRequest()
    }

    /** Root-only accounting: collected, credited, used, cost, and profit. */
    suspend fun smsAccounting(): SmsRechargeAccounting {
        val data = callSmsWallet("sms_accounting", UUID.randomUUID().toString(), emptyMap())
        return SmsRechargeAccounting(
            rechargeRequestCount = data.number("rechargeRequestCount").toInt(),
            pendingRequestCount = data.number("pendingRequestCount").toInt(),
            totalCollectedTaka = data.number("totalCollectedTaka").toDouble(),
            totalCreditedSms = data.number("totalCreditedSms").toInt(),
            totalUsedSms = data.number("totalUsedSms").toInt(),
            outstandingBalance = data.number("outstandingBalance").toInt(),
            smsUnitCostPaisa = data.number("smsUnitCostPaisa").toInt(),
            totalCostTaka = data.number("totalCostTaka").toDouble(),
            profitTaka = data.number("profitTaka").toDouble()
        )
    }

    private suspend fun callSmsWallet(
        action: String,
        operationId: String,
        values: Map<String, Any>
    ): Map<String, Any?> = try {
        val response = functions.getHttpsCallable("commitSmsWalletOperation")
            .call(values + mapOf("action" to action, "operationId" to operationId))
            .await()
        @Suppress("UNCHECKED_CAST")
        response.data as? Map<String, Any?> ?: error("Invalid SMS wallet service response.")
    } catch (error: FirebaseFunctionsException) {
        when (error.code) {
            FirebaseFunctionsException.Code.INVALID_ARGUMENT,
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.ALREADY_EXISTS,
            FirebaseFunctionsException.Code.NOT_FOUND,
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.UNAUTHENTICATED -> throw IllegalArgumentException(
                error.message ?: "SMS wallet operation was rejected.", error
            )
            else -> throw error
        }
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

private fun Map<*, *>.toPlatformTeamMember(): PlatformTeamMember {
    fun string(key: String): String = this[key] as? String ?: error("Missing $key in platform member.")
    fun number(key: String): Long = (this[key] as? Number)?.toLong()
        ?: error("Missing $key in platform member.")
    return PlatformTeamMember(
        userId = string("userId"),
        name = string("name"),
        email = string("email"),
        platformRole = string("platformRole"),
        status = string("status"),
        createdAtMs = number("createdAtMs"),
        updatedAtMs = number("updatedAtMs")
    )
}

private fun Map<*, *>.toInstituteDirectoryRow(): InstituteDirectoryRow {
    fun string(key: String): String = this[key] as? String ?: ""
    fun long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
    fun int(key: String): Int = (this[key] as? Number)?.toInt() ?: 0
    return InstituteDirectoryRow(
        instituteId = string("instituteId"),
        instituteName = string("instituteName"),
        ownerName = string("ownerName"),
        ownerEmail = string("ownerEmail"),
        phone = string("phone"),
        instituteCode = string("instituteCode"),
        currentPlanId = string("currentPlanId"),
        subscriptionStatus = string("subscriptionStatus"),
        currentPeriodEndMs = long("currentPeriodEndMs"),
        createdAtMs = long("createdAtMs"),
        lastActiveAtMs = long("lastActiveAtMs"),
        studentCount = int("studentCount"),
        staffCount = int("staffCount"),
        batchCount = int("batchCount")
    )
}

private fun Map<*, *>.toInstituteActivityEvent(): InstituteActivityEvent {
    fun string(key: String): String = this[key] as? String ?: ""
    return InstituteActivityEvent(
        eventId = string("eventId"),
        action = string("action"),
        actorRole = string("actorRole"),
        targetType = string("targetType"),
        targetId = string("targetId"),
        outcome = string("outcome"),
        summary = string("summary"),
        occurredAtMs = (this["occurredAtMs"] as? Number)?.toLong() ?: 0L
    )
}

private fun Map<*, *>.toStudentSupportResult(): StudentSupportResult {
    fun string(key: String): String = this[key] as? String ?: ""
    return StudentSupportResult(
        instituteId = string("instituteId"),
        instituteName = string("instituteName"),
        studentId = string("studentId"),
        studentCode = string("studentCode"),
        fullName = string("fullName"),
        status = string("status"),
        batchName = string("batchName")
    )
}

private fun Map<String, Any?>.toStudentSupportDetails(): StudentSupportDetails {
    fun string(key: String): String = this[key] as? String ?: ""
    return StudentSupportDetails(
        instituteId = string("instituteId"),
        instituteName = string("instituteName"),
        studentId = string("studentId"),
        studentCode = string("studentCode"),
        fullName = string("fullName"),
        status = string("status"),
        phone = string("phone"),
        className = string("className"),
        admissionDateMs = (this["admissionDateMs"] as? Number)?.toLong() ?: 0L,
        batchNames = (this["batchNames"] as? List<*>).orEmpty().mapNotNull { it as? String }
    )
}

private fun Map<*, *>.toClientNote(): ClientNote {
    fun string(key: String): String = this[key] as? String ?: ""
    return ClientNote(
        noteId = string("noteId"),
        title = string("title"),
        body = string("body"),
        status = string("status"),
        followUpAtMs = (this["followUpAtMs"] as? Number)?.toLong() ?: 0L,
        createdAtMs = (this["createdAtMs"] as? Number)?.toLong() ?: 0L,
        createdByName = string("createdByName"),
        createdByRole = string("createdByRole")
    )
}

private fun Map<*, *>.toSmsReviewRequest(): SmsRechargeReviewRequest {
    fun string(key: String): String = this[key] as? String ?: ""
    return SmsRechargeReviewRequest(
        requestId = string("requestId"),
        status = string("status"),
        packageId = string("packageId"),
        packageName = string("packageName"),
        layer = string("layer"),
        smsCount = (this["smsCount"] as? Number)?.toInt() ?: 0,
        baseAmount = (this["baseAmount"] as? Number)?.toDouble() ?: 0.0,
        chargeAmount = (this["chargeAmount"] as? Number)?.toDouble() ?: 0.0,
        payableAmount = (this["payableAmount"] as? Number)?.toDouble() ?: 0.0,
        paymentMethod = string("paymentMethod"),
        senderPhone = string("senderPhone"),
        instituteId = string("instituteId"),
        createdAtMs = (this["createdAtMs"] as? Number)?.toLong() ?: 0L,
        reviewedAtMs = (this["reviewedAtMs"] as? Number)?.toLong() ?: 0L,
        reviewerNote = string("reviewerNote")
    )
}
