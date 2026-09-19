package com.batchfee.edu.data.repository

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** Audience is intentionally limited to roles/institutes until a separately reviewed
 * server-side activity/version audience policy is available. */
data class NoticeAudience(
    val roles: List<String> = listOf("owner"),
    val instituteIds: List<String> = emptyList()
)

data class AppNotice(
    val noticeId: String,
    val title: String,
    val body: String,
    val category: String,
    val senderName: String,
    val publishedAtMs: Long,
    val updatedAtMs: Long,
    val expiresAtMs: Long,
    val isRead: Boolean,
    val status: String = "published",
    val audience: NoticeAudience = NoticeAudience(),
    val createdByName: String = ""
)

data class NoticeInbox(
    val notices: List<AppNotice>,
    val unreadCount: Int,
    val hasMore: Boolean
)

data class ProductFeedbackItem(
    val itemId: String,
    val type: String,
    val title: String,
    val body: String,
    val status: String,
    val instituteId: String,
    val instituteName: String,
    val createdByName: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class ProductFeedbackNote(
    val noteId: String,
    val body: String,
    val status: String,
    val createdByName: String,
    val createdAtMs: Long
)

data class ProductFeedbackDetails(
    val item: ProductFeedbackItem,
    val notes: List<ProductFeedbackNote>
)

/** A Root-managed in-app guide. Only its canonical YouTube video ID reaches the player. */
data class AppTutorial(
    val tutorialId: String,
    val title: String,
    val description: String,
    val category: String,
    val displayOrder: Int,
    val youtubeVideoId: String,
    /** portrait for YouTube Shorts; landscape for regular YouTube videos. */
    val videoLayout: String,
    val status: String,
    val publishedAtMs: Long,
    val updatedAtMs: Long
)

/**
 * V1.8 notice and product-feedback transport. No notice, read marker, support
 * item or internal note is read/written directly by the Android client.
 */
class NoticeCenterRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1")
) {
    /** Associates this authenticated tenant device with trusted notice delivery. */
    suspend fun registerPushToken(token: String) {
        require(token.length in 20..4_096) { "Invalid notification token." }
        call("register_notice_push_token", values = mapOf("token" to token))
    }

    suspend fun myNotices(tab: String = "all", pageSize: Int = 50): NoticeInbox {
        require(tab in setOf("all", "unread", "archived")) { "Invalid notice tab." }
        require(pageSize in setOf(25, 50, 100)) { "Invalid notice page size." }
        val response = call("list_my_notices", values = mapOf("tab" to tab, "pageSize" to pageSize))
        return NoticeInbox(
            notices = (response["notices"] as? List<*>).orEmpty()
                .mapNotNull { (it as? Map<*, *>)?.toAppNotice() },
            unreadCount = (response["unreadCount"] as? Number)?.toInt() ?: 0,
            hasMore = response["hasMore"] as? Boolean ?: false
        )
    }

    suspend fun markNoticeState(noticeId: String, isRead: Boolean) {
        require(noticeId.isNotBlank()) { "Notice is required." }
        call(
            action = "mark_notice_state",
            values = mapOf("noticeId" to noticeId, "isRead" to isRead)
        )
    }

    suspend fun submitFeedback(type: String, title: String, body: String): String {
        require(type in setOf("suggestion", "complaint")) { "Invalid feedback type." }
        require(title.trim().length >= 3) { "Write a short title." }
        require(body.trim().length >= 10) { "Please add a little more detail." }
        val response = call(
            action = "submit_support_item",
            values = mapOf("type" to type, "title" to title.trim(), "body" to body.trim())
        )
        return response["itemId"] as? String ?: error("Invalid support-item response.")
    }

    suspend fun platformNotices(pageSize: Int = 50): List<AppNotice> {
        require(pageSize in setOf(25, 50, 100)) { "Invalid notice page size." }
        val response = call("list_platform_notices", values = mapOf("pageSize" to pageSize))
        return (response["notices"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.toAppNotice() }
    }

    suspend fun publishNotice(
        title: String,
        body: String,
        category: String,
        audience: NoticeAudience,
        expiryDays: Int
    ): String {
        val response = call(
            action = "publish_notice",
            values = noticeValues(title, body, category, audience, expiryDays)
        )
        return response["noticeId"] as? String ?: error("Invalid publish response.")
    }

    suspend fun updateNotice(
        noticeId: String,
        title: String,
        body: String,
        category: String,
        audience: NoticeAudience,
        expiryDays: Int
    ) {
        require(noticeId.isNotBlank()) { "Notice is required." }
        call(
            action = "update_notice",
            values = noticeValues(title, body, category, audience, expiryDays) + ("noticeId" to noticeId)
        )
    }

    suspend fun archiveNotice(noticeId: String) {
        call("archive_notice", values = mapOf("noticeId" to noticeId))
    }

    suspend fun restoreNotice(noticeId: String) {
        call("restore_notice", values = mapOf("noticeId" to noticeId))
    }

    suspend fun supportItems(status: String = "", pageSize: Int = 50): List<ProductFeedbackItem> {
        require(status.isBlank() || status in setOf("open", "in_progress", "resolved")) { "Invalid support status." }
        val values = mutableMapOf<String, Any>("pageSize" to pageSize)
        if (status.isNotBlank()) values["status"] = status
        val response = call("list_support_items", values = values)
        return (response["items"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.toProductFeedbackItem() }
    }

    suspend fun supportItemDetails(itemId: String): ProductFeedbackDetails {
        val response = call("get_support_item_details", values = mapOf("itemId" to itemId))
        val item = (response["item"] as? Map<*, *>)?.toProductFeedbackItem()
            ?: error("Invalid support-item response.")
        return ProductFeedbackDetails(
            item = item,
            notes = (response["notes"] as? List<*>).orEmpty()
                .mapNotNull { (it as? Map<*, *>)?.toProductFeedbackNote() }
        )
    }

    suspend fun addSupportItemNote(itemId: String, body: String, status: String) {
        require(body.trim().length >= 3) { "Write an internal note." }
        require(status in setOf("open", "in_progress", "resolved")) { "Invalid support status." }
        call(
            action = "add_support_item_note",
            values = mapOf("itemId" to itemId, "body" to body.trim(), "status" to status)
        )
    }

    suspend fun updateSupportItemStatus(itemId: String, status: String) {
        require(status in setOf("open", "in_progress", "resolved")) { "Invalid support status." }
        call("update_support_item_status", values = mapOf("itemId" to itemId, "status" to status))
    }

    suspend fun tutorials(): List<AppTutorial> {
        val response = call("list_tutorials", values = emptyMap())
        return (response["tutorials"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.toAppTutorial() }
    }

    suspend fun platformTutorials(): List<AppTutorial> {
        val response = call("list_platform_tutorials", values = emptyMap())
        return (response["tutorials"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.toAppTutorial() }
    }

    suspend fun createTutorial(
        title: String,
        description: String,
        category: String,
        youtubeUrl: String,
        displayOrder: Int
    ): String {
        val response = call(
            "create_tutorial",
            values = tutorialValues(title, description, category, youtubeUrl, displayOrder)
        )
        return response["tutorialId"] as? String ?: error("Invalid tutorial response.")
    }

    suspend fun updateTutorial(
        tutorialId: String,
        title: String,
        description: String,
        category: String,
        youtubeUrl: String,
        displayOrder: Int
    ) {
        require(tutorialId.isNotBlank()) { "Tutorial is required." }
        call(
            "update_tutorial",
            values = tutorialValues(title, description, category, youtubeUrl, displayOrder) + ("tutorialId" to tutorialId)
        )
    }

    suspend fun archiveTutorial(tutorialId: String) {
        require(tutorialId.isNotBlank()) { "Tutorial is required." }
        call("archive_tutorial", values = mapOf("tutorialId" to tutorialId))
    }

    suspend fun restoreTutorial(tutorialId: String) {
        require(tutorialId.isNotBlank()) { "Tutorial is required." }
        call("restore_tutorial", values = mapOf("tutorialId" to tutorialId))
    }

    private fun noticeValues(
        title: String,
        body: String,
        category: String,
        audience: NoticeAudience,
        expiryDays: Int
    ): Map<String, Any> {
        require(title.trim().length >= 3) { "Write a title." }
        require(body.trim().length >= 3) { "Write the notice." }
        require(category in setOf("update", "maintenance", "billing", "feature", "important")) { "Invalid category." }
        require(expiryDays in 0..365) { "Invalid expiry." }
        return mapOf(
            "title" to title.trim(),
            "body" to body.trim(),
            "category" to category,
            "audience" to mapOf("roles" to audience.roles, "instituteIds" to audience.instituteIds),
            "expiryDays" to expiryDays
        )
    }

    private fun tutorialValues(
        title: String,
        description: String,
        category: String,
        youtubeUrl: String,
        displayOrder: Int
    ): Map<String, Any> {
        require(title.trim().length in 3..120) { "Write a tutorial title." }
        require(description.trim().length <= 1_000) { "Tutorial description is too long." }
        require(category.trim().length in 1..60) { "Write a category." }
        require(youtubeUrl.trim().isNotBlank()) { "Paste a YouTube video link." }
        require(displayOrder in 0..10_000) { "Invalid tutorial order." }
        return mapOf(
            "title" to title.trim(),
            "description" to description.trim(),
            "category" to category.trim(),
            "youtubeUrl" to youtubeUrl.trim(),
            "displayOrder" to displayOrder
        )
    }

    private suspend fun call(
        action: String,
        operationId: String = UUID.randomUUID().toString(),
        values: Map<String, Any>
    ): Map<String, Any?> = try {
        val response = functions.getHttpsCallable("commitNoticeCenterOperation")
            .call(values + mapOf("action" to action, "operationId" to operationId))
            .await()
        @Suppress("UNCHECKED_CAST")
        response.data as? Map<String, Any?> ?: error("Invalid notice service response.")
    } catch (error: FirebaseFunctionsException) {
        when (error.code) {
            FirebaseFunctionsException.Code.INVALID_ARGUMENT,
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.ALREADY_EXISTS,
            FirebaseFunctionsException.Code.NOT_FOUND,
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.UNAUTHENTICATED -> throw IllegalArgumentException(
                error.message ?: "Notice operation was rejected.", error
            )
            else -> throw error
        }
    }
}

private fun Map<*, *>.toAppNotice(): AppNotice {
    fun text(key: String) = this[key] as? String ?: ""
    fun millis(key: String) = (this[key] as? Number)?.toLong() ?: 0L
    val rawAudience = this["audience"] as? Map<*, *>
    return AppNotice(
        noticeId = text("noticeId"),
        title = text("title"),
        body = text("body"),
        category = text("category"),
        senderName = text("senderName").ifBlank { "BatchFee Team" },
        publishedAtMs = millis("publishedAtMs"),
        updatedAtMs = millis("updatedAtMs"),
        expiresAtMs = millis("expiresAtMs"),
        isRead = this["isRead"] as? Boolean ?: false,
        status = text("status").ifBlank { "published" },
        audience = NoticeAudience(
            roles = (rawAudience?.get("roles") as? List<*>).orEmpty().mapNotNull { it as? String },
            instituteIds = (rawAudience?.get("instituteIds") as? List<*>).orEmpty().mapNotNull { it as? String }
        ),
        createdByName = text("createdByName")
    )
}

private fun Map<*, *>.toProductFeedbackItem(): ProductFeedbackItem {
    fun text(key: String) = this[key] as? String ?: ""
    fun millis(key: String) = (this[key] as? Number)?.toLong() ?: 0L
    return ProductFeedbackItem(
        itemId = text("itemId"), type = text("type"), title = text("title"), body = text("body"),
        status = text("status"), instituteId = text("instituteId"), instituteName = text("instituteName"),
        createdByName = text("createdByName"), createdAtMs = millis("createdAtMs"), updatedAtMs = millis("updatedAtMs")
    )
}

private fun Map<*, *>.toProductFeedbackNote(): ProductFeedbackNote {
    fun text(key: String) = this[key] as? String ?: ""
    return ProductFeedbackNote(
        noteId = text("noteId"), body = text("body"), status = text("status"),
        createdByName = text("createdByName"), createdAtMs = (this["createdAtMs"] as? Number)?.toLong() ?: 0L
    )
}

private fun Map<*, *>.toAppTutorial(): AppTutorial {
    fun text(key: String) = this[key] as? String ?: ""
    fun millis(key: String) = (this[key] as? Number)?.toLong() ?: 0L
    return AppTutorial(
        tutorialId = text("tutorialId"),
        title = text("title"),
        description = text("description"),
        category = text("category").ifBlank { "Getting started" },
        displayOrder = (this["displayOrder"] as? Number)?.toInt() ?: 0,
        youtubeVideoId = text("youtubeVideoId"),
        videoLayout = text("videoLayout").ifBlank { "landscape" },
        status = text("status").ifBlank { "archived" },
        publishedAtMs = millis("publishedAtMs"),
        updatedAtMs = millis("updatedAtMs")
    )
}
