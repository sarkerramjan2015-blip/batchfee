package com.batchfee.edu.ui.superadmin

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.SubscriptionPlanSyncHelper
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.SubscriptionPlanEntity
import com.batchfee.edu.data.models.SubscriptionRequest
import com.batchfee.edu.data.repository.SafeDeletionRepository
import com.batchfee.edu.data.repository.PermanentArchivePurgeRepository
import com.batchfee.edu.data.repository.SubscriptionRepository
import com.batchfee.edu.data.repository.PlatformAdminRepository
import com.batchfee.edu.data.repository.PlatformInstituteDraft
import com.batchfee.edu.data.repository.InstituteOwnerLoginActivity
import com.batchfee.edu.data.repository.InstituteOwnerLoginActivityRepository
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.InstituteContactNumber
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

// ── Theme ────────────────────────────────────────────────────
private val BgColor = Color(0xFF0F0F14)
private val CardBg = Color(0xFF1A1A24)
private val BorderSub = Color(0xFF2A2A38)
private val TextWhite = Color(0xFFF0F0F0)
private val TextMuted = Color(0xFF8888A0)
private val AccentCyan = Color(0xFF22D3EE)
private val AccentGreen = Color(0xFF4ADE80)
private val AccentAmber = Color(0xFFFBBF24)
private val AccentRed = Color(0xFFF87171)
private val AccentViolet = Color(0xFFA855F7)
private val AccentPink = Color(0xFFF472B6)
private val ElectricBlue = Color(0xFF3B82F6)

private const val STANDARD_MONTHLY_FEE = 500.0
private const val DEFAULT_TRIAL_PLAN_ID = "plan_free_trial"
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

private fun effectiveSubscriptionExpiryMs(institute: InstituteEntity): Long =
    institute.currentPeriodEndMs.takeIf { it > 0L } ?: institute.trialEndDateMs

private fun humanizePlanId(planId: String): String = planId
    .removePrefix("plan_")
    .replace('_', ' ')
    .split(' ')
    .filter { it.isNotBlank() }
    .joinToString(" ") { token -> token.replaceFirstChar { it.uppercase() } }

private fun planDisplayName(planId: String, plans: List<SubscriptionPlanEntity>): String =
    plans.firstOrNull { it.id == planId }?.name ?: humanizePlanId(planId)

private fun planDisplayPrice(planId: String, plans: List<SubscriptionPlanEntity>): Double =
    plans.firstOrNull { it.id == planId }?.priceBdt ?: -1.0

private fun planStudentCapacityLabel(plan: SubscriptionPlanEntity): String =
    if (plan.id == DEFAULT_TRIAL_PLAN_ID) "Unlimited students" else "${plan.maxStudents} students"

private fun planBatchCapacityLabel(plan: SubscriptionPlanEntity): String =
    "Unlimited batches"

private fun planUserCapacityLabel(plan: SubscriptionPlanEntity): String =
    "Unlimited staff"

private fun planDisplayDetails(planId: String, plans: List<SubscriptionPlanEntity>): SubscriptionPlanEntity? =
    plans.firstOrNull { it.id == planId }

private fun slugifyPlanName(name: String): String = buildString {
    name.lowercase(Locale.getDefault()).forEach { ch ->
        when {
            ch.isLetterOrDigit() -> append(ch)
            ch == ' ' || ch == '-' || ch == '_' -> append('_')
        }
    }
}.replace(Regex("_+"), "_").trim('_')

private fun formatMoneyValue(price: Double): String = if (price == price.toLong().toDouble()) {
    price.toLong().toString()
} else {
    "%.0f".format(price)
}

private fun subscriptionOperationErrorMessage(error: Exception): String {
    val message = error.message?.trim().orEmpty()
    return when {
        message.equals("NOT_FOUND", ignoreCase = true) ->
            "Subscription service is unavailable. Please contact BatchFee support."
        message.isBlank() -> "Something went wrong. Please try again."
        else -> message
    }
}

// ── ViewModel ─────────────────────────────────────────────────
data class SuperAdminStats(
    val totalInstitutes: Int = 0,
    val activeSubscriptions: Int = 0,
    val lifetimeRevenue: Double = 0.0,
    val thisMonthRevenue: Double = 0.0,
    val lastMonthRevenue: Double = 0.0,
    val projectedRevenue: Double = 0.0,
    val totalStudents: Int = 0,
    val totalStaff: Int = 0,
    val expiringIn7Days: Int = 0,
    val expiringIn30Days: Int = 0,
    val canonicalReceiptCount: Int = 0,
    val snapshotAtMs: Long = 0L
)

data class InstituteCardData(
    val entity: InstituteEntity,
    val studentCount: Int = 0,
    val staffCount: Int = 0,
    val batchCount: Int = 0
)

data class InstituteStaffSummary(
    val id: String,
    val fullName: String,
    val staffCode: String,
    val roleTitle: String,
    val status: String,
    val phone: String,
    val email: String
)

data class AnnouncementData(
    val id: String,
    val message: String,
    val sentAt: Long,
    val updatedAt: Long,
    val expiresAt: Long?,
    val status: String,
    val sender: String,
    val platform: String
)

data class ManagedUserSummary(
    val id: String,
    val name: String,
    val email: String,
    val role: String,
    val instituteId: String? = null,
    val createdAtMs: Long,
    val status: String = "active"
)

data class SubscriptionReceiptData(
    val receiptNumber: String,
    val instituteName: String,
    val ownerName: String,
    val ownerPhone: String,
    val ownerEmail: String,
    val instituteCode: String,
    val instituteAddress: String,
    val planName: String,
    val durationMonths: Int,
    val amountPaid: Double,
    val paymentMethod: String,
    val transactionLast4: String,
    val startDateMs: Long,
    val endDateMs: Long,
    val senderPhone: String = ""
)

data class PlatformAuditEntry(
    val id: String,
    val action: String,
    val actorUid: String,
    val instituteId: String,
    val createdAtMs: Long,
    val summary: String
)

data class BulkImportReport(
    val batchId: String = "",
    val successfulRows: Set<Int> = emptySet(),
    val failedRows: Map<Int, String> = emptyMap(),
    val running: Boolean = false
)

class SuperAdminViewModel(private val db: AppDatabase) : ViewModel() {
    private val subscriptionExtensionsInProgress = mutableSetOf<String>()
    private val _institutes = MutableStateFlow<List<InstituteCardData>>(emptyList())
    val institutes = _institutes.asStateFlow()

    private val _subscriptionPlans = MutableStateFlow<List<SubscriptionPlanEntity>>(emptyList())
    val subscriptionPlans = _subscriptionPlans.asStateFlow()

    private val _stats = MutableStateFlow(SuperAdminStats())
    val stats = _stats.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _hasMoreInstitutes = MutableStateFlow(false)
    val hasMoreInstitutes = _hasMoreInstitutes.asStateFlow()

    private val _isLoadingMoreInstitutes = MutableStateFlow(false)
    val isLoadingMoreInstitutes = _isLoadingMoreInstitutes.asStateFlow()

    private val _operationMsg = MutableStateFlow<String?>(null)
    val operationMsg = _operationMsg.asStateFlow()

    private val _receiptData = MutableStateFlow<SubscriptionReceiptData?>(null)
    val receiptData = _receiptData.asStateFlow()

    private val _lastActiveMap = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastActiveMap = _lastActiveMap.asStateFlow()

    private val _pendingRequests = MutableStateFlow<List<SubscriptionRequest>>(emptyList())
    val pendingRequests = _pendingRequests.asStateFlow()

    private val _approvingRequestIds = MutableStateFlow<Set<String>>(emptySet())
    val approvingRequestIds = _approvingRequestIds.asStateFlow()

    private val _managedUsers = MutableStateFlow<List<ManagedUserSummary>>(emptyList())
    val managedUsers = _managedUsers.asStateFlow()

    private val _allReceipts = MutableStateFlow<List<SubscriptionReceiptData>>(emptyList())
    val allReceipts = _allReceipts.asStateFlow()

    private val _announcements = MutableStateFlow<List<AnnouncementData>>(emptyList())
    val announcements = _announcements.asStateFlow()

    private val _platformAudit = MutableStateFlow<List<PlatformAuditEntry>>(emptyList())
    val platformAudit = _platformAudit.asStateFlow()

    private val _lastRecoveryLink = MutableStateFlow<String?>(null)
    val lastRecoveryLink = _lastRecoveryLink.asStateFlow()

    private val _bulkImportReport = MutableStateFlow(BulkImportReport())
    val bulkImportReport = _bulkImportReport.asStateFlow()

    private val _purgingInstituteIds = MutableStateFlow<Set<String>>(emptySet())
    val purgingInstituteIds = _purgingInstituteIds.asStateFlow()

    private val firestore = FirebaseFirestore.getInstance()
    private val safeDeletionRepository = SafeDeletionRepository(db)
    private val permanentArchivePurgeRepository = PermanentArchivePurgeRepository(db)
    private val ownerLoginActivityRepository = InstituteOwnerLoginActivityRepository()
    private var didBackfillManagedUsers = false
    private var approvedRequestDocuments: List<Pair<String, Map<String, Any>>> = emptyList()
    private var nextInstitutePageCursor: DocumentSnapshot? = null
    private var totalInstituteCount: Int? = null
    private var hasServerDashboard = false
    private var instituteFirstPageListener: ListenerRegistration? = null
    private val lifecycleListeners = mutableListOf<ListenerRegistration>()
    private var resetInstituteListOnNextSnapshot = false

    private companion object {
        const val INSTITUTE_PAGE_SIZE = 40L
        const val ADMIN_LIST_WINDOW = 100L
        const val INSTITUTE_DETAIL_WINDOW = 100L
    }

    val projectedRevenue: Double
        get() = _stats.value.projectedRevenue

    init {
        loadSubscriptionPlans()
        loadInstitutesRealtime()
        loadInstituteTotalCount()
        loadPendingRequestsRealtime()
        cleanupInvalidPendingRequests()
        loadManagedUsersRealtime()
        loadTrashedInstitutes()
        viewModelScope.launch { safeDeletionRepository.replayAllPending() }
        // Lifetime receipt/revenue totals come from the trusted dashboard.
        // Do not open an unbounded collection-group listener at login.
        loadAllAnnouncements()
        refreshPlatformDashboard()
        loadPlatformAudit()
    }

    fun clearOperationMsg() { _operationMsg.value = null }
    fun clearRecoveryLink() { _lastRecoveryLink.value = null }

    private fun loadSubscriptionPlans() {
        viewModelScope.launch {
            db.subscriptionPlanDao().getAllPlans().collectLatest { plans ->
                _subscriptionPlans.value = plans
                rebuildReceiptHistory()
            }
        }
    }

    private fun recalculateStats(
        institutes: List<InstituteCardData>,
        plans: List<SubscriptionPlanEntity> = _subscriptionPlans.value
    ) {
        // The callable dashboard aggregates every institute on the server. Once
        // it has loaded, never replace it with a calculation from one paged
        // local screen (which may contain only the first 40 institutes).
        if (hasServerDashboard) return
        val now = System.currentTimeMillis()
        val planPriceMap = plans.associate { it.id to it.priceBdt }
        val activeCount = institutes.count { card ->
            val entity = card.entity
            val isExpired = entity.subscriptionStatus == "expired" || entity.subscriptionStatus == "blocked"
            !isExpired && effectiveSubscriptionExpiryMs(entity) > now
        }
        val totalRevenue = institutes.sumOf { card ->
            val entity = card.entity
            if (entity.subscriptionStatus == "blocked" ||
                entity.subscriptionStatus == "expired" ||
                entity.subscriptionStatus == "trashed" ||
                effectiveSubscriptionExpiryMs(entity) <= now
            ) {
                0.0
            } else {
                val price = planPriceMap[entity.currentPlanId]
                    ?: planDisplayPrice(entity.currentPlanId, plans)
                if (price < 0.0) STANDARD_MONTHLY_FEE else price
            }
        }
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val thisMonthStart = calendar.timeInMillis
        calendar.add(Calendar.MONTH, -1)
        val lastMonthStart = calendar.timeInMillis
        val receipts = _allReceipts.value
        _stats.value = SuperAdminStats(
            totalInstitutes = totalInstituteCount ?: institutes.size,
            activeSubscriptions = activeCount,
            lifetimeRevenue = receipts.sumOf { it.amountPaid },
            thisMonthRevenue = receipts
                .filter { it.startDateMs >= thisMonthStart }
                .sumOf { it.amountPaid },
            lastMonthRevenue = receipts
                .filter { it.startDateMs in lastMonthStart until thisMonthStart }
                .sumOf { it.amountPaid },
            projectedRevenue = totalRevenue,
            totalStudents = institutes.sumOf { it.studentCount },
            totalStaff = institutes.sumOf { it.staffCount }
        )
    }

    private fun loadApprovedReceiptsRealtime() {
        lifecycleListeners += firestore.collectionGroup("subscription_receipts")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    FirebaseCrashlytics.getInstance().recordException(error)
                    _operationMsg.value = "Receipt history unavailable: ${error.message}"
                    return@addSnapshotListener
                }
                approvedRequestDocuments = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    doc.data?.let { doc.id to it }
                }
                rebuildReceiptHistory()
            }
    }

    private fun rebuildReceiptHistory() {
        val institutesById = _institutes.value.associateBy { it.entity.id }
        val plans = _subscriptionPlans.value
        _allReceipts.value = approvedRequestDocuments.map { (documentId, data) ->
            val instituteId = data["instituteId"] as? String ?: ""
            val institute = institutesById[instituteId]?.entity
            val reviewedAt = (data["approvedAt"] as? Number)?.toLong() ?: 0L
            val startDateMs = (data["startDateMs"] as? Number)?.toLong()
                ?: reviewedAt.takeIf { it > 0L }
                ?: (data["requestSentAt"] as? Number)?.toLong()
                ?: 0L
            val durationMonths = (data["durationMonths"] as? Number)?.toInt() ?: 1
            val endDateMs = (data["endDateMs"] as? Number)?.toLong()
                ?: (startDateMs + durationMonths * 30L * MILLIS_PER_DAY)
            val requestedPlanId = data["planId"] as? String ?: ""
            SubscriptionReceiptData(
                receiptNumber = data["receiptNumber"] as? String
                    ?: "SUB-${reviewedAt.takeIf { it > 0L } ?: documentId}",
                instituteName = data["instituteName"] as? String
                    ?: institute?.name.orEmpty(),
                ownerName = data["ownerName"] as? String
                    ?: institute?.ownerName.orEmpty(),
                ownerPhone = data["ownerPhone"] as? String
                    ?: institute?.phone.orEmpty(),
                ownerEmail = data["ownerEmail"] as? String
                    ?: institute?.email.orEmpty(),
                instituteCode = data["instituteCode"] as? String
                    ?: institute?.instituteCode.orEmpty(),
                instituteAddress = data["instituteAddress"] as? String
                    ?: institute?.address.orEmpty(),
                planName = data["planName"] as? String
                    ?: planDisplayName(requestedPlanId, plans),
                durationMonths = durationMonths,
                amountPaid = (data["amountPaid"] as? Number)?.toDouble() ?: 0.0,
                paymentMethod = data["paymentMethod"] as? String ?: "",
                transactionLast4 = data["transactionLast4"] as? String ?: "",
                startDateMs = startDateMs,
                endDateMs = endDateMs,
                senderPhone = data["senderPhone"] as? String ?: ""
            )
        }.sortedByDescending { it.startDateMs }
        recalculateStats(_institutes.value, plans)
    }

    private fun loadPendingRequestsRealtime() {
        lifecycleListeners += firestore.collection("subscriptionRequests")
            .whereEqualTo("status", "pending")
            .limit(ADMIN_LIST_WINDOW)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    FirebaseCrashlytics.getInstance().recordException(error)
                    _operationMsg.value = "Pending subscription requests unavailable: ${error.message}"
                    return@addSnapshotListener
                }
                _pendingRequests.value = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    SubscriptionRequest.fromFirestore(doc.id, data)
                }.sortedBy { it.requestSentAt }
            }
    }

    private fun cleanupInvalidPendingRequests() {
        viewModelScope.launch {
            try {
                SubscriptionRepository(firestore).cleanupInvalidPendingRequests()
            } catch (error: Exception) {
                // This is a best-effort migration for old invalid records. Never
                // interrupt valid payment review if an offline session cannot run it.
                FirebaseCrashlytics.getInstance().recordException(error)
            }
        }
    }

    fun approveRequest(request: SubscriptionRequest) {
        if (request.requestId in _approvingRequestIds.value) return
        _approvingRequestIds.value = _approvingRequestIds.value + request.requestId
        viewModelScope.launch {
            try {
                val result = SubscriptionRepository(firestore).approveRequest(
                    instituteId = request.instituteId,
                    requestId = request.requestId
                )
                var roomFailed = false
                try {
                    withContext(Dispatchers.IO) {
                        db.instituteDao().getInstitute(request.instituteId)?.let { current ->
                            db.instituteDao().insertInstitute(
                                current.copy(
                                    currentPlanId = request.requestedPlanId,
                                    subscriptionStatus = "active",
                                    currentPeriodEndMs = result.endDateMs
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    roomFailed = true
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
                _pendingRequests.value = _pendingRequests.value.filterNot { it.requestId == request.requestId }
                _institutes.value = _institutes.value.map { card ->
                    if (card.entity.id != request.instituteId) return@map card
                    card.copy(
                        entity = card.entity.copy(
                            currentPlanId = request.requestedPlanId,
                            subscriptionStatus = "active",
                            currentPeriodEndMs = result.endDateMs
                        )
                    )
                }
                rebuildReceiptHistory()
                refreshPlatformDashboard()
                _operationMsg.value = if (roomFailed) "Approved ${request.instituteName} (local cache update failed)" else "Approved ${request.instituteName}"
                _receiptData.value = SubscriptionReceiptData(
                    receiptNumber = result.receiptNumber,
                    instituteName = result.instituteName,
                    ownerName = result.ownerName,
                    ownerPhone = result.ownerPhone,
                    ownerEmail = result.ownerEmail,
                    instituteCode = result.instituteCode,
                    instituteAddress = result.instituteAddress,
                    planName = result.planName,
                    durationMonths = result.durationMonths,
                    amountPaid = result.amountPaid,
                    paymentMethod = result.paymentMethod,
                    transactionLast4 = result.transactionLast4,
                    startDateMs = result.startDateMs,
                    endDateMs = result.endDateMs,
                    senderPhone = result.senderPhone
                )
            } catch (e: Exception) {
                _operationMsg.value = "Approve failed: ${subscriptionOperationErrorMessage(e)}"
                FirebaseCrashlytics.getInstance().recordException(e)
            } finally {
                _approvingRequestIds.value = _approvingRequestIds.value - request.requestId
            }
        }
    }

    fun clearReceipt() { _receiptData.value = null }

    fun rejectRequest(request: SubscriptionRequest, note: String? = null) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SubscriptionRepository(firestore).rejectRequest(
                        instituteId = request.instituteId,
                        requestId = request.requestId,
                        note = note
                    )
                }
                _pendingRequests.value = _pendingRequests.value.filterNot { it.requestId == request.requestId }
                _operationMsg.value = "Rejected ${request.instituteName}"
            } catch (e: Exception) {
                _operationMsg.value = "Reject failed: ${subscriptionOperationErrorMessage(e)}"
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun loadInstitutesRealtime() {
        instituteFirstPageListener?.remove()
        resetInstituteListOnNextSnapshot = true
        nextInstitutePageCursor = null
        _hasMoreInstitutes.value = false
        _isLoading.value = true
        instituteFirstPageListener = firestore.collection("institutes")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(INSTITUTE_PAGE_SIZE)
            .addSnapshotListener { page, error ->
                if (error != null || page == null) {
                    error?.let(FirebaseCrashlytics.getInstance()::recordException)
                    _operationMsg.value = "Failed to load institutes: ${error?.message ?: "Unknown error"}"
                    _isLoading.value = false
                    return@addSnapshotListener
                }
                // A realtime refresh of page one must not rewind a cursor that
                // already advanced through later pages.
                if (resetInstituteListOnNextSnapshot || nextInstitutePageCursor == null) {
                    nextInstitutePageCursor = page.documents.lastOrNull()
                    _hasMoreInstitutes.value = page.documents.size == INSTITUTE_PAGE_SIZE.toInt()
                }
                val now = System.currentTimeMillis()
                val pageIds = page.documents.map { it.id }.toSet()
                val pageCards = page.documents.mapNotNull { instituteCardFromDocument(it, now) }
                val existingCards = if (resetInstituteListOnNextSnapshot) emptyList() else _institutes.value
                val mergedCards = (pageCards.associateBy { it.entity.id } +
                    existingCards.filterNot { it.entity.id in pageIds }.associateBy { it.entity.id })
                    .values
                    .sortedByDescending { it.entity.createdAtMs }
                resetInstituteListOnNextSnapshot = false
                _institutes.value = mergedCards
                val pageActive = page.documents.mapNotNull { document ->
                    (document.data?.get("lastActiveAt") as? Number)?.toLong()?.let { document.id to it }
                }.toMap()
                _lastActiveMap.value = _lastActiveMap.value.toMutableMap().apply {
                    pageIds.forEach(::remove)
                    putAll(pageActive)
                }
                recalculateStats(mergedCards)
                rebuildReceiptHistory()
                _isLoading.value = false
            }
    }

    private fun loadInstituteTotalCount() {
        viewModelScope.launch {
            try {
                val allCount = withContext(Dispatchers.IO) {
                    firestore.collection("institutes").count().get(AggregateSource.SERVER).await().count
                }
                val archivedCount = withContext(Dispatchers.IO) {
                    firestore.collection("institutes")
                        .whereEqualTo("deletionState", "retained")
                        .count()
                        .get(AggregateSource.SERVER)
                        .await()
                        .count
                }
                totalInstituteCount = (allCount - archivedCount).coerceAtLeast(0L).toInt()
                recalculateStats(_institutes.value)
            } catch (error: Exception) {
                FirebaseCrashlytics.getInstance().recordException(error)
            }
        }
    }

    fun loadMoreInstitutes() {
        if (_isLoadingMoreInstitutes.value || !_hasMoreInstitutes.value) return
        viewModelScope.launch { loadInstitutePage(reset = false) }
    }

    private suspend fun loadInstitutePage(reset: Boolean) {
        if (reset) {
            _isLoading.value = true
            nextInstitutePageCursor = null
            _hasMoreInstitutes.value = false
        } else {
            _isLoadingMoreInstitutes.value = true
        }
        try {
            val query = firestore.collection("institutes")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(INSTITUTE_PAGE_SIZE)
            val pagedQuery = nextInstitutePageCursor?.let { query.startAfter(it) } ?: query
            val page = withContext(Dispatchers.IO) {
                pagedQuery.get().await()
            }
            nextInstitutePageCursor = page.documents.lastOrNull()
            _hasMoreInstitutes.value = page.documents.size == INSTITUTE_PAGE_SIZE.toInt()

            val now = System.currentTimeMillis()
            val loadedCards = page.documents.mapNotNull { document -> instituteCardFromDocument(document, now) }
            val loadedActive = page.documents.mapNotNull { document ->
                val lastActiveAt = (document.data?.get("lastActiveAt") as? Number)?.toLong()
                lastActiveAt?.let { document.id to it }
            }.toMap()
            val mergedCards = if (reset) {
                loadedCards
            } else {
                (_institutes.value.associateBy { it.entity.id } + loadedCards.associateBy { it.entity.id })
                    .values
                    .sortedByDescending { it.entity.createdAtMs }
            }
            _institutes.value = mergedCards
            _lastActiveMap.value = if (reset) loadedActive else _lastActiveMap.value + loadedActive
            recalculateStats(mergedCards)
            rebuildReceiptHistory()
        } catch (error: Exception) {
            FirebaseCrashlytics.getInstance().recordException(error)
            _operationMsg.value = "Failed to load institutes: ${error.message}"
        } finally {
            _isLoading.value = false
            _isLoadingMoreInstitutes.value = false
        }
    }

    override fun onCleared() {
        instituteFirstPageListener?.remove()
        instituteFirstPageListener = null
        lifecycleListeners.forEach { it.remove() }
        lifecycleListeners.clear()
        super.onCleared()
    }

    private fun instituteCardFromDocument(document: DocumentSnapshot, now: Long): InstituteCardData? {
        val data = document.data ?: return null
        if (data["deletionState"] == "retained") return null
        val trialEnd = (data["trialEndDate"] as? Number)?.toLong() ?: now
        val periodEnd = (data["currentPeriodEndMs"] as? Number)?.toLong() ?: trialEnd
        val currentPlanId = data["currentPlanId"] as? String ?: DEFAULT_TRIAL_PLAN_ID
        val isActive = data["isActive"] as? Boolean ?: true
        val status = when {
            !isActive -> "blocked"
            periodEnd <= now -> "expired"
            currentPlanId == DEFAULT_TRIAL_PLAN_ID -> "trial"
            else -> "active"
        }
        return InstituteCardData(
            entity = InstituteEntity(
                id = document.id,
                name = data["instituteName"] as? String ?: "Institute",
                currentPlanId = currentPlanId,
                subscriptionStatus = status,
                trialStartDateMs = (data["createdAt"] as? Number)?.toLong() ?: now,
                trialEndDateMs = trialEnd,
                currentPeriodEndMs = periodEnd,
                createdAtMs = (data["createdAt"] as? Number)?.toLong() ?: now,
                phone = InstituteContactNumber.primary(
                    data["phone"] as? String,
                    data["whatsappNumber"] as? String
                ),
                whatsappNumber = InstituteContactNumber.whatsapp(
                    data["phone"] as? String,
                    data["whatsappNumber"] as? String
                ),
                profilePhotoUri = data["profilePhotoUri"] as? String,
                ownerName = data["ownerName"] as? String,
                email = data["email"] as? String,
                instituteCode = data["instituteCode"] as? String,
                securityPin = data["securityPin"] as? String
            ),
            studentCount = (data["studentCount"] as? Number)?.toInt() ?: 0,
            staffCount = (data["staffCount"] as? Number)?.toInt() ?: 0,
            batchCount = (data["batchCount"] as? Number)?.toInt() ?: 0
        )
    }

    private fun loadManagedUsersRealtime() {
        lifecycleListeners += firestore.collection("app_users")
            .whereIn("role", listOf("SuperAdmin", "superAdmin", "super_admin", "PlatformAdmin"))
            .limit(ADMIN_LIST_WINDOW)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                _managedUsers.value = snapshot.documents.mapNotNull { doc ->
                    val email = doc.getString("email") ?: return@mapNotNull null
                    val role = doc.getString("role") ?: return@mapNotNull null
                    ManagedUserSummary(
                        id = doc.id,
                        name = doc.getString("name") ?: email.substringBefore("@"),
                        email = email,
                        role = role,
                        instituteId = doc.getString("instituteId"),
                        createdAtMs = (doc.get("createdAtMs") as? Number)?.toLong() ?: System.currentTimeMillis(),
                        status = doc.getString("status") ?: "active"
                    )
                }.sortedWith(compareBy<ManagedUserSummary> { it.role }.thenBy { it.name.lowercase(Locale.getDefault()) })
            }
    }

    fun createManagedUser(
        name: String,
        email: String,
        role: String,
        instituteId: String?,
        password: String
    ) {
        val cleanName = name.trim()
        val cleanEmail = email.trim().lowercase(Locale.getDefault())
        _operationMsg.value = "Direct account creation is disabled. Use secure institute provisioning or platform role access."
    }

    fun updateManagedUser(
        existing: ManagedUserSummary,
        name: String,
        role: String,
        instituteId: String?,
        password: String
    ) {
        val cleanName = name.trim()
        _operationMsg.value = "Direct account editing is disabled. Use Owner Access or Platform Roles with an audit reason."
    }

    fun extendSubscription(instituteId: String, daysToAdd: Int, reason: String) {
        if (reason.trim().length < 3) {
            _operationMsg.value = "Please record why access is being extended."
            return
        }
        if (!synchronized(subscriptionExtensionsInProgress) { subscriptionExtensionsInProgress.add(instituteId) }) {
            _operationMsg.value = "This subscription extension is already being processed."
            return
        }
        viewModelScope.launch {
            try {
                val updated = SubscriptionRepository(firestore).extendSubscription(instituteId, daysToAdd, reason)
                withContext(Dispatchers.IO) {
                    db.instituteDao().getInstitute(instituteId)?.let { current ->
                        db.instituteDao().insertInstitute(
                            current.copy(
                                subscriptionStatus = updated.subscriptionStatus,
                                currentPeriodEndMs = updated.currentPeriodEndMs
                            )
                        )
                    }
                }
                _institutes.value = _institutes.value.map { card ->
                    if (card.entity.id != instituteId) return@map card
                    card.copy(entity = card.entity.copy(
                        subscriptionStatus = updated.subscriptionStatus,
                        currentPeriodEndMs = updated.currentPeriodEndMs
                    ))
                }
                refreshPlatformDashboard()
                _operationMsg.value = "Subscription extended by $daysToAdd days"
            } catch (e: Exception) {
                _operationMsg.value = "Failed: ${e.message}"
            } finally {
                synchronized(subscriptionExtensionsInProgress) { subscriptionExtensionsInProgress.remove(instituteId) }
            }
        }
    }

    fun toggleBlock(instituteId: String, currentBlocked: Boolean) {
        viewModelScope.launch {
            try {
                val newBlocked = !currentBlocked
                val updated = SubscriptionRepository(firestore).setInstituteBlocked(instituteId, newBlocked)
                withContext(Dispatchers.IO) {
                    db.instituteDao().getInstitute(instituteId)?.let { current ->
                        db.instituteDao().insertInstitute(
                            current.copy(
                                subscriptionStatus = updated.subscriptionStatus,
                                currentPeriodEndMs = updated.currentPeriodEndMs
                            )
                        )
                    }
                }
                _institutes.value = _institutes.value.map { card ->
                    if (card.entity.id != instituteId) return@map card
                    card.copy(entity = card.entity.copy(
                        subscriptionStatus = updated.subscriptionStatus,
                        currentPeriodEndMs = updated.currentPeriodEndMs
                    ))
                }
                refreshPlatformDashboard()
                _operationMsg.value = if (newBlocked) "Institute blocked" else "Institute unblocked"
            } catch (e: Exception) {
                _operationMsg.value = "Failed: ${e.message}"
            }
        }
    }

    // ── Trash / Remove Institute ─────────────────────────────
    private val _trashedInstitutes = MutableStateFlow<List<InstituteCardData>>(emptyList())
    val trashedInstitutes = _trashedInstitutes.asStateFlow()

    private fun loadTrashedInstitutes() {
        lifecycleListeners += firestore.collection("institutes")
            .whereEqualTo("deletionState", "retained")
            .limit(ADMIN_LIST_WINDOW)
            .addSnapshotListener { docs, error ->
                if (error != null || docs == null) {
                    error?.let(FirebaseCrashlytics.getInstance()::recordException)
                    _operationMsg.value = "Failed to load retained institutes."
                    return@addSnapshotListener
                }
                _trashedInstitutes.value = docs.documents.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    InstituteCardData(
                        entity = InstituteEntity(
                            id = doc.id,
                            name = data["instituteName"] as? String ?: "Institute",
                            currentPlanId = data["currentPlanId"] as? String ?: DEFAULT_TRIAL_PLAN_ID,
                            subscriptionStatus = "deletion_pending",
                            trialStartDateMs = (data["createdAt"] as? Number)?.toLong() ?: 0L,
                            trialEndDateMs = (data["archivedAtMs"] as? Number)?.toLong() ?: 0L,
                            currentPeriodEndMs = (data["retentionUntilMs"] as? Number)?.toLong() ?: 0L,
                            createdAtMs = (data["createdAt"] as? Number)?.toLong() ?: 0L,
                            phone = InstituteContactNumber.primary(
                                data["phone"] as? String,
                                data["whatsappNumber"] as? String
                            ),
                            whatsappNumber = InstituteContactNumber.whatsapp(
                                data["phone"] as? String,
                                data["whatsappNumber"] as? String
                            ),
                            ownerName = data["ownerName"] as? String,
                            email = data["email"] as? String
                        ),
                        studentCount = 0,
                        staffCount = 0
                    )
                }
            }
    }

    fun removeInstitute(instituteId: String, superAdminPassword: String) {
        viewModelScope.launch {
            try {
                val email = FirebaseAuth.getInstance().currentUser?.email
                if (email == null || superAdminPassword.isBlank()) {
                    _operationMsg.value = "Please enter your password."
                    return@launch
                }
                try {
                    val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(email, superAdminPassword)
                    FirebaseAuth.getInstance().currentUser!!.reauthenticate(credential).await()
                } catch (e: Exception) {
                    _operationMsg.value = "Wrong super admin password."
                    return@launch
                }
                val card = _institutes.value.find { it.entity.id == instituteId } ?: return@launch
                val result = safeDeletionRepository.archiveInstitute(
                    instituteId,
                    "Institute archived after SuperAdmin re-authentication"
                )
                _trashedInstitutes.value = _trashedInstitutes.value + listOf(card.copy(
                    entity = card.entity.copy(
                        subscriptionStatus = "deletion_pending",
                        trialEndDateMs = result.archivedAtMs ?: System.currentTimeMillis(),
                        currentPeriodEndMs = result.retentionUntilMs ?: 0L
                    )
                ))
                _institutes.value = _institutes.value.filter { it.entity.id != instituteId }
                refreshPlatformDashboard()
                _operationMsg.value = "${card.entity.name} archived. Data and ledger are retained for recovery."
            } catch (e: Exception) {
                _operationMsg.value = "Failed: ${e.message}"
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    fun restoreInstitute(instituteId: String) {
        viewModelScope.launch {
            try {
                val card = _trashedInstitutes.value.find { it.entity.id == instituteId } ?: return@launch
                val result = safeDeletionRepository.restoreInstitute(
                    instituteId,
                    "Institute restored from retained deletion state"
                )
                _trashedInstitutes.value = _trashedInstitutes.value.filter { it.entity.id != instituteId }
                _institutes.value = _institutes.value + listOf(card.copy(
                    entity = card.entity.copy(
                        subscriptionStatus = result.subscriptionStatus ?: "active",
                        trialEndDateMs = card.entity.currentPeriodEndMs
                    )
                ))
                refreshPlatformDashboard()
                _operationMsg.value = "${card.entity.name} restored."
            } catch (e: Exception) {
                _operationMsg.value = "Failed: ${e.message}"
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    fun manageInstitute(
        instituteId: String,
        newExpiryMs: Long,
        studentLimit: Int,
        staffLimit: Int,
        planId: String,
        isActive: Boolean,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                val updated = SubscriptionRepository(firestore).manageInstituteSubscription(
                    instituteId = instituteId,
                    newExpiryMs = newExpiryMs,
                    studentLimit = studentLimit,
                    staffLimit = staffLimit,
                    planId = planId,
                    isActive = isActive
                )
                withContext(Dispatchers.IO) {
                    db.instituteDao().getInstitute(instituteId)?.let { current ->
                        db.instituteDao().insertInstitute(
                            current.copy(
                                currentPlanId = updated.currentPlanId,
                                subscriptionStatus = updated.subscriptionStatus,
                                currentPeriodEndMs = updated.currentPeriodEndMs
                            )
                        )
                    }
                }
                _institutes.value = _institutes.value.map { card ->
                    if (card.entity.id != instituteId) return@map card
                    card.copy(entity = card.entity.copy(
                        currentPlanId = updated.currentPlanId,
                        subscriptionStatus = updated.subscriptionStatus,
                        currentPeriodEndMs = updated.currentPeriodEndMs
                    ))
                }
                refreshPlatformDashboard()
                _operationMsg.value = "Institute updated successfully"
                onDone()
            } catch (e: Exception) {
                _operationMsg.value = "Failed: ${e.message}"
            }
        }
    }

    fun saveSubscriptionPlan(
        existingPlanId: String?,
        plan: SubscriptionPlanEntity
    ) {
        viewModelScope.launch {
            try {
                val duplicateName = _subscriptionPlans.value.any {
                    it.id != existingPlanId && it.name.equals(plan.name, ignoreCase = true)
                }
                if (duplicateName) {
                    _operationMsg.value = "A plan with this name already exists."
                    return@launch
                }
                if (existingPlanId != null && existingPlanId != plan.id) {
                    _operationMsg.value = "Plan ID cannot be changed after institutes use it. Create a new plan instead."
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    db.subscriptionPlanDao().insertPlans(listOf(plan))
                    SubscriptionPlanSyncHelper.upsertPlans(listOf(plan))
                }
                _operationMsg.value = if (existingPlanId == null) {
                    "Subscription plan created."
                } else {
                    "Subscription plan updated."
                }
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                _operationMsg.value = "Plan save failed: ${e.message}"
            }
        }
    }

    fun deleteSubscriptionPlan(planId: String) {
        if (planId == DEFAULT_TRIAL_PLAN_ID) {
            _operationMsg.value = "Free Trial plan cannot be deleted."
            return
        }
        if (_institutes.value.any { it.entity.currentPlanId == planId }) {
            _operationMsg.value = "This plan is assigned to one or more institutes."
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SubscriptionPlanSyncHelper.deletePlan(planId)
                    db.subscriptionPlanDao().deletePlanById(planId)
                }
                _operationMsg.value = "Subscription plan deleted."
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                _operationMsg.value = "Plan delete failed: ${e.message}"
            }
        }
    }

    fun broadcastAnnouncement(message: String, expiryDays: Int) {
        if (message.isBlank()) {
            _operationMsg.value = "Message cannot be empty."
            return
        }
        if (message.length > 500) {
            _operationMsg.value = "Message must be under 500 characters."
            return
        }
        viewModelScope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val expiresAt = if (expiryDays > 0) now + (expiryDays * MILLIS_PER_DAY) else null
                val data = mapOf(
                    "id" to id,
                    "message" to message.trim(),
                    "sentAt" to now,
                    "updatedAt" to now,
                    "expiresAt" to expiresAt,
                    "sender" to "SuperAdmin",
                    "senderName" to "BatchFee Support",
                    "platform" to "android",
                    "status" to "active",
                    "version" to 1
                )
                withContext(Dispatchers.IO) {
                    firestore.collection("Global_Notifications")
                        .document(id).set(data).await()
                }
                _operationMsg.value = "Announcement broadcast to all institutes!"
            } catch (e: Exception) {
                _operationMsg.value = "Failed to send: ${e.message}"
            }
        }
    }

    fun editAnnouncement(id: String, message: String, expiryDays: Int) {
        if (message.isBlank()) { _operationMsg.value = "Message cannot be empty."; return }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val expiresAt = if (expiryDays > 0) now + (expiryDays * MILLIS_PER_DAY) else null
                withContext(Dispatchers.IO) {
                    firestore.collection("Global_Notifications").document(id)
                        .update(mapOf("message" to message.trim(), "updatedAt" to now, "expiresAt" to expiresAt)).await()
                }
                _operationMsg.value = "Announcement updated."
            } catch (e: Exception) { _operationMsg.value = "Failed: ${e.message}" }
        }
    }

    fun archiveAnnouncement(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    firestore.collection("Global_Notifications").document(id)
                        .update(mapOf("status" to "archived", "archivedAt" to System.currentTimeMillis())).await()
                }
                _operationMsg.value = "Announcement archived. Restore it any time from Archived announcements."
            } catch (e: Exception) { _operationMsg.value = "Failed: ${e.message}" }
        }
    }

    fun restoreAnnouncement(id: String) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    firestore.collection("Global_Notifications").document(id)
                        .update(mapOf("status" to "active", "updatedAt" to now, "restoredAt" to now)).await()
                }
                _operationMsg.value = "Announcement restored and visible again."
            } catch (e: Exception) { _operationMsg.value = "Restore failed: ${e.message}" }
        }
    }

    fun permanentlyDeleteInstitute(instituteId: String) {
        if (instituteId in _purgingInstituteIds.value) return
        viewModelScope.launch {
            _purgingInstituteIds.value = _purgingInstituteIds.value + instituteId
            try {
                val card = _trashedInstitutes.value.find { it.entity.id == instituteId }
                permanentArchivePurgeRepository.purgeInstitute(instituteId)
                _trashedInstitutes.value = _trashedInstitutes.value.filter { it.entity.id != instituteId }
                refreshPlatformDashboard()
                _operationMsg.value = "${card?.entity?.name ?: "Institute"} permanently deleted."
            } catch (error: Exception) {
                _operationMsg.value = "Permanent delete failed: ${error.message ?: "Please try again."}"
                FirebaseCrashlytics.getInstance().recordException(error)
            } finally {
                _purgingInstituteIds.value = _purgingInstituteIds.value - instituteId
            }
        }
    }

    fun deleteAnnouncement(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    firestore.collection("Global_Notifications").document(id)
                        .update("status", "deleted").await()
                }
                _operationMsg.value = "Announcement deleted."
            } catch (e: Exception) { _operationMsg.value = "Failed: ${e.message}" }
        }
    }

    private fun loadAllAnnouncements() {
        lifecycleListeners += firestore.collection("Global_Notifications")
            .limit(ADMIN_LIST_WINDOW)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                _announcements.value = snapshot.documents.mapNotNull { doc ->
                    val d = doc.data ?: return@mapNotNull null
                    AnnouncementData(
                        id = doc.id,
                        message = d["message"] as? String ?: "",
                        sentAt = (d["sentAt"] as? Number)?.toLong() ?: 0L,
                        updatedAt = (d["updatedAt"] as? Number)?.toLong() ?: (d["sentAt"] as? Number)?.toLong() ?: 0L,
                        expiresAt = (d["expiresAt"] as? Number)?.toLong(),
                        status = d["status"] as? String ?: "active",
                        sender = d["senderName"] as? String ?: (d["sender"] as? String ?: "SuperAdmin"),
                        platform = d["platform"] as? String ?: "android"
                    )
                }.sortedByDescending { it.sentAt }
            }
    }

    fun clearExpiredAnnouncements() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val expired = _announcements.value.filter {
                it.status == "active" && it.expiresAt != null && it.expiresAt < now
            }
            expired.forEach { a ->
                withContext(Dispatchers.IO) {
                    firestore.collection("Global_Notifications").document(a.id)
                        .update("status", "expired").await()
                }
            }
        }
    }

    fun loadInstituteStaff(instituteId: String, onResult: (List<InstituteStaffSummary>) -> Unit) {
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    firestore.collection("institutes").document(instituteId)
                        .collection("staffs")
                        .limit(INSTITUTE_DETAIL_WINDOW)
                        .get().await()
                }
                val staffList = data.documents.mapNotNull { doc ->
                    val d = doc.data ?: return@mapNotNull null
                    if (d["status"] == "archived") return@mapNotNull null
                    InstituteStaffSummary(
                        id = doc.id,
                        fullName = d["fullName"] as? String ?: "N/A",
                        staffCode = d["staffCode"] as? String ?: "",
                        roleTitle = d["roleTitle"] as? String ?: "N/A",
                        status = d["status"] as? String ?: "active",
                        phone = d["phone"] as? String ?: "",
                        email = d["email"] as? String ?: ""
                    )
                }.sortedBy { it.fullName }
                onResult(staffList)
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                onResult(emptyList())
            }
        }
    }

    fun loadInstituteReceipts(instituteId: String, onResult: (List<SubscriptionReceiptData>) -> Unit) {
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    firestore.collection("institutes").document(instituteId)
                        .collection("subscription_receipts")
                        .orderBy("approvedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                        .limit(INSTITUTE_DETAIL_WINDOW)
                        .get().await()
                }
                val plans = _subscriptionPlans.value
                val receipts = data.documents.mapNotNull { doc ->
                    val d = doc.data ?: return@mapNotNull null
                    SubscriptionReceiptData(
                        receiptNumber = d["receiptNumber"] as? String ?: doc.id,
                        instituteName = d["instituteName"] as? String ?: "",
                        ownerName = d["ownerName"] as? String ?: "",
                        ownerPhone = d["ownerPhone"] as? String ?: "",
                        ownerEmail = d["ownerEmail"] as? String ?: "",
                        instituteCode = d["instituteCode"] as? String ?: "",
                        instituteAddress = d["instituteAddress"] as? String ?: "",
                        planName = d["planName"] as? String ?: planDisplayName(d["planId"] as? String ?: "", plans),
                        durationMonths = (d["durationMonths"] as? Number)?.toInt() ?: 1,
                        amountPaid = (d["amountPaid"] as? Number)?.toDouble() ?: 0.0,
                        paymentMethod = d["paymentMethod"] as? String ?: "",
                        transactionLast4 = d["transactionLast4"] as? String ?: "",
                        startDateMs = (d["startDateMs"] as? Number)?.toLong() ?: (d["approvedAt"] as? Number)?.toLong() ?: 0L,
                        endDateMs = (d["endDateMs"] as? Number)?.toLong() ?: 0L,
                        senderPhone = d["senderPhone"] as? String ?: ""
                    )
                }.sortedByDescending { it.startDateMs }
                onResult(receipts)
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
                onResult(emptyList())
            }
        }
    }

    fun lastActiveLabel(instituteId: String): String {
        val ts = _lastActiveMap.value[instituteId] ?: return "Never"
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            diff < 2_592_000_000 -> "${diff / 86_400_000}d ago"
            else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(ts))
        }
    }

    fun sendOwnerRecovery(instituteId: String, reason: String) {
        if (reason.trim().length < 3) {
            _operationMsg.value = "Enter a reason before creating an owner recovery link."
            return
        }
        viewModelScope.launch {
            try {
                val result = PlatformAdminRepository().ownerRecovery(instituteId, reason)
                _lastRecoveryLink.value = result.recoveryLink.takeIf { it.isNotBlank() }
                _operationMsg.value = if (result.recoveryLink.isBlank()) {
                    "Recovery audited, but no reset link was generated."
                } else {
                    "Recovery link generated once. Copy it from Owner Access."
                }
            } catch (e: Exception) {
                _operationMsg.value = "Failed: ${e.message}"
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }


    fun refreshPlatformDashboard() {
        viewModelScope.launch {
            try {
                val metrics = PlatformAdminRepository().dashboard()
                hasServerDashboard = true
                _stats.value = SuperAdminStats(
                    totalInstitutes = metrics.totalInstitutes, activeSubscriptions = metrics.activeInstitutes,
                    lifetimeRevenue = metrics.lifetimeRevenue, thisMonthRevenue = metrics.thisMonthRevenue,
                    totalStudents = metrics.totalStudents, totalStaff = metrics.totalStaff,
                    expiringIn7Days = metrics.expiringIn7Days, expiringIn30Days = metrics.expiringIn30Days,
                    canonicalReceiptCount = metrics.canonicalReceiptCount, snapshotAtMs = metrics.snapshotAtMs
                )
            } catch (error: Exception) {
                FirebaseCrashlytics.getInstance().recordException(error)
                _operationMsg.value = "Live platform metrics unavailable: ${error.message}"
            }
        }
    }

    fun loadInstituteOwnerLoginActivity(
        instituteId: String,
        onResult: (InstituteOwnerLoginActivity?, String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                onResult(ownerLoginActivityRepository.getOwnerLoginActivity(instituteId), null)
            } catch (error: Exception) {
                FirebaseCrashlytics.getInstance().recordException(error)
                onResult(null, "Login activity could not be loaded right now.")
            }
        }
    }

    fun transferOwner(instituteId: String, ownerName: String, ownerEmail: String, reason: String) {
        if (ownerName.isBlank() || ownerEmail.isBlank() || reason.trim().length < 3) {
            _operationMsg.value = "Owner name, email, and transfer reason are required."
            return
        }
        viewModelScope.launch {
            try {
                val result = PlatformAdminRepository().transferOwner(instituteId, ownerName, ownerEmail, reason)
                _lastRecoveryLink.value = result.recoveryLink.takeIf { it.isNotBlank() }
                _operationMsg.value = "Owner access transferred and audited. The previous owner is now an institute admin."
                loadInstitutesRealtime()
            } catch (error: Exception) { _operationMsg.value = "Owner transfer failed: ${error.message}" }
        }
    }

    fun createInstitute(draft: PlatformInstituteDraft) {
        viewModelScope.launch {
            try {
                val result = PlatformAdminRepository().createInstitute(draft)
                _lastRecoveryLink.value = result.recoveryLink.takeIf { it.isNotBlank() }
                _operationMsg.value = "${result.instituteName} was provisioned securely."
                loadInstitutesRealtime(); refreshPlatformDashboard()
            } catch (error: Exception) { _operationMsg.value = "Institute creation failed: ${error.message}" }
        }
    }

    fun previewInstituteImport(rows: List<PlatformInstituteDraft>, onPreview: (List<com.batchfee.edu.data.repository.ImportPreviewRow>) -> Unit) {
        viewModelScope.launch {
            try { onPreview(PlatformAdminRepository().previewInstituteImport(rows)) }
            catch (error: Exception) { _operationMsg.value = "CSV preview failed: ${error.message}"; onPreview(emptyList()) }
        }
    }

    fun importInstitutes(rows: List<PlatformInstituteDraft>, validRows: Set<Int>, batchId: String) {
        if (validRows.isEmpty()) { _operationMsg.value = "No valid rows are ready to import."; return }
        viewModelScope.launch {
            val succeeded = _bulkImportReport.value.successfulRows.toMutableSet()
            val failures = _bulkImportReport.value.failedRows.toMutableMap()
            _bulkImportReport.value = BulkImportReport(batchId, succeeded, failures, running = true)
            validRows.sorted().forEach { index ->
                if (index !in succeeded) try {
                    PlatformAdminRepository().createInstitute(rows[index], "${batchId}_row_${index.toString().padStart(4, '0')}")
                    succeeded += index; failures.remove(index)
                } catch (error: Exception) { failures[index] = error.message ?: "Server rejected this row." }
                _bulkImportReport.value = BulkImportReport(batchId, succeeded.toSet(), failures.toMap(), running = true)
            }
            _bulkImportReport.value = BulkImportReport(batchId, succeeded.toSet(), failures.toMap(), running = false)
            _operationMsg.value = "Bulk import complete: ${succeeded.size} created, ${failures.size} failed."
            loadInstitutesRealtime(); refreshPlatformDashboard()
        }
    }

    fun provisionPlatformRole(name: String, email: String, role: String) {
        viewModelScope.launch {
            try {
                val result = PlatformAdminRepository().provisionPlatformAdmin(name, email, role)
                _lastRecoveryLink.value = result.recoveryLink.takeIf { it.isNotBlank() }
                _operationMsg.value = "${role.replace('_', ' ')} access provisioned and audited."
            } catch (error: Exception) { _operationMsg.value = "Platform role update failed: ${error.message}" }
        }
    }

    private fun loadPlatformAudit() {
        lifecycleListeners += firestore.collection("platform_audit")
            .orderBy("createdAtMs", Query.Direction.DESCENDING)
            .limit(ADMIN_LIST_WINDOW)
            .addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            _platformAudit.value = snapshot.documents.mapNotNull { document ->
                val data = document.data ?: return@mapNotNull null
                val details = data["details"] as? Map<*, *>
                PlatformAuditEntry(document.id, data["action"] as? String ?: "platform_operation",
                    data["actorUid"] as? String ?: "", data["instituteId"] as? String ?: "",
                    (data["createdAtMs"] as? Number)?.toLong() ?: 0L,
                    details?.entries?.joinToString(" · ") { "${it.key}: ${it.value}" }.orEmpty())
            }.sortedByDescending { it.createdAtMs }
        }
    }

    fun loadInstituteAudit(instituteId: String, onResult: (List<PlatformAuditEntry>) -> Unit) {
        viewModelScope.launch {
            try {
                val snapshot = firestore.collection("institutes").document(instituteId)
                    .collection("subscription_audit")
                    .orderBy("createdAtMs", Query.Direction.DESCENDING)
                    .limit(INSTITUTE_DETAIL_WINDOW)
                    .get().await()
                onResult(snapshot.documents.mapNotNull { document ->
                    val data = document.data ?: return@mapNotNull null
                    PlatformAuditEntry(document.id, data["action"] as? String ?: "subscription_operation",
                        data["actorUid"] as? String ?: "", instituteId,
                        (data["createdAtMs"] as? Number)?.toLong() ?: 0L,
                        (data["details"] as? Map<*, *>)?.entries?.joinToString(" · ") { "${it.key}: ${it.value}" }.orEmpty())
                }.sortedByDescending { it.createdAtMs })
            } catch (_: Exception) { onResult(emptyList()) }
        }
    }

    // Kept only so legacy card state cannot send an unaudited Firebase Auth reset.
    fun sendPasswordReset(email: String?) {
        _operationMsg.value = "Use Owner Access in institute details to create an audited one-time recovery link."
    }

    fun setSecurityPin(instituteId: String, pin: String) {
        if (pin.isBlank() || !pin.matches(Regex("^\\d{4,6}$"))) {
            _operationMsg.value = "PIN must be 4-6 digits."
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    firestore.collection("institutes").document(instituteId)
                        .update("securityPin", pin).await()
                }
                _operationMsg.value = "Security PIN updated"
            } catch (e: Exception) {
                _operationMsg.value = "Failed: ${e.message}"
            }
        }
    }
}

class SuperAdminViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SuperAdminViewModel::class.java)) return SuperAdminViewModel(db) as T
        throw IllegalArgumentException()
    }
}

// ── Screen ────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperAdminScreen(db: AppDatabase, onLogout: () -> Unit) {
    val viewModel: SuperAdminViewModel = viewModel(factory = SuperAdminViewModelFactory(db))
    val institutes by viewModel.institutes.collectAsState()
    val trashedInstitutes by viewModel.trashedInstitutes.collectAsState()
    val subscriptionPlans by viewModel.subscriptionPlans.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val pendingRequests by viewModel.pendingRequests.collectAsState()
    val approvingRequestIds by viewModel.approvingRequestIds.collectAsState()
    val managedUsers by viewModel.managedUsers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val hasMoreInstitutes by viewModel.hasMoreInstitutes.collectAsState()
    val isLoadingMoreInstitutes by viewModel.isLoadingMoreInstitutes.collectAsState()
    val operationMsg by viewModel.operationMsg.collectAsState()
    val receiptData by viewModel.receiptData.collectAsState()
    val lastActiveMap by viewModel.lastActiveMap.collectAsState()
    val platformAudit by viewModel.platformAudit.collectAsState()
    val recoveryLink by viewModel.lastRecoveryLink.collectAsState()
    val importReport by viewModel.bulkImportReport.collectAsState()
    val purgingInstituteIds by viewModel.purgingInstituteIds.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(operationMsg) {
        operationMsg?.let { snackbarHostState.showSnackbar(it); viewModel.clearOperationMsg() }
    }

    var announceText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") }
    var lastActivityFilter by remember { mutableStateOf("all") }
    var lastActivityMenuOpen by remember { mutableStateOf(false) }
    // Kept for future advanced search. The normal dashboard intentionally uses
    // only the two controls above.
    var planFilter by remember { mutableStateOf("all") }
    var expiryFilter by remember { mutableStateOf("all") }
    var directoryLayout by remember { mutableStateOf("grid") }
    var directorySort by remember { mutableStateOf("newest") }
    var showCreateInstitute by remember { mutableStateOf(false) }
    var showCsvImport by remember { mutableStateOf(false) }
    var showPlatformRoles by remember { mutableStateOf(false) }
    var showAuditHistory by remember { mutableStateOf(false) }
    var selectedInstitute by remember { mutableStateOf<InstituteCardData?>(null) }
    var userSearchQuery by remember { mutableStateOf("") }
    var userRoleFilter by remember { mutableStateOf("all") }
    var showUserDialog by remember { mutableStateOf(false) }
    var editingUser by remember { mutableStateOf<ManagedUserSummary?>(null) }
    var editingPlan by remember { mutableStateOf<SubscriptionPlanEntity?>(null) }
    var showPlanDialog by remember { mutableStateOf(false) }
    var showReceiptDialog by remember { mutableStateOf(false) }
    var permanentDeleteTarget by remember { mutableStateOf<InstituteCardData?>(null) }
    var recoveryVaultExpanded by remember { mutableStateOf(false) }

    // Open receipt actions only after server approval returned the saved receipt.
    LaunchedEffect(receiptData) {
        if (receiptData != null) showReceiptDialog = true
    }

    val planNameById = remember(subscriptionPlans) { subscriptionPlans.associate { it.id to it.name } }

    val filteredInstitutes = remember(institutes, searchQuery, statusFilter, lastActivityFilter, lastActiveMap) {
        val now = System.currentTimeMillis()
        institutes.filter { card ->
            val inst = card.entity
            val matchesSearch = searchQuery.isBlank() ||
                inst.name.contains(searchQuery, ignoreCase = true) ||
                (inst.instituteCode?.contains(searchQuery, ignoreCase = true) ?: false) ||
                (inst.ownerName?.contains(searchQuery, ignoreCase = true) ?: false) ||
                (inst.phone?.contains(searchQuery) ?: false) ||
                (inst.email?.contains(searchQuery, ignoreCase = true) ?: false)
            val matchesFilter = statusFilter == "all" || inst.subscriptionStatus == statusFilter
            val lastActiveAt = lastActiveMap[inst.id]
            val matchesActivity = when (lastActivityFilter) {
                "today" -> lastActiveAt?.let { now - it in 0..MILLIS_PER_DAY } == true
                "7days" -> lastActiveAt?.let { now - it in 0..(7 * MILLIS_PER_DAY) } == true
                "30days" -> lastActiveAt?.let { now - it in 0..(30 * MILLIS_PER_DAY) } == true
                "inactive30" -> lastActiveAt == null || now - lastActiveAt > 30 * MILLIS_PER_DAY
                "never" -> lastActiveAt == null
                else -> true
            }
            matchesSearch && matchesFilter && matchesActivity
        }
    }
    val instituteNameMap = remember(institutes) { institutes.associate { it.entity.id to it.entity.name } }
    val filteredUsers = remember(managedUsers, userSearchQuery, userRoleFilter, instituteNameMap) {
        managedUsers.filter { user ->
            val matchesSearch = userSearchQuery.isBlank() ||
                user.name.contains(userSearchQuery, ignoreCase = true) ||
                user.email.contains(userSearchQuery, ignoreCase = true) ||
                (instituteNameMap[user.instituteId]?.contains(userSearchQuery, ignoreCase = true) ?: false)
            val matchesRole = userRoleFilter == "all" || user.role == userRoleFilter
            matchesSearch && matchesRole
        }
    }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                            .background(Brush.horizontalGradient(listOf(AccentCyan, ElectricBlue))),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.Shield, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Super Admin", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("BatchFee Platform", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor),
                actions = {
                    IconButton(onClick = { SessionManager.logout(); onLogout() }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, "Logout", tint = AccentRed)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ── Platform Overview ──
            item {
                Text("Platform Overview", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        CompactStat("Institutes", if (isLoading) "..." else stats.totalInstitutes.toString(), AccentCyan, Icons.Filled.Business, Modifier.weight(1f))
                        CompactStat("Active", if (isLoading) "..." else stats.activeSubscriptions.toString(), AccentGreen, Icons.Filled.Verified, Modifier.weight(1f))
                        CompactStat("Students", if (isLoading) "..." else stats.totalStudents.toString(), AccentViolet, Icons.Filled.People, Modifier.weight(1f))
                        CompactStat("Staff", if (isLoading) "..." else stats.totalStaff.toString(), AccentPink, Icons.Filled.Badge, Modifier.weight(1f))
                    }
                }
            }

            // ── Revenue Section ──
            if (trashedInstitutes.isNotEmpty()) {
                item {
                    RecoveryVaultSection(
                        institutes = trashedInstitutes,
                        purgingInstituteIds = purgingInstituteIds,
                        expanded = recoveryVaultExpanded,
                        onExpandedChange = { recoveryVaultExpanded = it },
                        onRestore = viewModel::restoreInstitute,
                        onDelete = { permanentDeleteTarget = it }
                    )
                }
            }

            item {
                SubscriptionPlanSection(
                    plans = subscriptionPlans,
                    institutes = institutes,
                    onCreate = {
                        editingPlan = null
                        showPlanDialog = true
                    },
                    onEdit = { plan ->
                        editingPlan = plan
                        showPlanDialog = true
                    },
                    onDelete = viewModel::deleteSubscriptionPlan
                )
            }


            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { RevenueCard("Lifetime revenue", if (isLoading) "..." else "BDT ${NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 0 }.format(stats.lifetimeRevenue)}", AccentCyan, Icons.Filled.TrendingUp) }
                    Box(Modifier.weight(1f)) { RevenueCard("This Month", if (isLoading) "..." else "BDT ${NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 0 }.format(stats.thisMonthRevenue)}", AccentGreen, Icons.Filled.MonetizationOn) }
                }
            }

            // ── Canonical subscription metrics ──
            item {
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Expiring in 30 days", color = TextMuted, fontSize = 11.sp)
                            Text(stats.expiringIn30Days.toString(), color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Canonical subscription receipts", color = TextMuted, fontSize = 11.sp)
                            Text(stats.canonicalReceiptCount.toString(), color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                    }
                }
            }

            // ── Global Broadcast ──
            // Kept directly under the overview so payment approvals are never hidden.
            item {
                SubscriptionRequestSection(
                    requests = pendingRequests,
                    institutes = institutes,
                    plans = subscriptionPlans,
                    approvingRequestIds = approvingRequestIds,
                    onApprove = viewModel::approveRequest,
                    onReject = { request, note -> viewModel.rejectRequest(request, note) }
                )
            }

            item {
                BroadcastSection(
                    announceText = announceText,
                    onAnnounceTextChange = { announceText = it },
                    activeInstituteCount = stats.activeSubscriptions,
                    onSend = { msg, days ->
                        viewModel.broadcastAnnouncement(msg, days)
                        announceText = ""
                    },
                    announcements = viewModel.announcements.collectAsState().value,
                    onEdit = { a, msg, days -> viewModel.editAnnouncement(a.id, msg, days) },
                    onArchive = { viewModel.archiveAnnouncement(it.id) },
                    onRestore = { viewModel.restoreAnnouncement(it.id) },
                    onDelete = { viewModel.deleteAnnouncement(it.id) }
                )
            }

            // ── Pending Requests ──
            if (false && pendingRequests.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Pending Requests · ${pendingRequests.size}", color = AccentAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                items(pendingRequests, key = { it.requestId }) { req ->
                    val requestInstitute = institutes.firstOrNull { it.entity.id == req.instituteId }?.entity
                    val requestedPlanName = planNameById[req.requestedPlanId] ?: humanizePlanId(req.requestedPlanId)
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.3f))
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(req.instituteName, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(AccentAmber.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                    Text(req.status.uppercase(), color = AccentAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = BorderSub.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, BorderSub)
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.WorkspacePremium, null, tint = AccentViolet, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(requestedPlanName, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Text("BDT ${"%,.0f".format(req.amountPaid)}", color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        "${req.durationMonths} month(s) · ${if (req.studentLimitAtRequest > 0) "Up to ${req.studentLimitAtRequest} students" else "Plan capacity"}",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        if (req.senderPhone.isNotBlank()) {
                                            "${req.paymentMethod.uppercase()} · Sent from ${req.senderPhone}"
                                        } else {
                                            "${req.paymentMethod.uppercase()} · Ref: ••••${req.transactionLast4}"
                                        },
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Person, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(req.ownerName.ifBlank { "Owner not provided" }, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                requestInstitute?.instituteCode?.takeIf { it.isNotBlank() }?.let { code ->
                                    Text(code, color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            req.institutePhone?.takeIf { it.isNotBlank() }?.let { phone ->
                                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Phone, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(phone, color = AccentGreen, fontSize = 11.sp)
                                }
                            }
                            requestInstitute?.email?.takeIf { it.isNotBlank() }?.let { email ->
                                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Email, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(email, color = AccentCyan, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text("Requested ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(req.requestSentAt))}", color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                var rejectNote by remember { mutableStateOf("") }
                                var showRejectDialog by remember { mutableStateOf(false) }
                                OutlinedButton(
                                    onClick = { showRejectDialog = true },
                                    shape = RoundedCornerShape(8.dp),
                                    border = ButtonDefaults.outlinedButtonBorder,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                                ) { Text("Reject", fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                                Button(onClick = { viewModel.approveRequest(req) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                                ) {
                                    Icon(Icons.Filled.ReceiptLong, null, tint = Color.Black, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Approve & Receipt", fontSize = 12.sp, color = Color.Black)
                                }
                                if (showRejectDialog) {
                                    AlertDialog(
                                        onDismissRequest = { showRejectDialog = false },
                                        title = { Text("Reject ${req.instituteName}?", color = TextWhite) },
                                        text = {
                                            OutlinedTextField(
                                                value = rejectNote, onValueChange = { rejectNote = it },
                                                placeholder = { Text("Reason (optional)", color = TextMuted) },
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite)
                                            )
                                        },
                                        confirmButton = {
                                            Button(onClick = { viewModel.rejectRequest(req, rejectNote.ifBlank { null }); showRejectDialog = false }) {
                                                Text("Reject")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showRejectDialog = false }) { Text("Cancel") }
                                        },
                                        containerColor = CardBg
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Institute list ──
            item {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("All Institutes · ${filteredInstitutes.size}", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotBlank() || statusFilter != "all" || lastActivityFilter != "all") {
                            TextButton(onClick = { searchQuery = ""; statusFilter = "all"; lastActivityFilter = "all" }) {
                                Text("Clear", color = AccentCyan, fontSize = 11.sp)
                            }
                        }
                        Box {
                            IconButton(onClick = { lastActivityMenuOpen = true }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Filled.AccessTime,
                                    "Filter institutes by activity",
                                    tint = if (lastActivityFilter == "all") TextMuted else AccentCyan,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = lastActivityMenuOpen,
                                onDismissRequest = { lastActivityMenuOpen = false },
                                containerColor = CardBg
                            ) {
                                listOf(
                                    "all" to "All activity",
                                    "today" to "Active in last 24 hours",
                                    "7days" to "Active in last 7 days",
                                    "30days" to "Active in last 30 days",
                                    "inactive30" to "Inactive for 30+ days",
                                    "never" to "Never active"
                                ).forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                label,
                                                color = if (value == lastActivityFilter) AccentCyan else TextWhite,
                                                fontSize = 12.sp
                                            )
                                        },
                                        onClick = {
                                            lastActivityFilter = value
                                            lastActivityMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Search bar ──
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by name, code, owner, phone, email...", color = TextMuted.copy(alpha = 0.5f), fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextMuted, modifier = Modifier.size(20.dp)) },
                    trailingIcon = if (searchQuery.isNotBlank()) {
                        { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Filled.Close, null, tint = TextMuted, modifier = Modifier.size(18.dp)) } }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
                        focusedBorderColor = AccentCyan, unfocusedBorderColor = BorderSub,
                        focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                        cursorColor = AccentCyan
                    )
                )
            }

            // ── Filter chips ──
            // Activity filter is placed on the dashboard above; keep the list controls focused on status.
            if (false) {
            item {
                val activityOptions = listOf(
                    "all" to "All activity",
                    "today" to "Active in last 24 hours",
                    "7days" to "Active in last 7 days",
                    "30days" to "Active in last 30 days",
                    "inactive30" to "Inactive for 30+ days",
                    "never" to "Never active"
                )
                val selectedActivityLabel = activityOptions.firstOrNull { it.first == lastActivityFilter }?.second ?: "All activity"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, BorderSub)
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(AccentCyan.copy(alpha = 0.13f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.AccessTime, null, tint = AccentCyan, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Last Activity", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Filter institute list by recent activity", color = TextMuted, fontSize = 10.sp)
                        }
                        Box {
                            OutlinedButton(
                                onClick = { lastActivityMenuOpen = true },
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                                modifier = Modifier.height(34.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (lastActivityFilter == "all") TextMuted else AccentCyan)
                            ) {
                                Text(if (lastActivityFilter == "all") "Filter" else "Active", fontSize = 11.sp)
                                Spacer(Modifier.width(3.dp))
                                Icon(Icons.Filled.ExpandMore, null, modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(
                                expanded = lastActivityMenuOpen,
                                onDismissRequest = { lastActivityMenuOpen = false },
                                containerColor = CardBg
                            ) {
                                activityOptions.forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, color = if (value == lastActivityFilter) AccentCyan else TextWhite, fontSize = 12.sp) },
                                        onClick = { lastActivityFilter = value; lastActivityMenuOpen = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val filters = listOf(
                        "all" to "All",
                        "trial" to "Trial",
                        "active" to "Active",
                        "expired" to "Expired",
                        "blocked" to "Blocked"
                    )
                    filters.forEach { (key, label) ->
                        val selected = statusFilter == key
                        val chipColor = when (key) {
                            "trial" -> AccentCyan; "active" -> AccentGreen; "expired" -> AccentRed; "blocked" -> AccentAmber; else -> TextMuted
                        }
                        FilterChip(
                            selected = selected,
                            onClick = { statusFilter = if (selected) "all" else key },
                            label = {
                                Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) chipColor else TextMuted)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = CardBg,
                                selectedContainerColor = chipColor.copy(alpha = 0.15f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = if (selected) chipColor.copy(alpha = 0.5f) else BorderSub,
                                selectedBorderColor = chipColor.copy(alpha = 0.5f),
                                enabled = true, selected = selected
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }

            // Advanced filtering stays available in the code path, but is intentionally
            // not part of the everyday dashboard flow.
            if (false) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("all" to "Any expiry", "7days" to "≤ 7 days", "30days" to "≤ 30 days", "expired" to "Expired").forEach { (key, label) ->
                        FilterChip(selected = expiryFilter == key, onClick = { expiryFilter = key }, label = { Text(label, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(containerColor = CardBg, selectedContainerColor = AccentCyan.copy(alpha = 0.14f)),
                            border = FilterChipDefaults.filterChipBorder(borderColor = BorderSub, selectedBorderColor = AccentCyan.copy(alpha = 0.45f), enabled = true, selected = expiryFilter == key),
                            shape = RoundedCornerShape(8.dp))
                    }
                }
            }

            item {
                val activityOptions = listOf(
                    "all" to "All activity",
                    "today" to "Active in last 24 hours",
                    "7days" to "Active in last 7 days",
                    "30days" to "Active in last 30 days",
                    "inactive30" to "Inactive for 30+ days",
                    "never" to "Never active"
                )
                val selectedActivityLabel = activityOptions.firstOrNull { it.first == lastActivityFilter }?.second ?: "All activity"
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { lastActivityMenuOpen = true },
                        modifier = Modifier.fillMaxWidth().height(38.dp),
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (lastActivityFilter == "all") TextMuted else AccentCyan)
                    ) {
                        Icon(Icons.Filled.AccessTime, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Last activity: $selectedActivityLabel", fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ExpandMore, null, modifier = Modifier.size(17.dp))
                    }
                    DropdownMenu(
                        expanded = lastActivityMenuOpen,
                        onDismissRequest = { lastActivityMenuOpen = false },
                        containerColor = CardBg
                    ) {
                        activityOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = if (value == lastActivityFilter) AccentCyan else TextWhite, fontSize = 12.sp) },
                                onClick = { lastActivityFilter = value; lastActivityMenuOpen = false }
                            )
                        }
                    }
                }
            }
            item {
                val planOptions = listOf("all") + subscriptionPlans.map { it.id }
                OutlinedButton(
                    onClick = {
                        val current = planOptions.indexOf(planFilter).coerceAtLeast(0)
                        planFilter = planOptions[(current + 1) % planOptions.size]
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                ) {
                    Icon(Icons.Filled.Tune, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Plan: ${if (planFilter == "all") "All plans" else planDisplayName(planFilter, subscriptionPlans)}", fontSize = 12.sp)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("newest" to "Newest", "name" to "A–Z", "expiry" to "Expiry").forEach { (key, label) ->
                            FilterChip(selected = directorySort == key, onClick = { directorySort = key }, label = { Text(label, fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(containerColor = CardBg, selectedContainerColor = AccentCyan.copy(alpha = 0.14f)), shape = RoundedCornerShape(8.dp))
                        }
                    }
                    Row {
                        IconButton(onClick = { directoryLayout = "grid" }) { Icon(Icons.Filled.GridView, "Grid", tint = if (directoryLayout == "grid") AccentCyan else TextMuted) }
                        IconButton(onClick = { directoryLayout = "table" }) { Icon(Icons.Filled.ViewList, "Table", tint = if (directoryLayout == "table") AccentCyan else TextMuted) }
                    }
                }
            }
            }

            if (filteredInstitutes.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Text(if (searchQuery.isNotBlank() || statusFilter != "all" || lastActivityFilter != "all") "No institutes match your filters." else "No institutes registered yet.",
                            color = TextMuted, fontSize = 14.sp)
                    }
                }
            } else {
                items(filteredInstitutes, key = { it.entity.id }) { card ->
                    InstituteCard(card, viewModel, subscriptionPlans)
                }
            }

            if (hasMoreInstitutes) {
                item {
                    OutlinedButton(
                        onClick = viewModel::loadMoreInstitutes,
                        enabled = !isLoadingMoreInstitutes,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                    ) {
                        if (isLoadingMoreInstitutes) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = AccentCyan
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isLoadingMoreInstitutes) "Loading institutes…" else "Load more institutes")
                    }
                }
            }


            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    permanentDeleteTarget?.let { card ->
        val institute = card.entity
        AlertDialog(
            onDismissRequest = { permanentDeleteTarget = null },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp),
            icon = { Icon(Icons.Filled.WarningAmber, null, tint = AccentRed) },
            title = {
                Text("Delete permanently?", color = TextWhite, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(institute.name, color = TextWhite, fontWeight = FontWeight.SemiBold)
                    Text(
                        "This removes the institute, students, staff, fees, receipts, media and login accounts from the app and cloud. It cannot be restored.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.permanentlyDeleteInstitute(institute.id)
                        permanentDeleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Icon(Icons.Filled.DeleteForever, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { permanentDeleteTarget = null }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }

    if (showPlanDialog) {
        SubscriptionPlanEditorDialog(
            initialPlan = editingPlan,
            onDismiss = { showPlanDialog = false },
            onSave = { existingId, plan ->
                viewModel.saveSubscriptionPlan(existingId, plan)
                showPlanDialog = false
            }
        )
    }

    /* Legacy duplicate ViewModel methods are intentionally disabled.
    fun refreshPlatformDashboard() {
        viewModelScope.launch {
            try {
                val metrics = PlatformAdminRepository().dashboard()
                hasServerDashboard = true
                _stats.value = SuperAdminStats(
                    totalInstitutes = metrics.totalInstitutes,
                    activeSubscriptions = metrics.activeInstitutes,
                    lifetimeRevenue = metrics.lifetimeRevenue,
                    thisMonthRevenue = metrics.thisMonthRevenue,
                    totalStudents = metrics.totalStudents,
                    totalStaff = metrics.totalStaff,
                    expiringIn7Days = metrics.expiringIn7Days,
                    expiringIn30Days = metrics.expiringIn30Days,
                    canonicalReceiptCount = metrics.canonicalReceiptCount,
                    snapshotAtMs = metrics.snapshotAtMs
                )
            } catch (error: Exception) {
                FirebaseCrashlytics.getInstance().recordException(error)
                _operationMsg.value = "Live platform metrics unavailable: ${error.message}"
            }
        }
    }

    fun transferOwner(instituteId: String, ownerName: String, ownerEmail: String, reason: String) {
        if (ownerName.isBlank() || ownerEmail.isBlank() || reason.trim().length < 3) {
            _operationMsg.value = "Owner name, email, and transfer reason are required."
            return
        }
        viewModelScope.launch {
            try {
                val result = PlatformAdminRepository().transferOwner(instituteId, ownerName, ownerEmail, reason)
                _lastRecoveryLink.value = result.recoveryLink.takeIf { it.isNotBlank() }
                _operationMsg.value = "Owner access transferred and audited. The previous owner is now an institute admin."
            } catch (error: Exception) {
                FirebaseCrashlytics.getInstance().recordException(error)
                _operationMsg.value = "Owner transfer failed: ${error.message}"
            }
        }
    }

    fun createInstitute(draft: PlatformInstituteDraft) {
        viewModelScope.launch {
            try {
                val result = PlatformAdminRepository().createInstitute(draft)
                _lastRecoveryLink.value = result.recoveryLink.takeIf { it.isNotBlank() }
                _operationMsg.value = "${result.instituteName} was provisioned securely."
                loadInstitutesRealtime()
                refreshPlatformDashboard()
            } catch (error: Exception) {
                FirebaseCrashlytics.getInstance().recordException(error)
                _operationMsg.value = "Institute creation failed: ${error.message}"
            }
        }
    }

    fun previewInstituteImport(rows: List<PlatformInstituteDraft>, onPreview: (List<com.batchfee.edu.data.repository.ImportPreviewRow>) -> Unit) {
        viewModelScope.launch {
            try {
                onPreview(PlatformAdminRepository().previewInstituteImport(rows))
            } catch (error: Exception) {
                FirebaseCrashlytics.getInstance().recordException(error)
                _operationMsg.value = "CSV preview failed: ${error.message}"
                onPreview(emptyList())
            }
        }
    }

    fun importInstitutes(
        rows: List<PlatformInstituteDraft>,
        validRows: Set<Int>,
        batchId: String
    ) {
        if (validRows.isEmpty()) {
            _operationMsg.value = "No valid rows are ready to import."
            return
        }
        viewModelScope.launch {
            val succeeded = _bulkImportReport.value.successfulRows.toMutableSet()
            val failures = _bulkImportReport.value.failedRows.toMutableMap()
            _bulkImportReport.value = BulkImportReport(batchId, succeeded, failures, running = true)
            validRows.sorted().forEach { index ->
                if (index in succeeded) return@forEach
                try {
                    // A stable operation id makes retry after an interrupted import idempotent.
                    PlatformAdminRepository().createInstitute(rows[index], "${batchId}_row_${index.toString().padStart(4, '0')}")
                    succeeded += index
                    failures.remove(index)
                } catch (error: Exception) {
                    failures[index] = error.message ?: "Server rejected this row."
                }
                _bulkImportReport.value = BulkImportReport(batchId, succeeded.toSet(), failures.toMap(), running = true)
            }
            _bulkImportReport.value = BulkImportReport(batchId, succeeded.toSet(), failures.toMap(), running = false)
            _operationMsg.value = "Bulk import complete: ${succeeded.size} created, ${failures.size} failed."
            loadInstitutesRealtime()
            refreshPlatformDashboard()
        }
    }

    fun provisionPlatformRole(name: String, email: String, role: String) {
        viewModelScope.launch {
            try {
                val result = PlatformAdminRepository().provisionPlatformAdmin(name, email, role)
                _lastRecoveryLink.value = result.recoveryLink.takeIf { it.isNotBlank() }
                _operationMsg.value = "${role.replace('_', ' ')} access provisioned and audited."
            } catch (error: Exception) {
                FirebaseCrashlytics.getInstance().recordException(error)
                _operationMsg.value = "Platform role update failed: ${error.message}"
            }
        }
    }

    private fun loadPlatformAudit() {
        firestore.collection("platform_audit").addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) {
                error?.let(FirebaseCrashlytics.getInstance()::recordException)
                return@addSnapshotListener
            }
            _platformAudit.value = snapshot.documents.mapNotNull { document ->
                val data = document.data ?: return@mapNotNull null
                val details = data["details"] as? Map<*, *>
                PlatformAuditEntry(
                    id = document.id,
                    action = data["action"] as? String ?: "platform_operation",
                    actorUid = data["actorUid"] as? String ?: "",
                    instituteId = data["instituteId"] as? String ?: "",
                    createdAtMs = (data["createdAtMs"] as? Number)?.toLong() ?: 0L,
                    summary = details?.entries?.joinToString(" · ") { "${it.key}: ${it.value}" }.orEmpty()
                )
            }.sortedByDescending { it.createdAtMs }.take(100)
        }
    }

    fun loadInstituteAudit(instituteId: String, onResult: (List<PlatformAuditEntry>) -> Unit) {
        viewModelScope.launch {
            try {
                val snapshot = firestore.collection("institutes").document(instituteId)
                    .collection("subscription_audit").get().await()
                onResult(snapshot.documents.mapNotNull { document ->
                    val data = document.data ?: return@mapNotNull null
                    PlatformAuditEntry(
                        id = document.id,
                        action = data["action"] as? String ?: "subscription_operation",
                        actorUid = data["actorUid"] as? String ?: "",
                        instituteId = instituteId,
                        createdAtMs = (data["createdAtMs"] as? Number)?.toLong() ?: 0L,
                        summary = (data["details"] as? Map<*, *>)?.entries?.joinToString(" · ") { "${it.key}: ${it.value}" }.orEmpty()
                    )
                }.sortedByDescending { it.createdAtMs })
            } catch (error: Exception) {
                FirebaseCrashlytics.getInstance().recordException(error)
                onResult(emptyList())
            }
        }
    }

    // ── Receipt Dialog ──
    */
    selectedInstitute?.let { card ->
        InstituteDetailsTabsDialog(
            card = card,
            plans = subscriptionPlans,
            viewModel = viewModel,
            onDismiss = { selectedInstitute = null }
        )
    }
    if (showCreateInstitute) {
        CreateInstituteWizard(
            plans = subscriptionPlans,
            onDismiss = { showCreateInstitute = false },
            onCreate = { draft -> viewModel.createInstitute(draft); showCreateInstitute = false }
        )
    }
    if (showCsvImport) {
        CsvInstituteImportDialog(
            report = importReport,
            onDismiss = { showCsvImport = false },
            onPreview = viewModel::previewInstituteImport,
            onImport = viewModel::importInstitutes
        )
    }
    if (showPlatformRoles) {
        PlatformRolesDialog(
            managedUsers = managedUsers,
            onDismiss = { showPlatformRoles = false },
            onProvision = viewModel::provisionPlatformRole
        )
    }
    if (showAuditHistory) {
        PlatformAuditDialog(platformAudit, onDismiss = { showAuditHistory = false })
    }
    recoveryLink?.let { link ->
        OneTimeRecoveryLinkDialog(link, onDismiss = viewModel::clearRecoveryLink)
    }
    if (showReceiptDialog) {
        val context = LocalContext.current
        val data = receiptData
        AlertDialog(
            onDismissRequest = { showReceiptDialog = false; viewModel.clearReceipt() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ReceiptLong, null, tint = AccentGreen, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Money Receipt Ready", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    data?.let { r ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.3f))
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(r.instituteName, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text("Receipt #${r.receiptNumber}", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text("${r.planName} · ${r.durationMonths} Month(s)", color = AccentCyan, fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Text("BDT ${"%,.0f".format(r.amountPaid)}", color = AccentGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (r.transactionLast4.isNotBlank()) {
                                        "${r.paymentMethod.uppercase()} · Trx: ***${r.transactionLast4}"
                                    } else {
                                        r.paymentMethod.uppercase()
                                    },
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                                Text("${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(r.startDateMs))} — ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(r.endDateMs))}", color = TextMuted, fontSize = 11.sp)
                            }
                        }
                    }
                    Text("Send this receipt to the institute owner via WhatsApp or print it.", color = TextMuted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        data?.let { r ->
                            if (!shareSubscriptionReceiptToWhatsApp(context, r)) {
                                Toast.makeText(context, "WhatsApp is not installed. Use Print instead.", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showReceiptDialog = false
                        viewModel.clearReceipt()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                ) {
                    Icon(Icons.Filled.Chat, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send to WhatsApp", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        data?.let { r ->
                            if (!openSubscriptionReceiptPdf(context, r)) {
                                Toast.makeText(context, "Unable to open the receipt PDF.", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showReceiptDialog = false
                        viewModel.clearReceipt()
                    }) {
                        Icon(Icons.Filled.Print, null, tint = ElectricBlue, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Print", color = ElectricBlue, fontSize = 13.sp)
                    }
                    TextButton(onClick = {
                        showReceiptDialog = false
                        viewModel.clearReceipt()
                    }) {
                        Text("Close", color = TextMuted, fontSize = 13.sp)
                    }
                }
            },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun SubscriptionRequestSection(
    requests: List<SubscriptionRequest>,
    institutes: List<InstituteCardData>,
    plans: List<SubscriptionPlanEntity>,
    approvingRequestIds: Set<String>,
    onApprove: (SubscriptionRequest) -> Unit,
    onReject: (SubscriptionRequest, String?) -> Unit
) {
    Column {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                    .background(AccentAmber.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.ReceiptLong, null, tint = AccentAmber, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Subscription Review", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Approve requests and issue payment receipts", color = TextMuted, fontSize = 10.sp)
            }
            Surface(shape = RoundedCornerShape(7.dp), color = AccentAmber.copy(alpha = 0.14f)) {
                Text("${requests.size} pending", color = AccentAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(7.dp))

        if (requests.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text("No subscription requests pending", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("New payment requests will appear here for review.", color = TextMuted, fontSize = 10.sp)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                requests.forEach { request ->
                    val institute = institutes.firstOrNull { it.entity.id == request.instituteId }?.entity
                    PendingSubscriptionRequestCard(
                        request = request,
                        planName = planDisplayName(request.requestedPlanId, plans),
                        instituteCode = institute?.instituteCode,
                        ownerEmail = institute?.email,
                        isApproving = request.requestId in approvingRequestIds,
                        onApprove = { onApprove(request) },
                        onReject = { note -> onReject(request, note) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingSubscriptionRequestCard(
    request: SubscriptionRequest,
    planName: String,
    instituteCode: String?,
    ownerEmail: String?,
    isApproving: Boolean,
    onApprove: () -> Unit,
    onReject: (String?) -> Unit
) {
    val context = LocalContext.current
    var showApproveConfirmation by remember { mutableStateOf(false) }
    var showRejectDialog by remember { mutableStateOf(false) }
    var rejectNote by remember { mutableStateOf("") }
    var rejectTemplateMenuOpen by remember { mutableStateOf(false) }
    val rejectionTemplates = remember {
        listOf(
            "Payment reference could not be verified. Please submit again with a valid transaction ID.",
            "Selected plan or payment amount does not match. Please review and submit again.",
            "This transaction reference has already been used. Please verify your payment details.",
            "We could not verify this payment. Please contact BatchFee support with a payment screenshot."
        )
    }
    val ownerPhone = request.institutePhone.orEmpty()
    val requestDate = remember(request.requestSentAt) {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(request.requestSentAt))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AccentAmber.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Business, null, tint = AccentAmber, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(request.instituteName, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Submitted $requestDate", color = TextMuted, fontSize = 10.sp)
                }
                Surface(shape = RoundedCornerShape(7.dp), color = AccentAmber.copy(alpha = 0.14f)) {
                    Text("PENDING", color = AccentAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = BorderSub.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WorkspacePremium, null, tint = AccentViolet, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(planName, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("BDT ${"%,.0f".format(request.amountPaid)}", color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "${request.durationMonths} month(s) · ${if (request.studentLimitAtRequest > 0) "Up to ${request.studentLimitAtRequest} students" else "Plan capacity"}",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        if (request.senderPhone.isNotBlank()) {
                            "${request.paymentMethod.uppercase()} · Sent from ${request.senderPhone}"
                        } else {
                            "${request.paymentMethod.uppercase()} · Ref: ••••${request.transactionLast4}"
                        },
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Person, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(request.ownerName.ifBlank { "Owner not provided" }, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                instituteCode?.takeIf { it.isNotBlank() }?.let { Text(it, color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.Medium) }
            }
            if (ownerPhone.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Phone, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(ownerPhone, color = AccentGreen, fontSize = 11.sp)
                }
            }
            ownerEmail?.takeIf { it.isNotBlank() }?.let { email ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Email, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(email, color = AccentCyan, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            OutlinedButton(
                onClick = {
                    val phone = ownerPhone.filter(Char::isDigit)
                    try {
                        val url = Uri.parse("https://wa.me/$phone?text=${Uri.encode("Hello ${request.ownerName}, regarding your BatchFee subscription request.")}")
                        val whatsapp = Intent(Intent.ACTION_VIEW, url).setPackage("com.whatsapp")
                        context.startActivity(if (whatsapp.resolveActivity(context.packageManager) != null) whatsapp else Intent(Intent.ACTION_VIEW, url))
                    } catch (_: Exception) {
                        Toast.makeText(context, "Unable to contact the institute owner.", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = ownerPhone.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(38.dp),
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
            ) {
                Icon(Icons.Filled.Chat, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (ownerPhone.isBlank()) "Owner contact unavailable" else "Contact institute owner", fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showRejectDialog = true },
                    enabled = !isApproving,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                ) {
                    Icon(Icons.Filled.Close, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reject", fontSize = 11.sp)
                }
                Button(
                    onClick = { showApproveConfirmation = true },
                    enabled = !isApproving,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    if (isApproving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = Color.Black
                        )
                    } else {
                        Icon(Icons.Filled.ReceiptLong, null, tint = Color.Black, modifier = Modifier.size(15.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(if (isApproving) "Approving..." else "Approve", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("Approval issues a receipt and adds it to this institute's Payment History.", color = TextMuted, fontSize = 10.sp)
        }
    }

    if (showApproveConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!isApproving) showApproveConfirmation = false },
            title = { Text("Approve subscription payment?", color = TextWhite, fontWeight = FontWeight.Bold) },
            text = { Text("A receipt for BDT ${"%,.0f".format(request.amountPaid)} will be issued to ${request.instituteName} and saved in its Payment History.", color = TextMuted, fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = { showApproveConfirmation = false; onApprove() },
                    enabled = !isApproving,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text(if (isApproving) "Approving..." else "Approve & issue receipt", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showApproveConfirmation = false }, enabled = !isApproving) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }
    if (showRejectDialog) {
        AlertDialog(
            onDismissRequest = { showRejectDialog = false },
            title = { Text("Reject ${request.instituteName}?", color = TextWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Quick template", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Box {
                            OutlinedButton(
                                onClick = { rejectTemplateMenuOpen = true },
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                                modifier = Modifier.height(34.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                            ) {
                                Text("Choose", fontSize = 11.sp)
                                Spacer(Modifier.width(3.dp))
                                Icon(Icons.Filled.ExpandMore, null, modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(
                                expanded = rejectTemplateMenuOpen,
                                onDismissRequest = { rejectTemplateMenuOpen = false },
                                containerColor = CardBg
                            ) {
                                rejectionTemplates.forEach { template ->
                                    DropdownMenuItem(
                                        text = { Text(template, color = TextWhite, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                                        onClick = { rejectNote = template; rejectTemplateMenuOpen = false }
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = rejectNote,
                        onValueChange = { if (it.length <= 300) rejectNote = it },
                        label = { Text("Reason for rejection") },
                        placeholder = { Text("Choose a template or write your own message") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        supportingText = { Text("This message will be visible to the institute owner.", fontSize = 10.sp) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, cursorColor = AccentRed)
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showRejectDialog = false; onReject(rejectNote.ifBlank { null }) }, colors = ButtonDefaults.buttonColors(containerColor = AccentRed)) {
                    Text("Reject request", color = Color.White)
                }
            },
            dismissButton = { TextButton(onClick = { showRejectDialog = false }) { Text("Cancel", color = TextMuted) } },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun InstituteGridCard(
    card: InstituteCardData,
    plans: List<SubscriptionPlanEntity>,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
    val inst = card.entity
    val statusColor = when (inst.subscriptionStatus) {
        "active" -> AccentGreen; "trial" -> AccentCyan; "expired", "blocked" -> AccentRed; else -> TextMuted
    }
    Card(modifier.clickable(onClick = onOpen), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg), border = BorderStroke(1.dp, BorderSub)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(AccentCyan.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
                    Text(inst.name.take(1).uppercase(), color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(inst.name, color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(inst.instituteCode ?: "No code", color = TextMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(planDisplayName(inst.currentPlanId, plans), color = AccentCyan, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(inst.subscriptionStatus.replaceFirstChar { it.uppercase() }, color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(Date(effectiveSubscriptionExpiryMs(inst))), color = TextMuted, fontSize = 9.sp)
            }
            Text("${card.studentCount} students · ${card.staffCount} staff", color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun InstituteTableHeader() {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(BorderSub.copy(alpha = .55f)).padding(horizontal = 11.dp, vertical = 8.dp)) {
        Text("INSTITUTE", Modifier.weight(1.7f), color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text("PLAN", Modifier.weight(1f), color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text("EXPIRY", Modifier.weight(.8f), color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InstituteTableRow(card: InstituteCardData, plans: List<SubscriptionPlanEntity>, onOpen: () -> Unit) {
    val inst = card.entity
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(9.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.7f)) {
                Text(inst.name, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(inst.ownerName ?: inst.email.orEmpty(), color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(planDisplayName(inst.currentPlanId, plans), Modifier.weight(1f), color = AccentCyan, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Column(Modifier.weight(.8f)) {
                Text(SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(Date(effectiveSubscriptionExpiryMs(inst))), color = TextMuted, fontSize = 10.sp)
                Text(inst.subscriptionStatus, color = if (inst.subscriptionStatus == "active") AccentGreen else AccentCyan, fontSize = 9.sp)
            }
        }

        /* Archived-announcement UI is rendered in BroadcastSection below.
        if (archivedAnnouncements.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Archived announcements · ${archivedAnnouncements.size}", color = AccentAmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { showArchived = !showArchived }) {
                    Text(if (showArchived) "Hide" else "View", color = AccentAmber, fontSize = 11.sp)
                }
            }
            if (showArchived) {
                archivedAnnouncements.forEach { archived ->
                    Card(
                        Modifier.fillMaxWidth().clickable { selectedArchived = archived }.padding(bottom = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.3f))
                    ) {
                        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(AccentAmber.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Archive, null, tint = AccentAmber, modifier = Modifier.size(15.dp))
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(archived.message, color = TextWhite, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("Archived · tap to view or restore", color = TextMuted, fontSize = 10.sp)
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        selectedArchived?.let { archived ->
            AlertDialog(
                onDismissRequest = { selectedArchived = null },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Archive, null, tint = AccentAmber, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Archived announcement", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = RoundedCornerShape(10.dp), color = BorderSub.copy(alpha = 0.45f)) {
                            Text(archived.message, color = TextWhite, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(12.dp))
                        }
                        Text("Restore makes this message visible to institutes again.", color = TextMuted, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    Button(onClick = { onRestore(archived); selectedArchived = null }, colors = ButtonDefaults.buttonColors(containerColor = AccentGreen), shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Filled.Restore, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Restore", color = Color.Black)
                    }
                },
                dismissButton = { TextButton(onClick = { selectedArchived = null }) { Text("Close", color = TextMuted) } },
                containerColor = CardBg,
                shape = RoundedCornerShape(16.dp)
            )
        }
        */
    }
}

@Composable
private fun InstituteDetailsTabsDialog(
    card: InstituteCardData,
    plans: List<SubscriptionPlanEntity>,
    viewModel: SuperAdminViewModel,
    initialTab: String = "Overview",
    onDismiss: () -> Unit
) {
    val inst = card.entity
    val tabs = listOf("Overview", "Logins", "Subscription", "Usage", "Team", "Payments", "Support", "Audit")
    var selectedTab by remember(inst.id, initialTab) {
        mutableIntStateOf(tabs.indexOf(initialTab).coerceAtLeast(0))
    }
    var team by remember { mutableStateOf<List<InstituteStaffSummary>?>(null) }
    var receipts by remember { mutableStateOf<List<SubscriptionReceiptData>?>(null) }
    var audit by remember { mutableStateOf<List<PlatformAuditEntry>?>(null) }
    var loginActivity by remember(inst.id) { mutableStateOf<InstituteOwnerLoginActivity?>(null) }
    var loginActivityLoading by remember(inst.id) { mutableStateOf(false) }
    var loginActivityError by remember(inst.id) { mutableStateOf<String?>(null) }
    var accessReason by remember { mutableStateOf("") }
    var accessDays by remember { mutableStateOf("7") }
    var showOwnerAccess by remember { mutableStateOf(false) }
    LaunchedEffect(inst.id, selectedTab) {
        when (tabs[selectedTab]) {
            "Logins" -> {
                loginActivityLoading = true
                loginActivityError = null
                viewModel.loadInstituteOwnerLoginActivity(inst.id) { activity, error ->
                    loginActivity = activity
                    loginActivityError = error
                    loginActivityLoading = false
                }
            }
            "Team" -> viewModel.loadInstituteStaff(inst.id) { team = it }
            "Payments" -> viewModel.loadInstituteReceipts(inst.id) { receipts = it }
            "Audit" -> viewModel.loadInstituteAudit(inst.id) { audit = it }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Column { Text(inst.name, color = TextWhite, fontWeight = FontWeight.Bold); Text(inst.ownerName ?: inst.email.orEmpty(), color = TextMuted, fontSize = 11.sp) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = CardBg,
                    contentColor = AccentCyan,
                    edgePadding = 0.dp,
                    divider = {}
                ) {
                    tabs.forEachIndexed { index, name -> Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(name, fontSize = 9.sp, maxLines = 1) }) }
                }
                when (tabs[selectedTab]) {
                    "Overview" -> DetailRows(listOf(
                        "Institute code" to (inst.instituteCode ?: "Not set"), "Phone" to (inst.phone ?: "Not set"),
                        "Email" to (inst.email ?: "Not set"), "Status" to inst.subscriptionStatus, "Created" to SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(inst.createdAtMs))
                    ))
                    "Logins" -> when {
                        loginActivityLoading -> LoadingDetail()
                        loginActivityError != null -> EmptyDetail(loginActivityError!!)
                        loginActivity == null -> EmptyDetail("No owner login activity is available yet")
                        else -> InstituteOwnerLoginActivityPanel(loginActivity!!)
                    }
                    "Subscription" -> {
                        DetailRows(listOf("Plan" to planDisplayName(inst.currentPlanId, plans), "Paid / trial expiry" to SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(effectiveSubscriptionExpiryMs(inst)))))
                        OutlinedTextField(accessDays, { if (it.all(Char::isDigit) && it.length <= 4) accessDays = it }, label = { Text("Grant access days") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(accessReason, { if (it.length <= 500) accessReason = it }, label = { Text("Reason (required for audit)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { accessDays.toIntOrNull()?.takeIf { it > 0 }?.let { viewModel.extendSubscription(inst.id, it, accessReason) } }, enabled = accessReason.trim().length >= 3, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) { Text("Grant access", color = BgColor) }
                    }
                    "Usage" -> DetailRows(listOf("Students" to card.studentCount.toString(), "Staff" to card.staffCount.toString(), "Batches" to card.batchCount.toString(), "Student limit" to "Server-controlled plan limit"))
                    "Team" -> if (team == null) LoadingDetail() else if (team!!.isEmpty()) EmptyDetail("No active team members") else team!!.take(12).forEach { member -> Text("${member.fullName} · ${member.roleTitle} · ${member.status}", color = TextMuted, fontSize = 11.sp) }
                    "Payments" -> if (receipts == null) LoadingDetail() else if (receipts!!.isEmpty()) EmptyDetail("No canonical subscription receipts") else receipts!!.take(8).forEach { receipt -> Text("${receipt.receiptNumber} · BDT ${"%.0f".format(receipt.amountPaid)} · ${receipt.planName}", color = TextMuted, fontSize = 11.sp) }
                    "Support" -> {
                        Text("Owner transfer and recovery preserve the institute ID and are always audited.", color = TextMuted, fontSize = 11.sp)
                        OutlinedButton(onClick = { showOwnerAccess = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)) { Icon(Icons.Filled.Key, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text("Owner access / recovery") }
                    }
                    "Audit" -> if (audit == null) LoadingDetail() else if (audit!!.isEmpty()) EmptyDetail("No subscription audit records") else audit!!.take(12).forEach { entry -> Text("${entry.action.replace('_', ' ')} · ${SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(entry.createdAtMs))}\n${entry.summary}", color = TextMuted, fontSize = 10.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = AccentCyan) } },
        containerColor = CardBg, shape = RoundedCornerShape(16.dp)
    )
    if (showOwnerAccess) OwnerAccessDialog(inst, onDismiss = { showOwnerAccess = false }, onRecovery = viewModel::sendOwnerRecovery, onTransfer = viewModel::transferOwner)
}

@Composable private fun DetailRows(rows: List<Pair<String, String>>) = Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { rows.forEach { (label, value) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = TextMuted, fontSize = 11.sp); Text(value, color = TextWhite, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } } }
@Composable private fun LoadingDetail() = Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = AccentCyan, strokeWidth = 2.dp) }
@Composable private fun EmptyDetail(text: String) = Text(text, color = TextMuted, fontSize = 11.sp)

@Composable
private fun InstituteOwnerLoginActivityPanel(activity: InstituteOwnerLoginActivity) {
    val dateTimeFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OwnerLoginStat("Today", activity.todayCount.toString(), AccentGreen, Modifier.weight(1f))
            OwnerLoginStat("Last 30 days", activity.last30DaysCount.toString(), AccentCyan, Modifier.weight(1f))
            OwnerLoginStat("Lifetime", activity.totalLoginCount.toString(), AccentViolet, Modifier.weight(1f))
        }
        Text(
            if (activity.lastLoginAtMs > 0L) "Last login: ${dateTimeFormat.format(Date(activity.lastLoginAtMs))}"
            else "No owner login has been recorded yet.",
            color = TextMuted,
            fontSize = 10.sp
        )
        HorizontalDivider(color = BorderSub)
        Text("Owner login history - last ${activity.retentionDays} days", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (activity.events.isEmpty()) {
            EmptyDetail("New successful owner logins will appear here.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(activity.events, key = { it.id }) { event ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                            .background(BorderSub.copy(alpha = 0.34f)).padding(horizontal = 9.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(29.dp).clip(RoundedCornerShape(8.dp))
                                .background(AccentCyan.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (event.method == "biometric") Icons.Filled.Fingerprint else Icons.Filled.Login,
                                null,
                                tint = AccentCyan,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(dateTimeFormat.format(Date(event.occurredAtMs)), color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Text(if (event.method == "biometric") "Biometric login" else "Password login", color = TextMuted, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnerLoginStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(9.dp)).background(color.copy(alpha = 0.11f))
            .padding(horizontal = 7.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = TextMuted, fontSize = 8.sp, maxLines = 1)
    }
}

@Composable
private fun OwnerAccessDialog(inst: InstituteEntity, onDismiss: () -> Unit, onRecovery: (String, String) -> Unit, onTransfer: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf(inst.ownerName.orEmpty()) }; var email by remember { mutableStateOf(inst.email.orEmpty()) }; var reason by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Owner Access", color = TextWhite) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recovery generates a one-time reset link. Transfer changes the canonical owner without changing institute data.", color = TextMuted, fontSize = 11.sp)
        OutlinedTextField(name, { name = it }, label = { Text("New owner name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(email, { email = it }, label = { Text("New owner email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(reason, { if (it.length <= 500) reason = it }, label = { Text("Reason") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    } }, confirmButton = { Row { TextButton(onClick = { onRecovery(inst.id, reason) }, enabled = reason.trim().length >= 3) { Text("Recovery link", color = AccentCyan) }; Button(onClick = { onTransfer(inst.id, name, email, reason); onDismiss() }, enabled = name.isNotBlank() && email.isNotBlank() && reason.trim().length >= 3, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) { Text("Transfer", color = BgColor) } } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) } }, containerColor = CardBg)
}

@Composable
private fun CreateInstituteWizard(plans: List<SubscriptionPlanEntity>, onDismiss: () -> Unit, onCreate: (PlatformInstituteDraft) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var instituteName by remember { mutableStateOf("") }; var ownerName by remember { mutableStateOf("") }; var ownerEmail by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }; var address by remember { mutableStateOf("") }; var instituteCode by remember { mutableStateOf("") }
    var planId by remember { mutableStateOf("plan_free_trial") }
    val canContinue = instituteName.trim().isNotBlank() && ownerName.trim().isNotBlank() && ownerEmail.contains("@")
    AlertDialog(onDismissRequest = onDismiss, title = { Column { Text("Create Institute", color = TextWhite, fontWeight = FontWeight.Bold); Text("Step ${step + 1} of 2 · secure server provisioning", color = TextMuted, fontSize = 11.sp) } }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (step == 0) {
            OutlinedTextField(instituteName, { instituteName = it }, label = { Text("Institute name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(ownerName, { ownerName = it }, label = { Text("Owner name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(ownerEmail, { ownerEmail = it }, label = { Text("Owner email") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        } else {
            OutlinedTextField(instituteCode, { instituteCode = it.uppercase() }, label = { Text("Institute code (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(address, { address = it }, label = { Text("Address (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Text("Subscription plan", color = TextMuted, fontSize = 11.sp)
            plans.forEach { plan -> FilterChip(selected = planId == plan.id, onClick = { planId = plan.id }, label = { Text("${plan.name} · BDT ${formatMoneyValue(plan.priceBdt)}", fontSize = 11.sp) }, colors = FilterChipDefaults.filterChipColors(containerColor = CardBg, selectedContainerColor = AccentCyan.copy(alpha = .15f))) }
            Text("The owner receives a one-time reset link; no password is collected or stored here.", color = TextMuted, fontSize = 11.sp)
        }
    } }, confirmButton = { Button(onClick = { if (step == 0) step = 1 else onCreate(PlatformInstituteDraft(instituteName, ownerName, ownerEmail, phone, address, instituteCode, planId)) }, enabled = if (step == 0) canContinue else true, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) { Text(if (step == 0) "Continue" else "Create institute", color = BgColor) } }, dismissButton = { Row { if (step > 0) TextButton(onClick = { step = 0 }) { Text("Back", color = TextMuted) }; TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) } } }, containerColor = CardBg, shape = RoundedCornerShape(16.dp))
}

@Composable
private fun CsvInstituteImportDialog(
    report: BulkImportReport,
    onDismiss: () -> Unit,
    onPreview: (List<PlatformInstituteDraft>, (List<com.batchfee.edu.data.repository.ImportPreviewRow>) -> Unit) -> Unit,
    onImport: (List<PlatformInstituteDraft>, Set<Int>, String) -> Unit
) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<PlatformInstituteDraft>>(emptyList()) }
    var preview by remember { mutableStateOf<List<com.batchfee.edu.data.repository.ImportPreviewRow>>(emptyList()) }
    var parseError by remember { mutableStateOf<String?>(null) }
    val batchId = remember { UUID.randomUUID().toString().replace("-", "") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.length > 250_000) error("CSV is too large. Split it into files of up to 100 rows.")
            rows = parseInstituteCsv(text)
            preview = emptyList(); parseError = null
        } catch (error: Exception) { parseError = error.message ?: "Could not read CSV."; rows = emptyList(); preview = emptyList() }
    }
    val validRows = preview.filter { it.valid }.map { it.row - 1 }.toSet()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Bulk Institute Import", color = TextWhite) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Columns: instituteName, ownerName, ownerEmail, phone, address, instituteCode, planId. Preview runs server duplicate/plan checks before any write.", color = TextMuted, fontSize = 11.sp)
        OutlinedButton(onClick = { picker.launch("text/csv") }, colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)) { Icon(Icons.Filled.UploadFile, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Choose CSV") }
        if (rows.isNotEmpty()) Text("${rows.size} parsed row(s)", color = TextWhite, fontSize = 12.sp)
        parseError?.let { Text(it, color = AccentRed, fontSize = 11.sp) }
        if (preview.isNotEmpty()) {
            Text("Preview: ${validRows.size} valid · ${preview.size - validRows.size} need correction", color = AccentCyan, fontSize = 11.sp)
            preview.filterNot { it.valid }.take(6).forEach { Text("Row ${it.row}: ${it.issues.joinToString()}", color = AccentRed, fontSize = 10.sp) }
        }
        if (report.batchId == batchId) {
            Text("Created ${report.successfulRows.size}; failed ${report.failedRows.size}", color = if (report.failedRows.isEmpty()) AccentGreen else AccentRed, fontSize = 11.sp)
            report.failedRows.entries.take(6).forEach { Text("Row ${it.key + 1}: ${it.value}", color = AccentRed, fontSize = 10.sp) }
        }
    } }, confirmButton = { Row { if (preview.isEmpty()) Button(onClick = { if (rows.isNotEmpty()) onPreview(rows) { preview = it } }, enabled = rows.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) { Text("Validate preview", color = BgColor) } else Button(onClick = { onImport(rows, validRows, batchId) }, enabled = validRows.isNotEmpty() && !report.running, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) { Text(if (report.failedRows.isNotEmpty()) "Retry failed / import" else "Import valid rows", color = BgColor) } } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextMuted) } }, containerColor = CardBg, shape = RoundedCornerShape(16.dp))
}

@Composable
private fun PlatformRolesDialog(managedUsers: List<ManagedUserSummary>, onDismiss: () -> Unit, onProvision: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var email by remember { mutableStateOf("") }; var role by remember { mutableStateOf("billing") }
    val roles = listOf("root" to "Root", "billing" to "Billing", "support" to "Support", "operations" to "Operations", "read_only" to "Read-only")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Platform Roles", color = TextWhite) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Root retains all privileges. Billing, Support, Operations, and Read-only roles are least-privilege server roles; direct Firestore privilege writes are blocked.", color = TextMuted, fontSize = 11.sp)
        roles.forEach { (key, label) -> FilterChip(selected = role == key, onClick = { if (key != "root") role = key }, label = { Text(label, fontSize = 11.sp) }, enabled = key != "root", colors = FilterChipDefaults.filterChipColors(containerColor = CardBg, selectedContainerColor = AccentCyan.copy(alpha = .15f))) }
        Text("Existing platform accounts: ${managedUsers.count { it.role == "PlatformAdmin" || it.role == "SuperAdmin" }}", color = TextMuted, fontSize = 11.sp)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
    } }, confirmButton = { Button(onClick = { onProvision(name, email, role) }, enabled = name.isNotBlank() && email.contains("@") && role != "root", colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) { Text("Grant ${role.replace('_', ' ')}", color = BgColor) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextMuted) } }, containerColor = CardBg)
}

@Composable
private fun PlatformAuditDialog(entries: List<PlatformAuditEntry>, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Audit & Security History", color = TextWhite) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entries.isEmpty()) EmptyDetail("No platform audit entries yet.") else entries.take(25).forEach { entry ->
            Text("${entry.action.replace('_', ' ')} · ${SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(entry.createdAtMs))}", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text("${entry.summary.ifBlank { "Actor: ${entry.actorUid.take(10)}" }}", color = TextMuted, fontSize = 10.sp)
        }
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = AccentCyan) } }, containerColor = CardBg)
}

@Composable
private fun OneTimeRecoveryLinkDialog(link: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current; val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("One-time owner recovery link", color = TextWhite) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Copy or share this now. It is never persisted in Firestore or shown again after this dialog closes.", color = TextMuted, fontSize = 11.sp)
        Text(link, color = AccentCyan, fontSize = 10.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
    } }, confirmButton = { Row { TextButton(onClick = { clipboard.setText(AnnotatedString(link)) }) { Text("Copy", color = AccentCyan) }; Button(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }, "Share recovery link")) }, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) { Text("Share", color = BgColor) } } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Done", color = TextMuted) } }, containerColor = CardBg)
}

private fun parseInstituteCsv(text: String): List<PlatformInstituteDraft> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().filter { it.isNotBlank() }
    require(lines.size in 2..101) { "CSV must have a header and 1 to 100 data rows." }
    val header = parseCsvLine(lines.first()).map { it.trim().lowercase().replace("_", "") }
    fun field(row: List<String>, name: String) = row.getOrNull(header.indexOf(name))?.trim().orEmpty()
    require(listOf("institutename", "ownername", "owneremail").all { it in header }) { "CSV requires instituteName, ownerName, ownerEmail headers." }
    return lines.drop(1).map { line ->
        val row = parseCsvLine(line)
        PlatformInstituteDraft(field(row, "institutename"), field(row, "ownername"), field(row, "owneremail"), field(row, "phone"), field(row, "address"), field(row, "institutecode"), field(row, "planid").ifBlank { "plan_free_trial" })
    }
}

private fun parseCsvLine(line: String): List<String> {
    val values = mutableListOf<String>(); val value = StringBuilder(); var quoted = false; var index = 0
    while (index < line.length) { val char = line[index]; when { char == '"' && quoted && line.getOrNull(index + 1) == '"' -> { value.append(char); index += 1 }; char == '"' -> quoted = !quoted; char == ',' && !quoted -> { values += value.toString(); value.clear() }; else -> value.append(char) }; index += 1 }
    require(!quoted) { "CSV has an unmatched quote." }; values += value.toString(); return values
}

@Composable
private fun RecoveryVaultSection(
    institutes: List<InstituteCardData>,
    purgingInstituteIds: Set<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (InstituteCardData) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.3f))
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                        .background(AccentRed.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.DeleteSweep, null, tint = AccentRed, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Recovery Vault", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (expanded) "${institutes.size} institutes - tap to close"
                        else "${institutes.size} institutes - tap to manage",
                        color = TextMuted,
                        fontSize = 9.sp
                    )
                }
                Surface(shape = RoundedCornerShape(7.dp), color = AccentRed.copy(alpha = 0.13f)) {
                    Text(
                        institutes.size.toString(),
                        color = AccentRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse recovery vault" else "Expand recovery vault",
                    tint = AccentRed,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(130))
            ) {
                Column {
                    HorizontalDivider(color = BorderSub)
                    institutes.forEachIndexed { index, card ->
                        val institute = card.entity
                        val isPurging = institute.id in purgingInstituteIds
                        if (index > 0) {
                            HorizontalDivider(
                                color = BorderSub.copy(alpha = 0.75f),
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(9.dp))
                                        .background(AccentRed.copy(alpha = 0.13f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Business, null, tint = AccentRed, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        institute.name,
                                        color = TextWhite,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "Archived · Review ${dateFormat.format(Date(institute.currentPeriodEndMs))}",
                                        color = TextMuted,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(7.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { onRestore(institute.id) },
                                    enabled = !isPurging,
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.65f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Icon(Icons.Filled.Restore, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Restore", fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = { onDelete(card) },
                                    enabled = !isPurging,
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.7f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    if (isPurging) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentRed)
                                    } else {
                                        Icon(Icons.Filled.DeleteForever, null, modifier = Modifier.size(14.dp))
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isPurging) "Deleting" else "Delete", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionPlanSection(
    plans: List<SubscriptionPlanEntity>,
    institutes: List<InstituteCardData>,
    onCreate: () -> Unit,
    onEdit: (SubscriptionPlanEntity) -> Unit,
    onDelete: (String) -> Unit
) {
    val planUsage = remember(institutes) { institutes.groupingBy { it.entity.currentPlanId }.eachCount() }
    var selectedPlan by remember { mutableStateOf<SubscriptionPlanEntity?>(null) }
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, AccentViolet.copy(alpha = 0.28f))
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 12.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                        .background(AccentViolet.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.WorkspacePremium, null, tint = AccentViolet, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Subscription Plans", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (expanded) "${plans.size} plans - tap a plan for details" else "${plans.size} plans - tap to manage",
                        color = TextMuted,
                        fontSize = 9.sp
                    )
                }
                TextButton(onClick = onCreate, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                    Icon(Icons.Filled.Add, null, tint = AccentCyan, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("New Plan", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    if (expanded) "Collapse subscription plans" else "Expand subscription plans",
                    tint = AccentViolet,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(130))
            ) {
                Column {
                    HorizontalDivider(color = BorderSub)
                    if (plans.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text("No subscription plans found yet.", color = TextMuted, fontSize = 12.sp)
                        }
                    } else {
                        plans.forEachIndexed { index, plan ->
                            val assignedCount = planUsage[plan.id] ?: 0
                            if (index > 0) HorizontalDivider(color = BorderSub.copy(alpha = 0.75f), modifier = Modifier.padding(horizontal = 12.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { selectedPlan = plan }
                                    .padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(32.dp).clip(RoundedCornerShape(9.dp))
                                            .background(AccentCyan.copy(alpha = 0.11f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(plan.name.take(1).uppercase(), color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(9.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(plan.name, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                            if (plan.tag.isNotBlank()) {
                                                Spacer(Modifier.width(5.dp))
                                                Surface(shape = RoundedCornerShape(6.dp), color = AccentViolet.copy(alpha = 0.14f)) {
                                                    Text(plan.tag, color = AccentViolet, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                                                }
                                            }
                                        }
                                        Text(plan.description, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        IconButton(onClick = { onEdit(plan) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Filled.Edit, "Edit ${plan.name}", tint = AccentCyan, modifier = Modifier.size(16.dp))
                                        }
                                        if (assignedCount > 0) {
                                            Text("$assignedCount active", color = AccentGreen, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PlanMetricChip("BDT ${formatMoneyValue(plan.priceBdt)}")
                                    PlanMetricChip(planStudentCapacityLabel(plan))
                                    PlanMetricChip(planUserCapacityLabel(plan))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selectedPlan?.let { plan ->
        SubscriptionPlanDetailsDialog(
            plan = plan,
            assignedInstituteCount = planUsage[plan.id] ?: 0,
            onDismiss = { selectedPlan = null },
            onEdit = { selectedPlan = null; onEdit(plan) },
            onDelete = { selectedPlan = null; onDelete(plan.id) }
        )
    }
}

@Composable
private fun SubscriptionPlanDetailsDialog(
    plan: SubscriptionPlanEntity,
    assignedInstituteCount: Int,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val canDelete = plan.id != DEFAULT_TRIAL_PLAN_ID && assignedInstituteCount == 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(AccentViolet.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.WorkspacePremium, null, tint = AccentViolet, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(plan.name, color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(plan.id, color = TextMuted, fontSize = 10.sp)
                }
                if (plan.tag.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(7.dp), color = AccentViolet.copy(alpha = 0.14f)) {
                        Text(plan.tag, color = AccentViolet, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(plan.description.ifBlank { "No description added for this plan." }, color = TextMuted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlanMetricChip("BDT ${formatMoneyValue(plan.priceBdt)}")
                    PlanMetricChip(planStudentCapacityLabel(plan))
                    PlanMetricChip(planBatchCapacityLabel(plan))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlanMetricChip(planUserCapacityLabel(plan))
                    if (assignedInstituteCount > 0) PlanMetricChip("$assignedInstituteCount institutes")
                }
                Text(
                    if (assignedInstituteCount > 0) "This plan is assigned to $assignedInstituteCount institute(s), so it cannot be deleted."
                    else if (plan.id == DEFAULT_TRIAL_PLAN_ID) "The default Free Trial plan is protected and cannot be deleted."
                    else "No institute is using this plan.",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
        },
        confirmButton = {
            Button(onClick = onEdit, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Edit", color = BgColor)
            }
        },
        dismissButton = {
            Row {
                if (canDelete) TextButton(onClick = onDelete) { Text("Delete", color = AccentRed) }
                TextButton(onClick = onDismiss) { Text("Close", color = TextMuted) }
            }
        },
        containerColor = CardBg,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun PlanMetricChip(label: String) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp))
            .background(AccentCyan.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            color = AccentCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SubscriptionPlanEditorDialog(
    initialPlan: SubscriptionPlanEntity?,
    onDismiss: () -> Unit,
    onSave: (existingPlanId: String?, plan: SubscriptionPlanEntity) -> Unit
) {
    var name by remember(initialPlan) { mutableStateOf(initialPlan?.name ?: "") }
    var planId by remember(initialPlan) { mutableStateOf(initialPlan?.id ?: "") }
    var description by remember(initialPlan) { mutableStateOf(initialPlan?.description ?: "") }
    var priceBdt by remember(initialPlan) { mutableStateOf(initialPlan?.priceBdt?.toString() ?: "") }
    var priceInr by remember(initialPlan) { mutableStateOf(initialPlan?.priceInr?.toString() ?: "") }
    var maxStudents by remember(initialPlan) { mutableStateOf(initialPlan?.maxStudents?.toString() ?: "100") }
    var maxBranches by remember(initialPlan) { mutableStateOf(initialPlan?.maxBranches?.toString() ?: "1") }
    var tag by remember(initialPlan) { mutableStateOf(initialPlan?.tag ?: "") }
    var tierLevel by remember(initialPlan) { mutableStateOf(initialPlan?.tierLevel?.toString() ?: "1") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(39.dp).clip(RoundedCornerShape(11.dp))
                        .background(AccentViolet.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (initialPlan == null) Icons.Filled.AddBusiness else Icons.Filled.EditNote,
                        null,
                        tint = AccentViolet,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        if (initialPlan == null) "Create Subscription Plan" else "Edit Subscription Plan",
                        color = TextWhite,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (initialPlan == null) "Add a new customer plan" else "Update ${initialPlan.name}",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                validationError?.let { error ->
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = AccentRed.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.35f))
                    ) {
                        Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ErrorOutline, null, tint = AccentRed, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(error, color = AccentRed, fontSize = 11.sp)
                        }
                    }
                }

                PlanEditorSectionLabel("Plan information", Icons.Filled.Description)
                PlanEditorTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (initialPlan == null) {
                            planId = "plan_${slugifyPlanName(it)}"
                        }
                    },
                    label = { Text("Plan name") },
                    modifier = Modifier.fillMaxWidth()
                )
                PlanEditorTextField(
                    value = planId,
                    onValueChange = {
                        val slug = slugifyPlanName(it)
                        planId = if (slug.startsWith("plan_")) slug else "plan_$slug"
                    },
                    label = { Text("Plan ID") },
                    modifier = Modifier.fillMaxWidth()
                )
                PlanEditorTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                PlanEditorSectionLabel("Pricing", Icons.Filled.Payments)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanEditorTextField(
                        value = priceBdt,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) priceBdt = it },
                        label = { Text("BDT") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    PlanEditorTextField(
                        value = priceInr,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) priceInr = it },
                        label = { Text("INR") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }

                PlanEditorSectionLabel("Capacity & display", Icons.Filled.Tune)
                PlanEditorTextField(
                    value = maxStudents,
                    onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) maxStudents = it },
                    label = { Text("Student limit") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanEditorTextField(
                        value = maxBranches,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) maxBranches = it },
                        label = { Text("Branches") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    PlanEditorTextField(
                        value = tierLevel,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) tierLevel = it },
                        label = { Text("Tier") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                PlanEditorTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text("Tag (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Surface(shape = RoundedCornerShape(9.dp), color = AccentCyan.copy(alpha = 0.08f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AllInclusive, null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Batch and staff access remain unlimited for every plan.", color = TextMuted, fontSize = 10.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                val normalizedId = planId.trim().ifBlank { "plan_${slugifyPlanName(name)}" }
                val parsedPriceBdt = priceBdt.toDoubleOrNull()
                val parsedPriceInr = priceInr.toDoubleOrNull() ?: 0.0
                val parsedMaxStudents = maxStudents.toIntOrNull()
                val parsedMaxBranches = maxBranches.toIntOrNull()
                val parsedTierLevel = tierLevel.toIntOrNull()
                validationError = when {
                    name.isBlank() -> "Plan name is required."
                    normalizedId == "plan_" || normalizedId.length < 6 -> "Plan ID is required."
                    description.isBlank() -> "Description is required."
                    parsedPriceBdt == null -> "Valid BDT price is required."
                    parsedMaxStudents == null -> "Student limit is required."
                    parsedMaxBranches == null -> "Branch limit is required."
                    parsedTierLevel == null -> "Tier level is required."
                    else -> null
                }
                if (validationError == null) {
                    onSave(
                        initialPlan?.id,
                        SubscriptionPlanEntity(
                            id = normalizedId,
                            name = name.trim(),
                            description = description.trim(),
                            priceBdt = parsedPriceBdt!!,
                            priceInr = parsedPriceInr,
                            maxStudents = parsedMaxStudents!!,
                            // Legacy fields are retained for existing cloud
                            // records, but batch and staff capacity is now
                            // unlimited and never enforced.
                            maxBatches = initialPlan?.maxBatches ?: 1,
                            maxUsers = initialPlan?.maxUsers ?: 1,
                            maxBranches = parsedMaxBranches!!,
                            tag = tag.trim(),
                            tierLevel = parsedTierLevel!!
                        )
                    )
                }
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 9.dp)
            ) {
                Icon(Icons.Filled.Save, null, tint = BgColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (initialPlan == null) "Create Plan" else "Save Changes", color = BgColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, BorderSub),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text("Cancel", color = TextMuted, fontSize = 12.sp)
            }
        },
        containerColor = CardBg,
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
private fun PlanEditorSectionLabel(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AccentViolet, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(title, color = TextWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(7.dp))
        HorizontalDivider(Modifier.weight(1f), color = BorderSub)
    }
}

@Composable
private fun PlanEditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit),
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(11.dp),
        textStyle = LocalTextStyle.current.copy(color = TextWhite, fontSize = 13.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextWhite,
            unfocusedTextColor = TextWhite,
            focusedBorderColor = AccentCyan,
            unfocusedBorderColor = BorderSub,
            focusedLabelColor = AccentCyan,
            unfocusedLabelColor = TextMuted,
            cursorColor = AccentCyan,
            focusedContainerColor = BorderSub.copy(alpha = 0.2f),
            unfocusedContainerColor = BorderSub.copy(alpha = 0.12f)
        )
    )
}

@Composable
private fun CompactStat(label: String, value: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        }
        Text(value, color = TextWhite, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = TextMuted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun RevenueCard(title: String, amount: String, color: Color, icon: ImageVector) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column {
                Text(title, color = TextMuted, fontSize = 10.sp, maxLines = 1)
                Text(amount, color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

// ── Monthly Revenue Estimate ────────────────────────────────
@Composable
private fun ProjectedRevenueCard(amount: Double, activeCount: Int) {
    val pulseAnim = rememberInfiniteTransition()
    val glowAlpha by pulseAnim.animateFloat(0.4f, 0.7f, infiniteRepeatable(tween(1500), RepeatMode.Reverse))
    val trendLine by pulseAnim.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse))
    val avgFee = if (activeCount > 0) amount / activeCount else 499.0

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, AccentViolet.copy(alpha = glowAlpha))
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(AccentViolet.copy(alpha = glowAlpha)))
                        Spacer(Modifier.width(6.dp))
                        Text("MONTHLY ESTIMATE", color = AccentViolet.copy(alpha = 0.8f), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("BDT ${NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 0 }.format(amount)}",
                        color = TextWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Loaded-directory revenue estimate", color = TextMuted, fontSize = 13.sp)
                    Text("Based on $activeCount loaded active subscriptions × avg BDT ${NumberFormat.getNumberInstance(Locale.getDefault()).format(avgFee.toInt())}",
                        color = TextMuted.copy(alpha = 0.6f), fontSize = 11.sp)
                }
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(AccentViolet.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Insights, null, tint = AccentViolet, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            // Mini trend bars
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(18) { i ->
                    val fraction = (0.3f + (trendLine * 0.5f) + (i * 0.02f.toFloat())).coerceIn(0.1f, 1f)
                    Box(Modifier.weight(1f).fillMaxHeight(fraction).clip(RoundedCornerShape(2.dp)).background(
                        Brush.verticalGradient(listOf(AccentViolet, AccentPink))
                    ))
                }
            }
        }
    }
}

// ── Institute Card ────────────────────────────────────────────
@Composable
private fun DetailRow(label: String, value: String, color: Color = TextMuted) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label:", color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.width(80.dp))
        Text(value, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun InstituteCard(
    card: InstituteCardData,
    viewModel: SuperAdminViewModel,
    subscriptionPlans: List<SubscriptionPlanEntity>
) {
    val inst = card.entity
    val ctx = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val statusColor = when (inst.subscriptionStatus) {
        "active" -> AccentGreen; "trial" -> AccentCyan; "expired" -> AccentRed; "blocked" -> AccentAmber; else -> TextMuted
    }
    val lastActive = viewModel.lastActiveLabel(inst.id)
    val currentPlanName = remember(subscriptionPlans, inst.currentPlanId) {
        planDisplayName(inst.currentPlanId, subscriptionPlans)
    }

    var showExtendDialog by remember { mutableStateOf(false) }
    var extendDays by remember { mutableStateOf("30") }
    var extendReason by remember { mutableStateOf("") }
    var showManageDialog by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }
    var showLoginActivity by remember { mutableStateOf(false) }
    val compactActionPadding = PaddingValues(horizontal = 4.dp)

    Card(
        Modifier.fillMaxWidth().clickable { showDetailSheet = true },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(AccentViolet.copy(alpha = 0.3f), ElectricBlue.copy(alpha = 0.15f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(inst.name.take(2).uppercase(), color = AccentViolet, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(inst.name, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (!inst.instituteCode.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Tag, null, tint = AccentViolet.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(inst.instituteCode, color = AccentViolet.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text("Plan: $currentPlanName · Joined ${dateFormat.format(Date(inst.createdAtMs))}", color = TextMuted, fontSize = 12.sp)
                }
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(statusColor.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(inst.subscriptionStatus.replaceFirstChar { it.uppercase() }, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Contact Info + Counts
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!inst.ownerName.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Person, null, tint = TextMuted.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(inst.ownerName, color = TextMuted, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    if (!inst.email.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Email, null, tint = TextMuted.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(inst.email, color = AccentCyan, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    if (!inst.phone.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Phone, null, tint = TextMuted.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(inst.phone, color = TextMuted, fontSize = 12.sp)
                        }
                    }
                    if (!inst.whatsappNumber.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = {
                                val phone = inst.whatsappNumber.replace("+", "").replace(" ", "").replace("-", "")
                                val msg = "Greetings from BatchFee Admin Panel\n\nThis is regarding your institute \"${inst.name}\".\n\nWe are reaching out from the BatchFee platform administration. If you have any questions about your subscription or services, please feel free to reply.\n\n— BatchFee Support Team"
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse("https://wa.me/$phone?text=${Uri.encode(msg)}")
                                }
                                ctx.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF25D366).copy(alpha = 0.4f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF25D366))
                        ) {
                            Icon(Icons.Filled.Chat, null, tint = Color(0xFF25D366), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("WhatsApp ${inst.whatsappNumber}", color = Color(0xFF25D366), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CalendarMonth, null, tint = TextMuted.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Until ${dateFormat.format(Date(effectiveSubscriptionExpiryMs(inst)))}", color = TextMuted, fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { showLoginActivity = true },
                        modifier = Modifier.fillMaxWidth().height(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.35f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                    ) {
                        Icon(Icons.Filled.AccessTime, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Activity", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text(lastActive, color = TextMuted, fontSize = 9.sp, maxLines = 1)
                        }
                        Icon(Icons.Filled.ChevronRight, null, modifier = Modifier.size(15.dp))
                    }
                    if (lastActive == "Never" || lastActive.contains("d ago") && lastActive.substringBefore("d").toIntOrNull()?.let { it > 7 } == true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(18.dp))
                            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(AccentRed.copy(alpha = 0.6f)))
                            Spacer(Modifier.width(4.dp))
                            Text("Inactive", color = AccentRed.copy(alpha = 0.7f), fontSize = 10.sp)
                        }
                    }
                }
            }

            // ── Per-institute counts ──
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(AccentCyan.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.People, null, tint = AccentCyan, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${card.studentCount} students", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(AccentPink.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("${card.staffCount} staff", color = AccentPink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(AccentViolet.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("${card.batchCount} batches", color = AccentViolet, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = BorderSub, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(10.dp))

            // Action buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showExtendDialog = true },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = compactActionPadding,
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                ) {
                    Icon(Icons.Filled.Update, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Extend", fontSize = 11.sp)
                }

                val blocked = inst.subscriptionStatus == "blocked"
                OutlinedButton(
                    onClick = { viewModel.toggleBlock(inst.id, blocked) },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = compactActionPadding,
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (blocked) AccentGreen else AccentRed)
                ) {
                    Icon(if (blocked) Icons.Filled.LockOpen else Icons.Filled.Block, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (blocked) "Unblock" else "Block", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = { showManageDialog = true },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = compactActionPadding,
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentViolet)
                ) {
                    Icon(Icons.Filled.Settings, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Manage", fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Remove button
                var showRemoveConfirm by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { showRemoveConfirm = true },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = compactActionPadding,
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                ) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Archive", fontSize = 11.sp)
                }

                // Remove confirmation dialog
                if (showRemoveConfirm) {
                    var removePassword by remember { mutableStateOf("") }
                    var showRemovePassword by remember { mutableStateOf(false) }
                    if (showRemovePassword) {
                        AlertDialog(
                            onDismissRequest = { showRemovePassword = false; showRemoveConfirm = false },
                            title = { Text("Enter Super Admin Password", color = TextWhite, fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    Text("\"${inst.name}\" will be archived in place. Auth access is reconciled while data, media and financial history stay retained for recovery.", color = TextMuted, fontSize = 13.sp)
                                    Spacer(Modifier.height(12.dp))
                                    Text("Enter your password to continue:", color = TextMuted, fontSize = 12.sp)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = removePassword,
                                        onValueChange = { removePassword = it },
                                        placeholder = { Text("Super admin password", color = TextMuted.copy(alpha = 0.5f)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                                            focusedBorderColor = AccentRed, unfocusedBorderColor = BorderSub,
                                            cursorColor = AccentRed
                                        )
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        viewModel.removeInstitute(inst.id, removePassword)
                                        showRemovePassword = false
                                        showRemoveConfirm = false
                                    },
                                    enabled = removePassword.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                                ) { Text("Archive Safely", color = Color.White, fontWeight = FontWeight.Bold) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRemovePassword = false; showRemoveConfirm = false }) {
                                    Text("Cancel", color = TextMuted)
                                }
                            },
                            containerColor = CardBg,
                            shape = RoundedCornerShape(16.dp)
                        )
                    } else {
                        AlertDialog(
                            onDismissRequest = { showRemoveConfirm = false },
                            title = { Text("Archive ${inst.name}?", color = TextWhite, fontWeight = FontWeight.Bold) },
                            text = {
                                Text("Archive this institute?\n\nAccess will be blocked atomically and the original institute tree will remain intact. There is no automatic permanent deletion; recovery and audit records are retained.", color = TextMuted, fontSize = 13.sp)
                            },
                            confirmButton = {
                                Button(onClick = { showRemovePassword = true }, colors = ButtonDefaults.buttonColors(containerColor = AccentRed)) {
                                    Text("Continue to Re-auth", color = Color.White)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel", color = TextMuted) }
                            },
                            containerColor = CardBg,
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.sendPasswordReset(inst.email) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = compactActionPadding,
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber)
                ) {
                    Icon(Icons.Filled.Password, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset", fontSize = 11.sp)
                }

                var editPin by remember { mutableStateOf(inst.securityPin ?: "") }
                var showPinDialog by remember { mutableStateOf(false) }

                if (showPinDialog) {
                    AlertDialog(
                        onDismissRequest = { showPinDialog = false },
                        title = { Text("Set Security PIN", fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text("4-6 digit PIN for ${inst.name}:", color = TextMuted, fontSize = 14.sp)
                                Spacer(Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = editPin,
                                    onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d{0,6}$"))) editPin = it },
                                    label = { Text("PIN") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.setSecurityPin(inst.id, editPin)
                                showPinDialog = false
                            }) { Text("Save", color = AccentCyan, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPinDialog = false }) { Text("Cancel", color = TextMuted) }
                        }
                    )
                }

                OutlinedButton(
                    onClick = { editPin = inst.securityPin ?: ""; showPinDialog = true },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = compactActionPadding,
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPink)
                ) {
                    Icon(Icons.Filled.Pin, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (inst.securityPin.isNullOrBlank()) "Set PIN" else "Edit PIN", fontSize = 11.sp)
                }
            }
        }
    }

    if (showLoginActivity) {
        InstituteDetailsTabsDialog(
            card = card,
            plans = subscriptionPlans,
            viewModel = viewModel,
            initialTab = "Logins",
            onDismiss = { showLoginActivity = false }
        )
    }

    // ── Detail Sheet ──
    if (showDetailSheet) {
        AlertDialog(
            onDismissRequest = { showDetailSheet = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(
                        Brush.linearGradient(listOf(AccentViolet.copy(alpha = 0.3f), ElectricBlue.copy(alpha = 0.15f)))),
                        contentAlignment = Alignment.Center
                    ) { Text(inst.name.take(2).uppercase(), color = AccentViolet, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(inst.name, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (!inst.instituteCode.isNullOrBlank()) Text(inst.instituteCode, color = AccentViolet, fontSize = 12.sp)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Stats row
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(AccentCyan.copy(alpha = 0.1f)).padding(12.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${card.studentCount}", color = AccentCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("Students", color = AccentCyan.copy(alpha = 0.7f), fontSize = 10.sp)
                            }
                        }
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(AccentPink.copy(alpha = 0.1f)).padding(12.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${card.staffCount}", color = AccentPink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("Staff", color = AccentPink.copy(alpha = 0.7f), fontSize = 10.sp)
                            }
                        }
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(AccentViolet.copy(alpha = 0.1f)).padding(12.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${card.batchCount}", color = AccentViolet, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("Batches", color = AccentViolet.copy(alpha = 0.7f), fontSize = 10.sp)
                            }
                        }
                    }

                    HorizontalDivider(color = BorderSub)

                    // Details
                    DetailRow("Owner", inst.ownerName ?: "N/A")
                    DetailRow("Phone", inst.phone ?: "N/A")
                    if (!inst.whatsappNumber.isNullOrBlank()) {
                        DetailRow("WhatsApp", inst.whatsappNumber, Color(0xFF25D366))
                    }
                    if (!inst.whatsappNumber.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                val phone = inst.whatsappNumber.replace("+", "").replace(" ", "").replace("-", "")
                                val msg = "Greetings from BatchFee Admin Panel\n\nThis is regarding your institute \"${inst.name}\".\n\nWe are reaching out from the BatchFee platform administration. If you have any questions about your subscription or services, please feel free to reply.\n\n— BatchFee Support Team"
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse("https://wa.me/$phone?text=${Uri.encode(msg)}")
                                }
                                ctx.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF25D366).copy(alpha = 0.4f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF25D366))
                        ) {
                            Icon(Icons.Filled.Chat, null, tint = Color(0xFF25D366), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("WhatsApp ${inst.whatsappNumber}", color = Color(0xFF25D366), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    DetailRow("Email", inst.email ?: "N/A")

                    // ── Current Plan Card ──
                    Spacer(Modifier.height(4.dp))
                    val planDetail = remember(subscriptionPlans, inst.currentPlanId) {
                        planDisplayDetails(inst.currentPlanId, subscriptionPlans)
                    }
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = BorderSub.copy(alpha = 0.3f)),
                        border = BorderStroke(1.dp, AccentViolet.copy(alpha = 0.25f))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.WorkspacePremium, null, tint = AccentViolet, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(currentPlanName, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (planDetail != null && planDetail.tag.isNotBlank()) {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(6.dp)).background(AccentViolet.copy(alpha = 0.15f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(planDetail.tag, color = AccentViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (planDetail != null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        PlanMetricChip("BDT ${formatMoneyValue(planDetail.priceBdt)}")
                                        PlanMetricChip(planStudentCapacityLabel(planDetail))
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        PlanMetricChip(planBatchCapacityLabel(planDetail))
                                        PlanMetricChip(planUserCapacityLabel(planDetail))
                                    }
                                } else {
                                    Text("Plan details unavailable", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                            if (planDetail != null && planDetail.description.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(planDetail.description, color = TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    DetailRow("Status", inst.subscriptionStatus.replaceFirstChar { it.uppercase() }, when (inst.subscriptionStatus) {
                        "active" -> AccentGreen; "trial" -> AccentCyan; "expired" -> AccentRed; "blocked" -> AccentAmber; else -> TextMuted
                    })
                    DetailRow("Expiry", dateFormat.format(Date(effectiveSubscriptionExpiryMs(inst))))
                    DetailRow("Joined", dateFormat.format(Date(inst.createdAtMs)))
                    DetailRow("Last Active", lastActive)
                    DetailRow("Institute ID", inst.id.take(12))
                    if (!inst.securityPin.isNullOrBlank()) {
                        var revealPin by remember { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Key, null, tint = AccentAmber.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("PIN:", color = TextMuted, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                            if (revealPin) {
                                Text(inst.securityPin, color = AccentAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { revealPin = false }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Filled.VisibilityOff, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                }
                            } else {
                                Text("••••••", color = TextMuted, fontSize = 12.sp)
                                IconButton(onClick = { revealPin = true }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Filled.Visibility, null, tint = AccentAmber, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    // ── Staff list (fetched from Firestore) ──
                    var staffList by remember { mutableStateOf<List<InstituteStaffSummary>?>(null) }
                    LaunchedEffect(inst.id) { viewModel.loadInstituteStaff(inst.id) { staffList = it } }

                    HorizontalDivider(color = BorderSub)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.People, null, tint = AccentPink.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Staff (${staffList?.size ?: 0})", color = AccentPink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    when {
                        staffList == null -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp).align(Alignment.CenterHorizontally),
                                strokeWidth = 2.dp, color = AccentPink
                            )
                        }
                        staffList!!.isEmpty() -> Text("No staff found", color = TextMuted, fontSize = 12.sp)
                        else -> {
                            staffList!!.take(10).forEach { s ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                                            .background(AccentPink.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(s.fullName.take(1).uppercase(), color = AccentPink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.fullName, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        Text("${s.roleTitle} · ${s.staffCode}", color = TextMuted, fontSize = 10.sp)
                                    }
                                    Box(
                                        Modifier.clip(RoundedCornerShape(4.dp)).background(
                                            when (s.status) {
                                                "active" -> AccentGreen; "suspended" -> AccentAmber; else -> AccentRed
                                            }.copy(alpha = 0.15f)
                                        ).padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            s.status.replaceFirstChar { it.uppercase() }, fontSize = 9.sp,
                                            color = when (s.status) { "active" -> AccentGreen; "suspended" -> AccentAmber; else -> AccentRed }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Subscription payment history (never mixed with student fee receipts) ──
                    var receiptList by remember { mutableStateOf<List<SubscriptionReceiptData>?>(null) }
                    LaunchedEffect(inst.id) { viewModel.loadInstituteReceipts(inst.id) { receiptList = it } }

                    HorizontalDivider(color = BorderSub)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ReceiptLong, null, tint = AccentGreen.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Payment History (${receiptList?.size ?: 0})", color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    when {
                        receiptList == null -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp).align(Alignment.CenterHorizontally),
                                strokeWidth = 2.dp, color = AccentGreen
                            )
                        }
                        receiptList!!.isEmpty() -> Text("No payment receipts found", color = TextMuted, fontSize = 12.sp)
                        else -> {
                            val ctx = LocalContext.current
                            receiptList!!.forEachIndexed { index, r ->
                                var showShareOptions by remember { mutableStateOf(false) }
                                val isTrial = r.paymentMethod == "free_trial"
                                val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                                val isLatest = index == 0
                                val planColor = when {
                                    isTrial -> AccentAmber
                                    isLatest -> AccentGreen
                                    else -> AccentGreen.copy(alpha = 0.6f)
                                }
                                Column {
                                    // Timeline connector
                                    if (index > 0) {
                                        Box(modifier = Modifier.width(26.dp).height(8.dp).padding(start = 12.5.dp).width(1.dp).fillMaxHeight().background(BorderSub))
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().clickable { showShareOptions = true },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Sequence number / badge
                                        Box(
                                            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                                                .background(planColor.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isTrial) {
                                                Icon(Icons.Filled.Star, null, tint = AccentAmber, modifier = Modifier.size(14.dp))
                                            } else {
                                                Text("${receiptList!!.size - index}", color = planColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        // Main info
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(r.planName, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                Spacer(Modifier.width(6.dp))
                                                if (isTrial) {
                                                    Box(
                                                        Modifier.clip(RoundedCornerShape(4.dp)).background(AccentAmber.copy(alpha = 0.15f))
                                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                                    ) {
                                                        Text("Trial", color = AccentAmber, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                                if (isLatest && !isTrial) {
                                                    Box(
                                                        Modifier.clip(RoundedCornerShape(4.dp)).background(AccentGreen.copy(alpha = 0.15f))
                                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                                    ) {
                                                        Text("Current", color = AccentGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                                Text(
                                                    "${dateFmt.format(Date(r.startDateMs))} — ${dateFmt.format(Date(r.endDateMs))}",
                                                    color = TextMuted, fontSize = 10.sp
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text("· ${r.durationMonths} mo", color = TextMuted.copy(alpha = 0.7f), fontSize = 10.sp)
                                                if (!isTrial && r.paymentMethod.isNotBlank()) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("· ${r.paymentMethod.uppercase()}", color = TextMuted.copy(alpha = 0.5f), fontSize = 10.sp)
                                                }
                                            }
                                        }
                                        // Amount
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                if (isTrial) "FREE" else "BDT ${"%,.0f".format(r.amountPaid)}",
                                                color = if (isTrial) AccentAmber else AccentGreen,
                                                fontSize = 13.sp, fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                }
                                if (showShareOptions) {
                                    AlertDialog(
                                        onDismissRequest = { showShareOptions = false },
                                        title = { Text("Receipt Actions", color = TextWhite, fontWeight = FontWeight.Bold) },
                                        text = { Column {
                                            Text(r.planName, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            Spacer(Modifier.height(4.dp))
                                            Text("Period: ${dateFmt.format(Date(r.startDateMs))} — ${dateFmt.format(Date(r.endDateMs))}", color = TextMuted, fontSize = 12.sp)
                                            Text("Duration: ${r.durationMonths} month(s)", color = TextMuted, fontSize = 12.sp)
                                            Text("Amount: ${if (isTrial) "Free Trial" else "BDT ${"%,.0f".format(r.amountPaid)}"}", color = if (isTrial) AccentAmber else AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            if (!isTrial && r.paymentMethod.isNotBlank()) Text("Payment: ${r.paymentMethod.uppercase()}", color = TextMuted, fontSize = 12.sp)
                                        } },
                                        confirmButton = {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Button(onClick = {
                                                    if (!shareSubscriptionReceiptToWhatsApp(ctx, r)) {
                                                        Toast.makeText(ctx, "WhatsApp is not installed.", Toast.LENGTH_SHORT).show()
                                                    }
                                                    showShareOptions = false
                                                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)), modifier = Modifier.weight(1f)) {
                                                    Text("WhatsApp", fontSize = 11.sp, color = Color.White)
                                                }
                                                Button(onClick = {
                                                    if (!openSubscriptionReceiptPdf(ctx, r)) {
                                                        Toast.makeText(ctx, "Unable to open the receipt PDF.", Toast.LENGTH_SHORT).show()
                                                    }
                                                    showShareOptions = false
                                                }, colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue), modifier = Modifier.weight(1f)) {
                                                    Text("View PDF", fontSize = 11.sp, color = Color.White)
                                                }
                                            }
                                        },
                                        dismissButton = { TextButton(onClick = { showShareOptions = false }) { Text("Close", color = TextMuted) } },
                                        containerColor = CardBg, shape = RoundedCornerShape(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        showDetailSheet = false
                        showExtendDialog = true
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Text("Extend", fontSize = 12.sp, color = AccentCyan)
                    }
                    OutlinedButton(onClick = {
                        showDetailSheet = false
                        showManageDialog = true
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Text("Manage", fontSize = 12.sp, color = AccentViolet)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showDetailSheet = false }) { Text("Close", color = TextMuted) } },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Extend Dialog
    if (showExtendDialog) {
        val currentExpiryMs = effectiveSubscriptionExpiryMs(inst)
        val currentExpiryDate = remember(currentExpiryMs) {
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(currentExpiryMs))
        }
        val days = extendDays.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val newExpiryMs = maxOf(currentExpiryMs, System.currentTimeMillis()) + (days * MILLIS_PER_DAY)
        val newExpiryDate = remember(days) {
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(newExpiryMs))
        }
        val daysRemaining = remember(currentExpiryMs) {
            ((currentExpiryMs - System.currentTimeMillis()) / MILLIS_PER_DAY).coerceAtLeast(0).toInt()
        }

        AlertDialog(
            onDismissRequest = { showExtendDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AccentCyan.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Update, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Extend Subscription", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(inst.name, color = TextMuted, fontSize = 12.sp)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Current status card
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = BorderSub.copy(alpha = 0.3f))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CalendarToday, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Current Status", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("Plan", color = TextMuted.copy(alpha = 0.6f), fontSize = 10.sp)
                                    Text(currentPlanName, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Expires", color = TextMuted.copy(alpha = 0.6f), fontSize = 10.sp)
                                    Text(currentExpiryDate, color = if (daysRemaining <= 3) AccentRed else TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(
                                    if (daysRemaining <= 3) AccentRed else if (daysRemaining <= 7) AccentAmber else AccentGreen
                                ))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (daysRemaining <= 0) "Expired" else "$daysRemaining days remaining",
                                    color = if (daysRemaining <= 0) AccentRed else TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Quick presets
                    Text("Quick Presets", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "+7 Days" to 7,
                            "+15 Days" to 15,
                            "+30 Days" to 30
                        ).forEach { (label, preset) ->
                            FilterChip(
                                selected = days == preset,
                                onClick = { extendDays = preset.toString() },
                                modifier = Modifier.weight(1f),
                                label = { Text(label, fontSize = 10.sp, fontWeight = if (days == preset) FontWeight.Bold else FontWeight.Normal) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = CardBg,
                                    selectedContainerColor = AccentCyan.copy(alpha = 0.15f),
                                    labelColor = TextMuted,
                                    selectedLabelColor = AccentCyan
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = BorderSub,
                                    selectedBorderColor = AccentCyan.copy(alpha = 0.4f),
                                    enabled = true, selected = days == preset
                                ),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = extendDays,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d{1,4}$"))) extendDays = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom extension (days)") },
                        supportingText = { Text("Use this for any duration, such as 90 days.", fontSize = 10.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
                            focusedBorderColor = AccentCyan, unfocusedBorderColor = BorderSub,
                            focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                            cursorColor = AccentCyan, focusedLabelColor = AccentCyan, unfocusedLabelColor = TextMuted
                        )
                    )

                    OutlinedTextField(
                        value = extendReason,
                        onValueChange = { if (it.length <= 500) extendReason = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Reason for access extension") },
                        placeholder = { Text("e.g. Support-approved grace period", fontSize = 12.sp) },
                        minLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
                            focusedBorderColor = AccentCyan, unfocusedBorderColor = BorderSub,
                            focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                            cursorColor = AccentCyan, focusedLabelColor = AccentCyan, unfocusedLabelColor = TextMuted
                        )
                    )
                    Text("Reason is saved in the subscription audit log.", color = if (extendReason.trim().length >= 3) AccentGreen else TextMuted, fontSize = 10.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            "Grace period",
                            "Payment pending",
                            "Support approved"
                        ).forEach { template ->
                            TextButton(
                                onClick = { extendReason = template },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                            ) { Text(template, color = AccentCyan, fontSize = 9.sp, maxLines = 1) }
                        }
                    }

                    // Preview
                    if (days > 0) {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(alpha = 0.08f)),
                            border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.25f))
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.TrendingUp, null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("New Expiry Date", color = TextMuted, fontSize = 10.sp)
                                    Text(newExpiryDate, color = AccentGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "+$days day${if (days > 1) "s" else ""}",
                                    color = AccentGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val d = extendDays.toIntOrNull() ?: 0
                        if (d > 0) {
                            viewModel.extendSubscription(inst.id, d, extendReason)
                            if (extendReason.trim().length >= 3) {
                                showExtendDialog = false
                                extendDays = "30"
                                extendReason = ""
                            }
                        }
                    },
                    enabled = days > 0 && extendReason.trim().length >= 3,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        disabledContainerColor = BorderSub
                    )
                ) {
                    Text("Extend ${if (days > 0) "$days Days" else ""}", fontWeight = FontWeight.Bold, color = if (days > 0) CardBg else TextMuted)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExtendDialog = false; extendDays = "30"; extendReason = "" }) { Text("Cancel", color = TextMuted) }
            },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Manage Dialog
    if (showManageDialog) {
        val cal = remember { Calendar.getInstance() }
        if (effectiveSubscriptionExpiryMs(inst) > 0) cal.timeInMillis = effectiveSubscriptionExpiryMs(inst)
        var editYear by remember { mutableIntStateOf(cal.get(Calendar.YEAR)) }
        var editMonth by remember { mutableIntStateOf(cal.get(Calendar.MONTH)) }
        var editDay by remember { mutableIntStateOf(cal.get(Calendar.DAY_OF_MONTH)) }
        var editStudentLimit by remember { mutableStateOf("50") }
        var editIsActive by remember { mutableStateOf(inst.subscriptionStatus != "blocked") }
        var editAddMonths by remember { mutableStateOf("0") }

        val planOptions = remember(subscriptionPlans) {
            subscriptionPlans.associate { it.id to it.name }
        }
        var selectedPlanId by remember { mutableStateOf(inst.currentPlanId) }
        var selectedPlanName by remember { mutableStateOf(planOptions[inst.currentPlanId] ?: humanizePlanId(inst.currentPlanId)) }
        LaunchedEffect(planOptions, selectedPlanId) {
            selectedPlanName = planOptions[selectedPlanId] ?: humanizePlanId(selectedPlanId)
        }

        val selectedPlanDetail = remember(subscriptionPlans, selectedPlanId) {
            subscriptionPlans.firstOrNull { it.id == selectedPlanId }
        }
        LaunchedEffect(selectedPlanDetail) {
            if (selectedPlanDetail != null && showManageDialog) {
                editStudentLimit = selectedPlanDetail!!.maxStudents.toString()
            }
        }

        val mgrDateFmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

        fun computedExpiryMs(): Long {
            val c = Calendar.getInstance()
            c.set(editYear, editMonth, editDay, 23, 59, 59)
            val addMonthsVal = editAddMonths.toIntOrNull()?.coerceIn(0, 120) ?: 0
            if (addMonthsVal > 0) c.add(Calendar.MONTH, addMonthsVal)
            return c.timeInMillis
        }

        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AccentViolet.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Tune, null, tint = AccentViolet, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Manage Institute", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(inst.name, color = TextMuted, fontSize = 12.sp)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ── Current Plan Card ──
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = BorderSub.copy(alpha = 0.3f)), border = BorderStroke(1.dp, AccentViolet.copy(alpha = 0.2f))) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.WorkspacePremium, null, tint = AccentViolet, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(currentPlanName, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (!inst.instituteCode.isNullOrBlank()) {
                                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(AccentViolet.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                                        Text(inst.instituteCode, color = AccentViolet, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column {
                                    Text("Expires", color = TextMuted.copy(alpha = 0.6f), fontSize = 10.sp)
                                    Text(mgrDateFmt.format(Date(effectiveSubscriptionExpiryMs(inst))), color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                                Column {
                                    Text("Status", color = TextMuted.copy(alpha = 0.6f), fontSize = 10.sp)
                                    Text(inst.subscriptionStatus.replaceFirstChar { it.uppercase() }, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }

                    // ── Switch Plan ──
                    Text("Switch Plan", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    var editPlanDropdown by remember { mutableStateOf(false) }
                    Box {
                        OutlinedTextField(value = selectedPlanName, onValueChange = { }, readOnly = true, leadingIcon = { Icon(Icons.Filled.WorkspacePremium, null, tint = AccentViolet, modifier = Modifier.size(18.dp)) }, trailingIcon = { Icon(if (editPlanDropdown) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown, null, tint = AccentViolet, modifier = Modifier.clickable { editPlanDropdown = !editPlanDropdown }) }, modifier = Modifier.fillMaxWidth().clickable { editPlanDropdown = !editPlanDropdown }, shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = CardBg, unfocusedContainerColor = CardBg, focusedBorderColor = AccentViolet, unfocusedBorderColor = BorderSub, focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, cursorColor = AccentViolet))
                        DropdownMenu(expanded = editPlanDropdown, onDismissRequest = { editPlanDropdown = false }, modifier = Modifier.background(CardBg)) {
                            planOptions.forEach { (id, name) ->
                                val isCurrent = id == selectedPlanId
                                DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) { Text(name, color = if (isCurrent) AccentViolet else TextWhite, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal); if (isCurrent) { Spacer(Modifier.width(8.dp)); Icon(Icons.Filled.Check, null, tint = AccentViolet, modifier = Modifier.size(16.dp)) } } }, onClick = { selectedPlanId = id; selectedPlanName = name; editPlanDropdown = false })
                            }
                        }
                    }

                    // ── Plan Preview (when switching) ──
                    if (selectedPlanDetail != null && selectedPlanId != inst.currentPlanId) {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = AccentViolet.copy(alpha = 0.08f)), border = BorderStroke(1.dp, AccentViolet.copy(alpha = 0.2f))) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PlanMetricChip("BDT ${formatMoneyValue(selectedPlanDetail!!.priceBdt)}")
                                    PlanMetricChip(planStudentCapacityLabel(selectedPlanDetail!!))
                                    PlanMetricChip(planUserCapacityLabel(selectedPlanDetail!!))
                                }
                                if (selectedPlanDetail!!.description.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(selectedPlanDetail!!.description, color = TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }

                    // ── Expiry Date ──
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = BorderSub.copy(alpha = 0.3f))) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CalendarMonth, null, tint = AccentCyan, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Expiry Date", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                mgrDateField(editDay.toString().padStart(2, '0'), "Day", { val v = it.toIntOrNull(); if (v != null && v in 1..31) editDay = v }, Modifier.weight(1f))
                                mgrDateField((editMonth + 1).toString().padStart(2, '0'), "Month", { val v = it.toIntOrNull(); if (v != null && v in 1..12) editMonth = v - 1 }, Modifier.weight(1f))
                                mgrDateField(editYear.toString(), "Year", { val v = it.toIntOrNull(); if (v != null && v in 2024..2099) editYear = v }, Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = editAddMonths, onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) editAddMonths = it }, label = { Text("+Months", fontSize = 10.sp) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = CardBg, unfocusedContainerColor = CardBg, focusedBorderColor = AccentCyan, unfocusedBorderColor = BorderSub, focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, cursorColor = AccentCyan, focusedLabelColor = AccentCyan, unfocusedLabelColor = TextMuted))
                                Card(Modifier.weight(1f), shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = AccentCyan.copy(alpha = 0.1f))) {
                                    Box(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) { Text("→ ${mgrDateFmt.format(Date(computedExpiryMs()))}", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }

                    // ── Limits ──
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = BorderSub.copy(alpha = 0.3f))) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Groups, null, tint = AccentPink, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Institute Limits", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(value = editStudentLimit, onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) editStudentLimit = it }, label = { Text("Students", fontSize = 10.sp) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = CardBg, unfocusedContainerColor = CardBg, focusedBorderColor = AccentPink, unfocusedBorderColor = BorderSub, focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, cursorColor = AccentPink, focusedLabelColor = AccentPink, unfocusedLabelColor = TextMuted))
                            Spacer(Modifier.height(8.dp))
                            Text("Batches and staff are unlimited for every active institute.", color = AccentCyan, fontSize = 11.sp)
                        }
                    }

                    // ── Account Active ──
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = BorderSub.copy(alpha = 0.3f))) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Account Access", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text(if (editIsActive) "Institute can login and use the app" else "Institute is blocked from accessing the app", color = TextMuted, fontSize = 10.sp) }
                            Switch(checked = editIsActive, onCheckedChange = { editIsActive = it }, colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen, checkedTrackColor = AccentGreen.copy(alpha = 0.25f), uncheckedThumbColor = AccentRed, uncheckedTrackColor = AccentRed.copy(alpha = 0.25f)))
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { val studentLimit = if (selectedPlanId == DEFAULT_TRIAL_PLAN_ID) 0 else (editStudentLimit.toIntOrNull()?.coerceAtLeast(1) ?: 50); val legacyStaffLimit = 1; viewModel.manageInstitute(inst.id, computedExpiryMs(), studentLimit, legacyStaffLimit, selectedPlanId, editIsActive) { showManageDialog = false } }, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = AccentViolet)) {
                    Icon(Icons.Filled.Save, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save Changes", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextWhite)
                }
            },
            dismissButton = { TextButton(onClick = { showManageDialog = false }) { Text("Cancel", color = TextMuted) } },
            containerColor = CardBg, shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun mgrDateField(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label, fontSize = 10.sp) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = modifier, shape = RoundedCornerShape(10.dp), colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = CardBg, unfocusedContainerColor = CardBg, focusedBorderColor = AccentCyan, unfocusedBorderColor = BorderSub, focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, cursorColor = AccentCyan, focusedLabelColor = AccentCyan, unfocusedLabelColor = TextMuted))
}

// ── Subscription Receipt PDF ─────────────────────────────
internal fun generateSubscriptionReceiptPdf(context: Context, r: SubscriptionReceiptData): File {
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, 1).create()) // Letter size
    val canvas = page.canvas
    val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    val w = 612f; val h = 792f

    val white = android.graphics.Color.WHITE
    val darkBg = android.graphics.Color.parseColor("#0B1121")
    val cyan = android.graphics.Color.parseColor("#22D3EE")
    val muted = android.graphics.Color.parseColor("#64748B")
    val textDark = android.graphics.Color.parseColor("#111827")
    val textWhite = android.graphics.Color.parseColor("#F8FAFC")
    val lightBg = android.graphics.Color.parseColor("#F8FAFC")
    val green = android.graphics.Color.parseColor("#16A34A")
    val red = android.graphics.Color.parseColor("#EF4444")
    val gold = android.graphics.Color.parseColor("#F59E0B")
    val gray200 = android.graphics.Color.parseColor("#E2E8F0")
    val darkAccent = android.graphics.Color.parseColor("#1E293B")

    val fill = Paint().apply { style = Paint.Style.FILL }
    val stroke = Paint().apply { style = Paint.Style.STROKE; color = gray200; strokeWidth = 0.5f }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = textDark }
    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; color = textDark; isFakeBoldText = true }
    val whiteBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; color = white; isFakeBoldText = true }
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; color = white; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = muted; textAlign = Paint.Align.CENTER }

    // ── Try loading app logo ──
    var logoBitmap: android.graphics.Bitmap? = null
    try {
        logoBitmap = android.graphics.BitmapFactory.decodeResource(context.resources,
            context.resources.getIdentifier("app_logo", "drawable", context.packageName))
    } catch (_: Exception) { }

    // ── Dark header band ──
    fill.color = darkBg
    canvas.drawRect(0f, 0f, w, 145f, fill)

    // Logo circle with initial fallback
    if (logoBitmap != null) {
        val scaled = android.graphics.Bitmap.createScaledBitmap(logoBitmap, 52, 52, true)
        canvas.save()
        canvas.clipRect(39f, 42f, 91f, 94f)
        val circular = android.graphics.Bitmap.createBitmap(52, 52, android.graphics.Bitmap.Config.ARGB_8888)
        val circleCanvas = Canvas(circular)
        val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isAntiAlias = true }
        circleCanvas.drawCircle(26f, 26f, 26f, clipPaint)
        clipPaint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        circleCanvas.drawBitmap(scaled, 0f, 0f, clipPaint)
        canvas.drawBitmap(circular, 39f, 42f, null)
        canvas.restore()
    } else {
        // Fallback: BF circle
        fill.color = cyan
        canvas.drawCircle(65f, 68f, 26f, fill)
        val initPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darkBg; textSize = 20f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("BF", 65f, 76f, initPaint)
    }

    // App name
    whiteBold.textSize = 20f; whiteBold.textAlign = Paint.Align.LEFT
    canvas.drawText("BatchFee", 105f, 60f, whiteBold)
    whiteBold.textSize = 11f; whiteBold.color = cyan
    canvas.drawText("Education Management Platform", 105f, 78f, whiteBold)
    whiteBold.color = white

    // Receipt title
    title.textSize = 18f
    canvas.drawText("SUBSCRIPTION RECEIPT", w / 2, 118f, title)
    // Subtitle line
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = muted; textAlign = Paint.Align.CENTER }
    canvas.drawText("Receipt #: ${r.receiptNumber}  •  Issued: ${dateFmt.format(Date(r.startDateMs))}", w / 2, 133f, subPaint)

    // ── Institute Details box ──
    var y = 170f
    fill.color = lightBg
    canvas.drawRoundRect(36f, y, w - 36f, y + 72f, 8f, 8f, fill)
    bold.textSize = 13f; bold.color = textDark; bold.textAlign = Paint.Align.LEFT
    canvas.drawText("Bill To", 52f, y + 18f, bold)
    text.textSize = 10f; text.color = textDark
    canvas.drawText(r.instituteName, 52f, y + 34f, text)
    text.color = muted
    canvas.drawText("Owner: ${r.ownerName}", 52f, y + 48f, text)
    if (r.ownerPhone.isNotBlank()) canvas.drawText("Phone: ${r.ownerPhone}", 52f, y + 62f, text)

    // Right column: code + email
    if (r.instituteCode.isNotBlank()) {
        text.textSize = 10f; text.color = muted
        canvas.drawText("Institute Code: ${r.instituteCode}", 310f, y + 34f, text)
    }
    if (r.ownerEmail.isNotBlank()) {
        canvas.drawText("Email: ${r.ownerEmail}", 310f, y + 48f, text)
    }
    if (r.instituteAddress.isNotBlank()) {
        canvas.drawText("Address: ${r.instituteAddress}", 310f, y + 62f, text)
    }

    // ── Subscription Details ──
    y += 98f
    bold.textSize = 14f; bold.color = textDark
    canvas.drawText("Subscription Details", 40f, y, bold)
    y += 18f
    fill.color = cyan; canvas.drawRect(40f, y, 40f + 94f, y + 3f, fill)
    y += 16f

    // Table header
    fill.color = darkBg
    canvas.drawRect(40f, y, w - 40f, y + 22f, fill)
    text.textSize = 9f; text.color = white; text.isFakeBoldText = true
    canvas.drawText("Description", 52f, y + 15f, text)
    canvas.drawText("Details", 340f, y + 15f, text)
    canvas.drawText("Amount", 490f, y + 15f, text)
    text.isFakeBoldText = false; text.color = textDark
    y += 26f

    // Table rows
    fun drawRow(label: String, value: String, amount: String = "", highlight: Boolean = false, rowHeight: Float = 20f) {
        if (highlight) {
            fill.color = android.graphics.Color.parseColor("#F0FDF4")
            canvas.drawRect(40f, y - 2f, w - 40f, y + rowHeight, fill)
        }
        text.textSize = 10f; text.color = muted; text.isFakeBoldText = false
        canvas.drawText(label, 52f, y + 11f, text)
        text.color = textDark
        canvas.drawText(value, 340f, y + 11f, text)
        if (amount.isNotBlank()) {
            text.color = if (highlight) green else textDark
            text.isFakeBoldText = highlight
            canvas.drawText(amount, 490f, y + 11f, text)
            text.isFakeBoldText = false
        }
        if (!highlight) {
            canvas.drawLine(40f, y + rowHeight + 2f, w - 40f, y + rowHeight + 2f, stroke)
        }
        y += rowHeight + 4f
    }

    drawRow("Plan", r.planName)
    drawRow("Duration", "${r.durationMonths} Month(s)")
    drawRow("Period", "${dateFmt.format(Date(r.startDateMs))} — ${dateFmt.format(Date(r.endDateMs))}")
    if (r.paymentMethod.isNotBlank()) {
        drawRow("Payment Method", r.paymentMethod.uppercase())
    }
    if (r.senderPhone.isNotBlank()) {
        drawRow("Sending Number", r.senderPhone)
    }
    if (r.transactionLast4.isNotBlank()) {
        drawRow("Transaction Ref", "••••${r.transactionLast4}")
    }

    // Amount row — highlighted
    y += 4f
    drawRow("Amount Paid", "", "BDT ${"%,.0f".format(r.amountPaid)}", highlight = true, rowHeight = 24f)

    // ── Status badge ──
    y += 20f
    val green12 = android.graphics.Color.argb((0.12f * 255).toInt(), 22, 163, 74)
    fill.color = green12
    canvas.drawRoundRect(40f, y, w - 40f, y + 32f, 6f, 6f, fill)
    val statusLabel = if (r.paymentMethod == "free_trial") "FREE TRIAL — 15 DAYS" else "PAID — APPROVED & ACTIVE"
    bold.textSize = 11f; bold.color = green
    canvas.drawText(statusLabel, 56f, y + 21f, bold)

    // ── Watermark ──
    y += 60f
    val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = muted; textSize = 120f; alpha = 8; isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText("BatchFee", w / 2, h / 2 + 40, watermarkPaint)

    // ── Footer ──
    val footerY = h - 82f
    fill.color = darkBg; canvas.drawRect(0f, footerY, w, h, fill)
    var fy = footerY + 20f
    center.textSize = 11f; center.color = textWhite; center.isFakeBoldText = true
    canvas.drawText("BatchFee Education Management Platform", w / 2, fy, center)
    fy += 18f
    center.textSize = 9f; center.color = muted; center.isFakeBoldText = false
    canvas.drawText("This is a computer-generated receipt from the BatchFee admin panel.", w / 2, fy, center)
    fy += 16f
    canvas.drawText("For any queries, contact your institute administrator or visit batchfee.app", w / 2, fy, center)
    fy += 20f
    canvas.drawText("© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} BatchFee. All rights reserved.", w / 2, fy, center)

    document.finishPage(page)
    val file = File(context.cacheDir, "sub_receipt_${r.receiptNumber}.pdf")
    file.outputStream().use { document.writeTo(it) }
    document.close()
    return file
}

/** Opens the saved receipt in a PDF app, from where Android's system print option is available. */
internal fun openSubscriptionReceiptPdf(context: Context, receipt: SubscriptionReceiptData): Boolean = try {
    val file = generateSubscriptionReceiptPdf(context, receipt)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        clipData = ClipData.newRawUri("Subscription receipt", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
    true
} catch (_: Exception) {
    false
}

/** Shares the receipt PDF through either WhatsApp variant while granting it read access to the file. */
internal fun shareSubscriptionReceiptToWhatsApp(context: Context, receipt: SubscriptionReceiptData): Boolean = try {
    val file = generateSubscriptionReceiptPdf(context, receipt)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val shareText = buildString {
        append("BatchFee subscription receipt #${receipt.receiptNumber}")
        append(" for ${receipt.instituteName}.")
        if (receipt.ownerPhone.isNotBlank()) append(" Owner: ${receipt.ownerPhone}")
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, shareText)
        clipData = ClipData.newRawUri("Subscription receipt", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { packageName ->
        intent.setPackage(packageName)
        intent.resolveActivity(context.packageManager) != null
    }?.let {
        context.startActivity(intent)
        true
    } ?: false
} catch (_: Exception) {
    false
}

@Composable
private fun BroadcastSection(
    announceText: String,
    onAnnounceTextChange: (String) -> Unit,
    activeInstituteCount: Int,
    onSend: (String, Int) -> Unit,
    announcements: List<AnnouncementData>,
    onEdit: (AnnouncementData, String, Int) -> Unit,
    onArchive: (AnnouncementData) -> Unit,
    onRestore: (AnnouncementData) -> Unit,
    onDelete: (AnnouncementData) -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }
    var expiryDays by remember { mutableIntStateOf(0) }
    var expiryMenuExpanded by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var selectedArchived by remember { mutableStateOf<AnnouncementData?>(null) }
    val expiryOptions = listOf(0 to "Never", 1 to "1 Day", 3 to "3 Days", 7 to "7 Days", 30 to "30 Days")
    val selectedExpiryLabel = remember(expiryDays) { expiryOptions.firstOrNull { it.first == expiryDays }?.second ?: "Never" }

    Column {
        Text("System Broadcast", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))

        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(AccentPink.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Campaign, null, tint = AccentPink, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Global Notification", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("${announceText.length}/500 characters", color = if (announceText.length > 450) AccentRed else TextMuted, fontSize = 10.sp)
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = AccentGreen.copy(alpha = 0.12f)) {
                        Text(
                            "$activeInstituteCount active",
                            color = AccentGreen,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = announceText, onValueChange = { if (it.length <= 500) onAnnounceTextChange(it) },
                    placeholder = { Text("Write an announcement for all active institutes...", color = TextMuted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
                        focusedBorderColor = AccentPink, unfocusedBorderColor = BorderSub,
                        focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, cursorColor = AccentPink
                    )
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, tint = TextMuted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Visible for", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(
                            onClick = { expiryMenuExpanded = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(9.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPink)
                        ) {
                            Text(selectedExpiryLabel, fontSize = 11.sp)
                            Spacer(Modifier.width(3.dp))
                            Icon(Icons.Filled.ExpandMore, null, modifier = Modifier.size(16.dp))
                        }
                        DropdownMenu(expanded = expiryMenuExpanded, onDismissRequest = { expiryMenuExpanded = false }, containerColor = CardBg) {
                            expiryOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = if (expiryDays == value) AccentPink else TextWhite) },
                                    onClick = { expiryDays = value; expiryMenuExpanded = false }
                                )
                            }
                        }
                    }
                }

                // Live preview
                if (announceText.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Card(
                        Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = AccentPink.copy(alpha = 0.08f)),
                        border = BorderStroke(1.dp, AccentPink.copy(alpha = 0.2f))
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Campaign, null, tint = AccentPink.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Preview", color = AccentPink, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                Text(announceText, color = TextWhite, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { showConfirm = true },
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    enabled = announceText.trim().isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPink, disabledContainerColor = BorderSub)
                ) {
                    Icon(Icons.Filled.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send to $activeInstituteCount active institutes", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (announceText.isNotBlank()) Color.White else TextMuted)
                }
            }
        }

        // Confirmation dialog
        if (showConfirm) {
            AlertDialog(
                onDismissRequest = { showConfirm = false },
                title = { Text("Send Announcement?", color = TextWhite, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("This will be sent to ALL institutes on the platform.", color = TextMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = BorderSub.copy(alpha = 0.3f))) {
                            Text(announceText, color = TextWhite, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Expiry: $selectedExpiryLabel", color = TextMuted, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    Button(onClick = { onSend(announceText, expiryDays); showConfirm = false; expiryDays = 0 }, colors = ButtonDefaults.buttonColors(containerColor = AccentPink)) {
                        Text("Confirm & Send", color = Color.White)
                    }
                },
                dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel", color = TextMuted) } },
                containerColor = CardBg, shape = RoundedCornerShape(16.dp)
            )
        }

        // Announcement History
        val activeAnnouncements = announcements
            .filter { it.status != "deleted" && it.status != "archived" }
            .sortedByDescending { it.sentAt }
        val archivedAnnouncements = announcements
            .filter { it.status == "archived" }
            .sortedByDescending { it.sentAt }
        if (activeAnnouncements.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("History · ${activeAnnouncements.size}", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Tap to edit", color = TextMuted.copy(alpha = 0.5f), fontSize = 10.sp)
            }
            Spacer(Modifier.height(6.dp))
            activeAnnouncements.take(15).forEach { a ->
                var showActions by remember { mutableStateOf(false) }
                var editText by remember { mutableStateOf("") }
                var editExpiry by remember { mutableIntStateOf(0) }
                var showEditDialog by remember { mutableStateOf(false) }
                var showDeleteConfirm by remember { mutableStateOf(false) }
                val aDateFmt = remember { SimpleDateFormat("dd MMM yy, HH:mm", Locale.getDefault()) }
                val statusColor = when (a.status) {
                    "active" -> AccentGreen; "archived" -> AccentAmber; "expired" -> AccentRed; else -> TextMuted
                }
                Card(
                    Modifier.fillMaxWidth().clickable { showActions = true }.padding(bottom = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, BorderSub)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(statusColor.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Campaign, null, tint = statusColor, modifier = Modifier.size(14.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.message, color = TextWhite, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row {
                                    Text(aDateFmt.format(Date(a.sentAt)), color = TextMuted, fontSize = 10.sp)
                                    if (a.status == "expired") { Text(" · Expired", color = AccentRed, fontSize = 10.sp) }
                                    else if (a.expiresAt != null) {
                                        val remMs = a.expiresAt - System.currentTimeMillis()
                                        if (remMs > 0) { Text(" · ${(remMs / MILLIS_PER_DAY).toInt()}d left", color = TextMuted, fontSize = 10.sp) }
                                    }
                                }
                            }
                            Box(Modifier.clip(RoundedCornerShape(5.dp)).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(a.status.replaceFirstChar { it.uppercase() }, color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                // Actions dialog
                if (showActions) {
                    AlertDialog(
                        onDismissRequest = { showActions = false },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(statusColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Filled.Campaign, null, tint = statusColor, modifier = Modifier.size(20.dp)) }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Announcement details", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                                    Text("Platform broadcast", color = TextMuted, fontSize = 11.sp)
                                }
                                Surface(shape = RoundedCornerShape(7.dp), color = statusColor.copy(alpha = 0.14f)) {
                                    Text(a.status.replaceFirstChar { it.uppercase() }, color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                                }
                            }
                        },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(shape = RoundedCornerShape(12.dp), color = BorderSub.copy(alpha = 0.45f), border = BorderStroke(1.dp, BorderSub)) {
                                Text(a.message, color = TextWhite, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(12.dp))
                            }
                            Text("Sent: ${aDateFmt.format(Date(a.sentAt))}", color = TextMuted, fontSize = 11.sp)
                            if (a.updatedAt != a.sentAt) Text("Edited: ${aDateFmt.format(Date(a.updatedAt))}", color = TextMuted, fontSize = 11.sp)
                            Text("Status: ${a.status.uppercase()} · Expires: ${if (a.expiresAt != null) aDateFmt.format(Date(a.expiresAt)) else "Never"}", color = TextMuted, fontSize = 11.sp)
                        } },
                        confirmButton = {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (a.status == "active") {
                                    Button(onClick = { editText = a.message; editExpiry = if (a.expiresAt != null) ((a.expiresAt - a.sentAt) / MILLIS_PER_DAY).toInt() else 0; showEditDialog = true; showActions = false }, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan), modifier = Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(10.dp)) {
                                        Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text("Edit", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    OutlinedButton(onClick = { onArchive(a); showActions = false }, modifier = Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber)) {
                                        Icon(Icons.Filled.Archive, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text("Archive", fontSize = 12.sp)
                                    }
                                }
                                if (a.status == "archived") {
                                    Button(
                                        onClick = { onRestore(a); showActions = false },
                                        modifier = Modifier.fillMaxWidth().height(40.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                                    ) {
                                        Icon(Icons.Filled.Restore, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text("Restore announcement", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
                                    }
                                } else if (a.status != "active") {
                                    OutlinedButton(onClick = { showDeleteConfirm = true; showActions = false }, modifier = Modifier.fillMaxWidth().height(40.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)) {
                                        Icon(Icons.Filled.DeleteOutline, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text("Delete", fontSize = 12.sp)
                                    }
                                }
                            }
                        },
                        dismissButton = {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { showDeleteConfirm = true; showActions = false }) {
                                    Text("Delete", color = AccentRed, fontSize = 12.sp)
                                }
                                TextButton(onClick = { showActions = false }) { Text("Close", color = TextMuted, fontSize = 12.sp) }
                            }
                        },
                        containerColor = CardBg, shape = RoundedCornerShape(16.dp)
                    )
                }
                // Edit dialog
                if (showEditDialog) {
                    AlertDialog(
                        onDismissRequest = { showEditDialog = false },
                        title = { Text("Edit Announcement", color = TextWhite, fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                OutlinedTextField(value = editText, onValueChange = { if (it.length <= 500) editText = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), shape = RoundedCornerShape(10.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentCyan, focusedTextColor = TextWhite, unfocusedTextColor = TextWhite, cursorColor = AccentCyan))
                                Spacer(Modifier.height(8.dp))
                                Text("Expiry: ${expiryOptions.firstOrNull { it.first == editExpiry }?.second ?: "Never"}", color = TextMuted, fontSize = 11.sp)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { expiryOptions.forEach { (v, l) -> FilterChip(selected = editExpiry == v, onClick = { editExpiry = v }, label = { Text(l, fontSize = 8.sp) }, colors = FilterChipDefaults.filterChipColors(containerColor = CardBg, selectedContainerColor = AccentCyan.copy(alpha = 0.15f), labelColor = TextMuted, selectedLabelColor = AccentCyan), border = FilterChipDefaults.filterChipBorder(borderColor = BorderSub, selectedBorderColor = AccentCyan.copy(alpha = 0.4f), enabled = true, selected = editExpiry == v), shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(end = 4.dp)) } }
                            }
                        },
                        confirmButton = { Button(onClick = { onEdit(a, editText, editExpiry); showEditDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) { Text("Save", color = Color.White) } },
                        dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Cancel", color = TextMuted) } },
                        containerColor = CardBg, shape = RoundedCornerShape(16.dp)
                    )
                }
                // Delete confirmation
                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text("Delete announcement?", color = TextWhite, fontWeight = FontWeight.Bold) },
                        text = { Text("This will hide it from all institutes. It can be recovered within 90 days.", color = TextMuted, fontSize = 13.sp) },
                        confirmButton = { Button(onClick = { onDelete(a); showDeleteConfirm = false }, colors = ButtonDefaults.buttonColors(containerColor = AccentRed)) { Text("Delete", color = Color.White) } },
                        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = TextMuted) } },
                        containerColor = CardBg, shape = RoundedCornerShape(16.dp)
                    )
                }
            }
        }

        if (archivedAnnouncements.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Archived announcements · ${archivedAnnouncements.size}", color = AccentAmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { showArchived = !showArchived }) {
                    Text(if (showArchived) "Hide" else "View", color = AccentAmber, fontSize = 11.sp)
                }
            }
            if (showArchived) {
                archivedAnnouncements.forEach { archived ->
                    Card(
                        Modifier.fillMaxWidth().clickable { selectedArchived = archived }.padding(bottom = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.3f))
                    ) {
                        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(AccentAmber.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Archive, null, tint = AccentAmber, modifier = Modifier.size(15.dp))
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(archived.message, color = TextWhite, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("Archived · tap to view or restore", color = TextMuted, fontSize = 10.sp)
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        selectedArchived?.let { archived ->
            AlertDialog(
                onDismissRequest = { selectedArchived = null },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Archive, null, tint = AccentAmber, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Archived announcement", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = RoundedCornerShape(10.dp), color = BorderSub.copy(alpha = 0.45f)) {
                            Text(archived.message, color = TextWhite, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(12.dp))
                        }
                        Text("Restore makes this message visible to institutes again.", color = TextMuted, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    Button(onClick = { onRestore(archived); selectedArchived = null }, colors = ButtonDefaults.buttonColors(containerColor = AccentGreen), shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Filled.Restore, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Restore", color = Color.Black)
                    }
                },
                dismissButton = { TextButton(onClick = { selectedArchived = null }) { Text("Close", color = TextMuted) } },
                containerColor = CardBg,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

