package com.batchfee.edu.ui.superadmin

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firebase.FirebaseAuthApi
import com.batchfee.edu.data.firestore.AppUserSyncHelper
import com.batchfee.edu.data.firestore.ManagedUserRecord
import com.batchfee.edu.data.firestore.SubscriptionPlanSyncHelper
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.SubscriptionPlanEntity
import com.batchfee.edu.data.models.SubscriptionRequest
import com.batchfee.edu.data.models.UserEntity
import com.batchfee.edu.domain.PasswordHasher
import com.batchfee.edu.domain.SessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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

private fun humanizePlanId(planId: String): String = planId
    .removePrefix("plan_")
    .replace('_', ' ')
    .split(' ')
    .filter { it.isNotBlank() }
    .joinToString(" ") { token -> token.replaceFirstChar { it.uppercase() } }

private fun planDisplayName(planId: String, plans: List<SubscriptionPlanEntity>): String =
    plans.firstOrNull { it.id == planId }?.name ?: humanizePlanId(planId)

private fun planDisplayPrice(planId: String, plans: List<SubscriptionPlanEntity>): Double =
    plans.firstOrNull { it.id == planId }?.priceBdt ?: STANDARD_MONTHLY_FEE

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

// ── ViewModel ─────────────────────────────────────────────────
data class SuperAdminStats(
    val totalInstitutes: Int = 0,
    val activeSubscriptions: Int = 0,
    val totalRevenue: Double = 0.0,
    val totalStudents: Int = 0,
    val totalStaff: Int = 0
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
    val endDateMs: Long
)

class SuperAdminViewModel(private val db: AppDatabase) : ViewModel() {
    private val _institutes = MutableStateFlow<List<InstituteCardData>>(emptyList())
    val institutes = _institutes.asStateFlow()

    private val _subscriptionPlans = MutableStateFlow<List<SubscriptionPlanEntity>>(emptyList())
    val subscriptionPlans = _subscriptionPlans.asStateFlow()

    private val _stats = MutableStateFlow(SuperAdminStats())
    val stats = _stats.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _operationMsg = MutableStateFlow<String?>(null)
    val operationMsg = _operationMsg.asStateFlow()

    private val _shareReceiptEvent = MutableStateFlow<Pair<Bitmap, String>?>(null)
    val shareReceiptEvent = _shareReceiptEvent.asStateFlow()

    private val _receiptData = MutableStateFlow<SubscriptionReceiptData?>(null)
    val receiptData = _receiptData.asStateFlow()

    private val _lastActiveMap = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastActiveMap = _lastActiveMap.asStateFlow()

    private val _pendingRequests = MutableStateFlow<List<SubscriptionRequest>>(emptyList())
    val pendingRequests = _pendingRequests.asStateFlow()

    private val _managedUsers = MutableStateFlow<List<ManagedUserSummary>>(emptyList())
    val managedUsers = _managedUsers.asStateFlow()

    private val firestore = FirebaseFirestore.getInstance()
    private var didBackfillManagedUsers = false

    val projectedRevenue: Double
        get() = _stats.value.totalRevenue

    init {
        loadSubscriptionPlans()
        loadInstitutesRealtime()
        loadPendingRequestsRealtime()
        loadManagedUsersRealtime()
    }

    fun clearOperationMsg() { _operationMsg.value = null }

    private fun loadSubscriptionPlans() {
        viewModelScope.launch {
            db.subscriptionPlanDao().getAllPlans().collectLatest { plans ->
                _subscriptionPlans.value = plans
                recalculateStats(_institutes.value, plans)
            }
        }
    }

    private fun recalculateStats(
        institutes: List<InstituteCardData>,
        plans: List<SubscriptionPlanEntity> = _subscriptionPlans.value
    ) {
        val now = System.currentTimeMillis()
        val planPriceMap = plans.associate { it.id to it.priceBdt }
        val activeCount = institutes.count { card ->
            val entity = card.entity
            val isExpired = entity.subscriptionStatus == "expired" || entity.subscriptionStatus == "blocked"
            !isExpired && entity.trialEndDateMs > now
        }
        val totalRevenue = institutes.sumOf { card ->
            val entity = card.entity
            if (entity.subscriptionStatus == "blocked" ||
                entity.subscriptionStatus == "expired" ||
                entity.subscriptionStatus == "trashed" ||
                entity.trialEndDateMs <= now
            ) {
                0.0
            } else {
                planPriceMap[entity.currentPlanId] ?: 0.0
            }
        }
        _stats.value = SuperAdminStats(
            totalInstitutes = institutes.size,
            activeSubscriptions = activeCount,
            totalRevenue = totalRevenue,
            totalStudents = institutes.sumOf { it.studentCount },
            totalStaff = institutes.sumOf { it.staffCount }
        )
    }

    private fun loadPendingRequestsRealtime() {
        firestore.collection("subscriptionRequests")
            .whereEqualTo("status", "pending")
            .orderBy("requestSentAt")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                _pendingRequests.value = snapshot.documents.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    SubscriptionRequest.fromFirestore(doc.id, data)
                }
            }
    }

    fun approveRequest(request: SubscriptionRequest) {
        viewModelScope.launch {
            try {
                android.util.Log.d("SUPERADMIN", "approveRequest: instituteId=${request.instituteId}, requestId=${request.requestId}, planId=${request.requestedPlanId}, durationMonths=${request.durationMonths}")
                val approvedAt = System.currentTimeMillis()
                val newEnd = withContext(Dispatchers.IO) {
                    val repo = com.batchfee.edu.data.repository.SubscriptionRepository(firestore)
                    val reviewerUserId = SessionManager.currentUserId.value ?: "sys_super_admin_1"
                    android.util.Log.d("SUPERADMIN", "approveRequest DBG: approving subscriptionRequests/${request.requestId}")
                    repo.approveRequest(request.requestId, reviewerUserId)
                    val end = approvedAt + (request.durationMonths * 30L * 24 * 60 * 60 * 1000)
                    android.util.Log.d("SUPERADMIN", "approveRequest DBG: updating institutes/${request.instituteId} → plan=${request.requestedPlanId}, end=$end")
                    firestore.collection("institutes").document(request.instituteId)
                        .update(
                            mapOf(
                                "currentPlanId" to request.requestedPlanId,
                                "trialEndDate" to end,
                                "currentPeriodEndMs" to end,
                                "subscriptionStatus" to "active",
                                "isActive" to true
                            )
                        )
                        .await()
                    db.instituteDao().getInstitute(request.instituteId)?.let { current ->
                        db.instituteDao().insertInstitute(
                            current.copy(
                                currentPlanId = request.requestedPlanId,
                                subscriptionStatus = "active",
                                trialEndDateMs = end,
                                currentPeriodEndMs = end
                            )
                        )
                    }
                    end
                }
                _pendingRequests.value = _pendingRequests.value.filterNot { it.requestId == request.requestId }
                _institutes.value = _institutes.value.map { card ->
                    if (card.entity.id != request.instituteId) return@map card
                    card.copy(
                        entity = card.entity.copy(
                            currentPlanId = request.requestedPlanId,
                            subscriptionStatus = "active",
                            trialEndDateMs = newEnd,
                            currentPeriodEndMs = newEnd
                        )
                    )
                }
                _operationMsg.value = "Approved ${request.instituteName} — ${request.requestedPlanId}"
                // Store receipt data for manual share
                val planName = planDisplayName(request.requestedPlanId, _subscriptionPlans.value)
                val instDoc = withContext(Dispatchers.IO) {
                    firestore.collection("institutes").document(request.instituteId).get().await()
                }
                val instData = instDoc.data ?: emptyMap()
                _receiptData.value = SubscriptionReceiptData(
                    receiptNumber = "SUB-${System.currentTimeMillis()}",
                    instituteName = request.instituteName,
                    ownerName = request.ownerName,
                    ownerPhone = request.institutePhone ?: "",
                    ownerEmail = instData["email"] as? String ?: "",
                    instituteCode = instData["instituteCode"] as? String ?: "",
                    instituteAddress = instData["address"] as? String ?: "",
                    planName = planName,
                    durationMonths = request.durationMonths,
                    amountPaid = request.amountPaid,
                    paymentMethod = request.paymentMethod,
                    transactionLast4 = request.transactionLast4,
                    startDateMs = System.currentTimeMillis(),
                    endDateMs = newEnd
                )
            } catch (e: Exception) {
                _operationMsg.value = "Approve failed: ${e.message}"
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    fun clearReceipt() { _receiptData.value = null }

    fun consumeShareEvent() { _shareReceiptEvent.value = null }

    fun rejectRequest(request: SubscriptionRequest, note: String? = null) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val repo = com.batchfee.edu.data.repository.SubscriptionRepository(firestore)
                    val reviewerUserId = SessionManager.currentUserId.value ?: "sys_super_admin_1"
                    repo.rejectRequest(request.requestId, reviewerUserId, note)
                }
                _pendingRequests.value = _pendingRequests.value.filterNot { it.requestId == request.requestId }
                _operationMsg.value = "Rejected ${request.instituteName}"
            } catch (e: Exception) {
                _operationMsg.value = "Reject failed: ${e.message}"
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun loadInstitutesRealtime() {
        firestore.collection("institutes")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    _isLoading.value = false
                    return@addSnapshotListener
                }

                val list = mutableListOf<InstituteCardData>()
                val activeMap = mutableMapOf<String, Long>()
                var activeCount = 0
                val now = System.currentTimeMillis()
                val trialWindow = 15L * 24 * 60 * 60 * 1000

                snapshot.documents.forEach { doc ->
                    val data = doc.data ?: return@forEach
                    val uid = doc.id

                    val isActive = data["isActive"] as? Boolean ?: true
                    val trialEnd = data["trialEndDate"] as? Long ?: now
                    val createdAt = data["createdAt"] as? Long ?: now
                    val lastActive = data["lastActiveAt"] as? Long
                    val studentCount = (data["studentCount"] as? Long)?.toInt() ?: 0
                    val staffCount = (data["staffCount"] as? Long)?.toInt() ?: 0
                    val batchCount = (data["batchCount"] as? Long)?.toInt() ?: 0

                    if (lastActive != null) activeMap[uid] = lastActive

                    val currentPlanId = data["currentPlanId"] as? String ?: DEFAULT_TRIAL_PLAN_ID
                    val storedStatus = data["subscriptionStatus"] as? String
                    val status = when {
                        !isActive -> "blocked"
                        trialEnd < now -> "expired"
                        storedStatus == "active" -> "active"
                        storedStatus == "trial" -> "trial"
                        currentPlanId == "plan_free_trial" && (now - createdAt) < trialWindow -> "trial"
                        else -> "active"
                    }

                    if (isActive && trialEnd > now) activeCount++

                    list.add(
                        InstituteCardData(
                            entity = InstituteEntity(
                                id = uid,
                                name = data["instituteName"] as? String ?: "Institute",
                                currentPlanId = currentPlanId,
                                subscriptionStatus = status,
                                trialStartDateMs = createdAt,
                                trialEndDateMs = trialEnd,
                                currentPeriodEndMs = trialEnd,
                                createdAtMs = createdAt,
                                phone = data["phone"] as? String,
                                whatsappNumber = data["whatsappNumber"] as? String,
                                profilePhotoUri = data["profilePhotoUri"] as? String,
                                ownerName = data["ownerName"] as? String,
                                email = data["email"] as? String,
                                instituteCode = data["instituteCode"] as? String,
                                securityPin = data["securityPin"] as? String
                            ),
                            studentCount = studentCount,
                            staffCount = staffCount,
                            batchCount = batchCount
                        )
                    )
                }

                _institutes.value = list
                _lastActiveMap.value = activeMap
                recalculateStats(list)
                _isLoading.value = false
                if (!didBackfillManagedUsers) {
                    didBackfillManagedUsers = true
                    viewModelScope.launch {
                        backfillManagedUsersFromInstitutes(snapshot.documents.mapNotNull { doc ->
                            val data = doc.data ?: return@mapNotNull null
                            val role = data["role"] as? String ?: return@mapNotNull null
                            val mappedRole = when (role) {
                                "owner" -> "InstituteOwner"
                                "admin", "InstituteAdmin", "instituteAdmin", "institute_admin" -> "InstituteAdmin"
                                "SuperAdmin", "superAdmin", "super_admin" -> "SuperAdmin"
                                else -> null
                            } ?: return@mapNotNull null
                            val email = data["email"] as? String ?: return@mapNotNull null
                            ManagedUserRecord(
                                id = doc.id,
                                name = (data["ownerName"] as? String)
                                    ?: (data["instituteName"] as? String)
                                    ?: email.substringBefore("@"),
                                email = email,
                                role = mappedRole,
                                instituteId = if (mappedRole == "SuperAdmin") null else (data["instituteId"] as? String ?: doc.id),
                                createdAtMs = (data["createdAt"] as? Long) ?: System.currentTimeMillis(),
                                status = if ((data["isActive"] as? Boolean) == false) "blocked" else "active"
                            )
                        })
                    }
                }
            }
    }

    private suspend fun backfillManagedUsersFromInstitutes(records: List<ManagedUserRecord>) {
        records.forEach { record ->
            try {
                AppUserSyncHelper.upsertManagedUser(record)
                db.userDao().insertUser(
                    UserEntity(
                        id = record.id,
                        instituteId = record.instituteId,
                        name = record.name,
                        email = record.email,
                        passwordHash = "",
                        role = record.role,
                        createdAtMs = record.createdAtMs
                    )
                )
            } catch (_: Exception) { }
        }
    }

    private fun loadManagedUsersRealtime() {
        firestore.collection("app_users")
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
        val cleanPassword = password.trim()
        val cleanInstituteId = instituteId?.trim()?.takeIf { it.isNotEmpty() }
        when {
            cleanName.isBlank() -> _operationMsg.value = "Name is required."
            cleanEmail.isBlank() -> _operationMsg.value = "Email is required."
            cleanPassword.length < 6 -> _operationMsg.value = "Password must be at least 6 characters."
            role != "SuperAdmin" && cleanInstituteId == null -> _operationMsg.value = "Select an institute for this role."
            else -> viewModelScope.launch {
                try {
                    val uid = FirebaseAuthApi.createUser(cleanEmail, cleanPassword)
                    val now = System.currentTimeMillis()
                    val record = ManagedUserRecord(
                        id = uid,
                        name = cleanName,
                        email = cleanEmail,
                        role = role,
                        instituteId = cleanInstituteId,
                        createdAtMs = now,
                        status = "active"
                    )
                    AppUserSyncHelper.upsertManagedUser(record)
                    db.userDao().insertUser(
                        UserEntity(
                            id = uid,
                            instituteId = cleanInstituteId,
                            name = cleanName,
                            email = cleanEmail,
                            passwordHash = PasswordHasher.hash(cleanPassword),
                            role = role,
                            createdAtMs = now
                        )
                    )
                    if (role == "InstituteOwner" && cleanInstituteId != null) {
                        firestore.collection("institutes").document(cleanInstituteId)
                            .update(mapOf("ownerName" to cleanName, "email" to cleanEmail))
                            .await()
                    }
                    _operationMsg.value = "$role account created for $cleanEmail"
                } catch (e: FirebaseAuthApi.SignUpException) {
                    _operationMsg.value = e.firebaseMessage
                } catch (e: Exception) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                    _operationMsg.value = "User create failed: ${e.message}"
                }
            }
        }
    }

    fun updateManagedUser(
        existing: ManagedUserSummary,
        name: String,
        role: String,
        instituteId: String?,
        password: String
    ) {
        val cleanName = name.trim()
        val cleanInstituteId = instituteId?.trim()?.takeIf { it.isNotEmpty() }
        val requestedPassword = password.trim()
        when {
            cleanName.isBlank() -> _operationMsg.value = "Name is required."
            role != "SuperAdmin" && cleanInstituteId == null -> _operationMsg.value = "Select an institute for this role."
            else -> viewModelScope.launch {
                try {
                    val updated = ManagedUserRecord(
                        id = existing.id,
                        name = cleanName,
                        email = existing.email,
                        role = role,
                        instituteId = cleanInstituteId,
                        createdAtMs = existing.createdAtMs,
                        status = existing.status
                    )
                    AppUserSyncHelper.upsertManagedUser(updated)
                    val currentLocal = db.userDao().getUserById(existing.id)
                    if (currentLocal == null) {
                        db.userDao().insertUser(
                            UserEntity(
                                id = existing.id,
                                instituteId = cleanInstituteId,
                                name = cleanName,
                                email = existing.email,
                                passwordHash = "",
                                role = role,
                                createdAtMs = existing.createdAtMs
                            )
                        )
                    } else {
                        db.userDao().updateUser(
                            currentLocal.copy(
                                instituteId = cleanInstituteId,
                                name = cleanName,
                                role = role
                            )
                        )
                    }
                    if (role == "InstituteOwner" && cleanInstituteId != null) {
                        firestore.collection("institutes").document(cleanInstituteId)
                            .update(mapOf("ownerName" to cleanName, "email" to existing.email))
                            .await()
                    }
                    if (requestedPassword.isNotBlank()) {
                        FirebaseAuth.getInstance().sendPasswordResetEmail(existing.email).await()
                        _operationMsg.value = "User updated. Password reset email sent to ${existing.email}"
                    } else {
                        _operationMsg.value = "User updated successfully."
                    }
                } catch (e: Exception) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                    _operationMsg.value = "User update failed: ${e.message}"
                }
            }
        }
    }

    fun extendSubscription(instituteId: String, daysToAdd: Int) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val docRef = firestore.collection("institutes").document(instituteId)
                    val snapshot = docRef.get().await()
                    val currentEnd = snapshot.getLong("trialEndDate") ?: System.currentTimeMillis()
                    val newEnd = currentEnd + (daysToAdd * 24L * 60 * 60 * 1000)
                    docRef.update("trialEndDate", newEnd).await()
                }
                _operationMsg.value = "Subscription extended by $daysToAdd days"
            } catch (e: Exception) {
                _operationMsg.value = "Failed: ${e.message}"
            }
        }
    }

    fun toggleBlock(instituteId: String, currentBlocked: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    firestore.collection("institutes").document(instituteId)
                        .update("isActive", !currentBlocked).await()
                }
                _operationMsg.value = if (currentBlocked) "Institute unblocked" else "Institute blocked"
            } catch (e: Exception) {
                _operationMsg.value = "Failed: ${e.message}"
            }
        }
    }

    // ── Trash / Remove Institute ─────────────────────────────
    private val _trashedInstitutes = MutableStateFlow<List<InstituteCardData>>(emptyList())
    val trashedInstitutes = _trashedInstitutes.asStateFlow()

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
                val trashedAt = System.currentTimeMillis()
                val expiresAt = trashedAt + 10L * 24 * 60 * 60 * 1000 // 10 days
                withContext(Dispatchers.IO) {
                    // Mark institute as deleted in Firestore (move to trash subcollection)
                    val instDoc = firestore.collection("institutes").document(instituteId).get().await()
                    val instData = (instDoc.data ?: emptyMap()).toMutableMap()
                    instData["_trashedAt"] = trashedAt
                    instData["_trashExpiresAt"] = expiresAt
                    instData["_trashedBy"] = FirebaseAuth.getInstance().currentUser?.uid
                    instData["isActive"] = false
                    instData["subscriptionStatus"] = "trashed"
                    firestore.collection("institutes_trash").document(instituteId).set(instData).await()
                    firestore.collection("institutes").document(instituteId).delete().await()
                    // Move app_user to trash
                    try {
                        val appUserDoc = firestore.collection("app_users").document(instituteId).get().await()
                        if (appUserDoc.exists()) {
                            val userData = appUserDoc.data?.toMutableMap() ?: mutableMapOf()
                            userData["_trashedAt"] = trashedAt
                            firestore.collection("app_users_trash").document(instituteId).set(userData).await()
                            firestore.collection("app_users").document(instituteId).delete().await()
                        }
                    } catch (_: Exception) { }
                }
                _trashedInstitutes.value = _trashedInstitutes.value + listOf(card.copy(
                    entity = card.entity.copy(
                        subscriptionStatus = "trashed",
                        trialEndDateMs = trashedAt,
                        currentPeriodEndMs = expiresAt
                    )
                ))
                _institutes.value = _institutes.value.filter { it.entity.id != instituteId }
                _operationMsg.value = "${card.entity.name} moved to trash. Auto-delete in 10 days."
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
                withContext(Dispatchers.IO) {
                    val trashDoc = firestore.collection("institutes_trash").document(instituteId).get().await()
                    val data = (trashDoc.data ?: emptyMap()).toMutableMap()
                    data.remove("_trashedAt")
                    data.remove("_trashExpiresAt")
                    data.remove("_trashedBy")
                    data["isActive"] = true
                    data["subscriptionStatus"] = "active"
                    firestore.collection("institutes").document(instituteId).set(data).await()
                    firestore.collection("institutes_trash").document(instituteId).delete().await()
                    // Restore app_user
                    try {
                        val trashUserDoc = firestore.collection("app_users_trash").document(instituteId).get().await()
                        if (trashUserDoc.exists()) {
                            val userData = trashUserDoc.data?.toMutableMap() ?: mutableMapOf()
                            userData.remove("_trashedAt")
                            firestore.collection("app_users").document(instituteId).set(userData).await()
                            firestore.collection("app_users_trash").document(instituteId).delete().await()
                        }
                    } catch (_: Exception) { }
                }
                _trashedInstitutes.value = _trashedInstitutes.value.filter { it.entity.id != instituteId }
                _institutes.value = _institutes.value + listOf(card.copy(
                    entity = card.entity.copy(
                        subscriptionStatus = "active",
                        trialEndDateMs = card.entity.currentPeriodEndMs
                    )
                ))
                _operationMsg.value = "${card.entity.name} restored."
            } catch (e: Exception) {
                _operationMsg.value = "Failed: ${e.message}"
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    fun permanentlyDelete(instituteId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    firestore.collection("institutes_trash").document(instituteId).delete().await()
                    try { firestore.collection("app_users_trash").document(instituteId).delete().await() } catch (_: Exception) { }
                    // Clean all orphan subcollections (parent doc was already moved to trash)
                    val subCollections = listOf(
                        "batches", "students", "fees", "payments", "receipts",
                        "attendance", "exams", "staffs", "enquiries", "registrations"
                    )
                    for (col in subCollections) {
                        try {
                            val docs = firestore.collection("institutes").document(instituteId)
                                .collection(col).get().await().documents
                            for (doc in docs) {
                                // Also clean nested subcollections for exams (results)
                                if (col == "exams") {
                                    try {
                                        firestore.collection("institutes").document(instituteId)
                                            .collection("exams").document(doc.id)
                                            .collection("results").get().await()
                                            .documents.forEach { it.reference.delete().await() }
                                    } catch (_: Exception) { }
                                }
                                doc.reference.delete().await()
                            }
                        } catch (_: Exception) { }
                    }
                }
                _trashedInstitutes.value = _trashedInstitutes.value.filter { it.entity.id != instituteId }
                _operationMsg.value = "Institute permanently deleted."
            } catch (e: Exception) {
                _operationMsg.value = "Failed: ${e.message}"
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    fun clearExpiredTrash() {
        val now = System.currentTimeMillis()
        val expired = _trashedInstitutes.value.filter { it.entity.currentPeriodEndMs < now }
        expired.forEach { permanentlyDelete(it.entity.id) }
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
                withContext(Dispatchers.IO) {
                    firestore.collection("institutes").document(instituteId).update(
                        mapOf(
                            "trialEndDate" to newExpiryMs,
                            "studentLimit" to studentLimit,
                            "staffLimit" to staffLimit,
                            "currentPlanId" to planId,
                            "isActive" to isActive
                        )
                    ).await()
                }
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

                withContext(Dispatchers.IO) {
                    if (existingPlanId != null && existingPlanId != plan.id) {
                        val usingPlan = db.instituteDao().getAllInstitutes().first().filter { it.currentPlanId == existingPlanId }
                        usingPlan.forEach { institute ->
                            val updated = institute.copy(currentPlanId = plan.id)
                            db.instituteDao().insertInstitute(updated)
                            firestore.collection("institutes").document(institute.id)
                                .update("currentPlanId", plan.id)
                                .await()
                        }
                        SubscriptionPlanSyncHelper.deletePlan(existingPlanId)
                        db.subscriptionPlanDao().deletePlanById(existingPlanId)
                    }
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

    fun broadcastAnnouncement(message: String) {
        if (message.isBlank()) {
            _operationMsg.value = "Message cannot be empty."
            return
        }
        viewModelScope.launch {
            try {
                val id = UUID.randomUUID().toString()
                val data = mapOf(
                    "id" to id,
                    "message" to message.trim(),
                    "sentAt" to System.currentTimeMillis(),
                    "sender" to "SuperAdmin",
                    "platform" to "android"
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

    fun loadInstituteStaff(instituteId: String, onResult: (List<InstituteStaffSummary>) -> Unit) {
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    firestore.collection("institutes").document(instituteId)
                        .collection("staffs")
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

    fun sendPasswordReset(email: String?) {
        if (email.isNullOrBlank()) {
            _operationMsg.value = "No email on file for this institute."
            return
        }
        viewModelScope.launch {
            try {
                com.google.android.gms.tasks.Tasks.await(
                    FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                )
                _operationMsg.value = "Password reset email sent to $email"
            } catch (e: Exception) {
                _operationMsg.value = "Failed: ${e.message}"
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
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
    val managedUsers by viewModel.managedUsers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val operationMsg by viewModel.operationMsg.collectAsState()
    val receiptData by viewModel.receiptData.collectAsState()
    val projected = viewModel.projectedRevenue

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(operationMsg) {
        operationMsg?.let { snackbarHostState.showSnackbar(it); viewModel.clearOperationMsg() }
    }

    var announceText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") }
    var userSearchQuery by remember { mutableStateOf("") }
    var userRoleFilter by remember { mutableStateOf("all") }
    var showUserDialog by remember { mutableStateOf(false) }
    var editingUser by remember { mutableStateOf<ManagedUserSummary?>(null) }
    var editingPlan by remember { mutableStateOf<SubscriptionPlanEntity?>(null) }
    var showPlanDialog by remember { mutableStateOf(false) }
    var showReceiptDialog by remember { mutableStateOf(false) }

    val planNameById = remember(subscriptionPlans) { subscriptionPlans.associate { it.id to it.name } }

    val filteredInstitutes = remember(institutes, searchQuery, statusFilter) {
        institutes.filter { card ->
            val inst = card.entity
            val matchesSearch = searchQuery.isBlank() ||
                inst.name.contains(searchQuery, ignoreCase = true) ||
                (inst.instituteCode?.contains(searchQuery, ignoreCase = true) ?: false) ||
                (inst.ownerName?.contains(searchQuery, ignoreCase = true) ?: false) ||
                (inst.phone?.contains(searchQuery) ?: false) ||
                (inst.email?.contains(searchQuery, ignoreCase = true) ?: false)
            val matchesFilter = statusFilter == "all" || inst.subscriptionStatus == statusFilter
            matchesSearch && matchesFilter
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
                            .background(Brush.horizontalGradient(listOf(AccentViolet, ElectricBlue))),
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
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Registered\nInstitutes", if (isLoading) "..." else stats.totalInstitutes.toString(), AccentCyan, Icons.Filled.Business, Modifier.weight(1f))
                    StatCard("Active\nSubscriptions", if (isLoading) "..." else stats.activeSubscriptions.toString(), AccentGreen, Icons.Filled.Verified, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Total\nStudents", if (isLoading) "..." else stats.totalStudents.toString(), AccentViolet, Icons.Filled.People, Modifier.weight(1f))
                    StatCard("Total\nStaff", if (isLoading) "..." else stats.totalStaff.toString(), AccentPink, Icons.Filled.Badge, Modifier.weight(1f))
                }
            }

            // ── Total Revenue ──
            item {
                RevenueCard("Total Revenue",
                    if (isLoading) "..." else "BDT ${NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 0 }.format(stats.totalRevenue)}",
                    AccentAmber, Icons.Filled.TrendingUp
                )
            }

            // ── Projected Revenue (Prediction) ──
            item {
                ProjectedRevenueCard(projected, stats.activeSubscriptions)
            }

            // ── Live trend bars ──
            item {
                val pulseAnim = rememberInfiniteTransition()
                val bar1 by pulseAnim.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse))
                val bar2 by pulseAnim.animateFloat(0.3f, 0.85f, infiniteRepeatable(tween(1000), RepeatMode.Reverse))
                val bar3 by pulseAnim.animateFloat(0.5f, 0.95f, infiniteRepeatable(tween(1400), RepeatMode.Reverse))
                val bar4 by pulseAnim.animateFloat(0.2f, 0.7f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
                    Row(Modifier.fillMaxWidth().height(80.dp).padding(16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(12) { i ->
                            val f = listOf(bar1, bar2, bar3, bar4, bar1, bar2, bar3, bar4, bar1, bar2, bar3, bar4)[i]
                            Box(Modifier.weight(1f).fillMaxHeight(f).clip(RoundedCornerShape(3.dp)).background(Brush.verticalGradient(listOf(AccentCyan, ElectricBlue))))
                        }
                    }
                }
            }

            // ── Global Broadcast ──
            item {
                Text("System Broadcast", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AccentPink.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Campaign, null, tint = AccentPink, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("Global Notification", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = announceText, onValueChange = { announceText = it },
                            placeholder = { Text("Announcement for all institutes...", color = TextMuted) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
                                focusedBorderColor = AccentPink, unfocusedBorderColor = BorderSub,
                                focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                                cursorColor = AccentPink
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                viewModel.broadcastAnnouncement(announceText)
                                announceText = ""
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            enabled = announceText.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPink, disabledContainerColor = BorderSub)
                        ) {
                            Icon(Icons.Filled.Send, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Send Announcement", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (announceText.isNotBlank()) Color.White else TextMuted)
                        }
                    }
                }
            }

            // ── Pending Requests ──
            if (pendingRequests.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Pending Requests · ${pendingRequests.size}", color = AccentAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                items(pendingRequests) { req ->
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
                            Text("${req.requestedPlanId} · ${req.durationMonths} Month(s) · BDT ${"%.0f".format(req.amountPaid)}", color = TextMuted, fontSize = 12.sp)
                            Text("${req.paymentMethod} · TrxID: ***${req.transactionLast4} · ${req.ownerName}", color = TextMuted, fontSize = 11.sp)
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
                                Button(onClick = {
                                    viewModel.approveRequest(req)
                                    showReceiptDialog = true
                                },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                                ) { Text("Approve", fontSize = 12.sp, color = Color.Black) }
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
                    if (searchQuery.isNotBlank() || statusFilter != "all") {
                        TextButton(onClick = { searchQuery = ""; statusFilter = "all" }) {
                            Text("Clear", color = AccentCyan, fontSize = 12.sp)
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

            if (filteredInstitutes.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Text(if (searchQuery.isNotBlank() || statusFilter != "all") "No institutes match your filters." else "No institutes registered yet.",
                            color = TextMuted, fontSize = 14.sp)
                    }
                }
            } else {
                items(filteredInstitutes, key = { it.entity.id }) { card ->
                    InstituteCard(card, viewModel, subscriptionPlans)
                }
            }

            // ── Trash Section ──
            if (trashedInstitutes.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Trash · ${trashedInstitutes.size}", color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        LaunchedEffect(Unit) { viewModel.clearExpiredTrash() }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                items(trashedInstitutes, key = { it.entity.id }) { card ->
                    val inst = card.entity
                    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.3f))
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AccentRed.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Delete, null, tint = AccentRed, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(inst.name, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Auto-delete: ${dateFormat.format(Date(inst.currentPeriodEndMs))}", color = AccentRed.copy(alpha = 0.7f), fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.restoreInstitute(inst.id) },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = ButtonDefaults.outlinedButtonBorder,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                                ) {
                                    Icon(Icons.Filled.Restore, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Restore", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.permanentlyDelete(inst.id) },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = ButtonDefaults.outlinedButtonBorder,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                                ) {
                                    Icon(Icons.Filled.DeleteForever, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Delete Forever", fontSize = 12.sp)
                                }
                            }
                        }
                    }
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

            item { Spacer(Modifier.height(80.dp)) }
        }
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

    // ── Receipt Dialog ──
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
                                Text("${r.planName} · ${r.durationMonths} Month(s)", color = AccentCyan, fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Text("BDT ${"%,.0f".format(r.amountPaid)}", color = AccentGreen, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(2.dp))
                                Text("${r.paymentMethod.uppercase()} · Trx: ***${r.transactionLast4}", color = TextMuted, fontSize = 11.sp)
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
                            try {
                                val file = generateSubscriptionReceiptPdf(context, r)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val phone = r.ownerPhone.replace("+", "").replace(" ", "").replace("-", "")
                                var handled = false
                                val waIntent = Intent(Intent.ACTION_SEND)
                                waIntent.type = "application/pdf"
                                waIntent.putExtra(Intent.EXTRA_STREAM, uri)
                                waIntent.putExtra(Intent.EXTRA_TEXT, "Subscription Receipt - ${r.instituteName}")
                                waIntent.setPackage("com.whatsapp")
                                if (waIntent.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(waIntent)
                                    handled = true
                                }
                                if (!handled) {
                                    Toast.makeText(context, "WhatsApp not installed. Use Print.", Toast.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) { }
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
                            try {
                                val file = generateSubscriptionReceiptPdf(context, r)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/pdf")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            } catch (_: Exception) { }
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
private fun SubscriptionPlanSection(
    plans: List<SubscriptionPlanEntity>,
    institutes: List<InstituteCardData>,
    onCreate: () -> Unit,
    onEdit: (SubscriptionPlanEntity) -> Unit,
    onDelete: (String) -> Unit
) {
    val planUsage = remember(institutes) { institutes.groupingBy { it.entity.currentPlanId }.eachCount() }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Subscription Plans", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("New Plan", color = AccentCyan, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (plans.isEmpty()) {
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("No subscription plans found yet.", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                plans.forEach { plan ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, BorderSub)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(plan.name, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text(plan.id, color = TextMuted, fontSize = 11.sp)
                                }
                                if (plan.tag.isNotBlank()) {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(8.dp))
                                            .background(AccentViolet.copy(alpha = 0.15f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(plan.tag, color = AccentViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(plan.description, color = TextMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PlanMetricChip("BDT ${formatMoneyValue(plan.priceBdt)}")
                                PlanMetricChip("${plan.maxStudents} students")
                                PlanMetricChip("${plan.maxUsers} users")
                                PlanMetricChip("${planUsage[plan.id] ?: 0} institutes")
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { onEdit(plan) }) {
                                    Text("Edit", color = AccentCyan, fontWeight = FontWeight.Bold)
                                }
                                TextButton(
                                    onClick = { onDelete(plan.id) },
                                    enabled = plan.id != DEFAULT_TRIAL_PLAN_ID && (planUsage[plan.id] ?: 0) == 0
                                ) {
                                    Text("Delete", color = AccentRed, fontWeight = FontWeight.Bold)
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
private fun PlanMetricChip(label: String) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp))
            .background(AccentCyan.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.Medium)
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
    var maxBatches by remember(initialPlan) { mutableStateOf(initialPlan?.maxBatches?.toString() ?: "10") }
    var maxUsers by remember(initialPlan) { mutableStateOf(initialPlan?.maxUsers?.toString() ?: "3") }
    var maxBranches by remember(initialPlan) { mutableStateOf(initialPlan?.maxBranches?.toString() ?: "1") }
    var tag by remember(initialPlan) { mutableStateOf(initialPlan?.tag ?: "") }
    var tierLevel by remember(initialPlan) { mutableStateOf(initialPlan?.tierLevel?.toString() ?: "1") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialPlan == null) "Create Subscription Plan" else "Edit Subscription Plan", color = TextWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                validationError?.let { Text(it, color = AccentRed, fontSize = 12.sp) }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (initialPlan == null) {
                            planId = "plan_${slugifyPlanName(it)}"
                        }
                    },
                    label = { Text("Plan name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = planId,
                    onValueChange = {
                        val slug = slugifyPlanName(it)
                        planId = if (slug.startsWith("plan_")) slug else "plan_$slug"
                    },
                    label = { Text("Plan ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = priceBdt,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) priceBdt = it },
                        label = { Text("BDT") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = priceInr,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) priceInr = it },
                        label = { Text("INR") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxStudents,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) maxStudents = it },
                        label = { Text("Students") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxBatches,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) maxBatches = it },
                        label = { Text("Batches") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxUsers,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) maxUsers = it },
                        label = { Text("Users") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxBranches,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) maxBranches = it },
                        label = { Text("Branches") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tag,
                        onValueChange = { tag = it },
                        label = { Text("Tag") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tierLevel,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) tierLevel = it },
                        label = { Text("Tier") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalizedId = planId.trim().ifBlank { "plan_${slugifyPlanName(name)}" }
                val parsedPriceBdt = priceBdt.toDoubleOrNull()
                val parsedPriceInr = priceInr.toDoubleOrNull() ?: 0.0
                val parsedMaxStudents = maxStudents.toIntOrNull()
                val parsedMaxBatches = maxBatches.toIntOrNull()
                val parsedMaxUsers = maxUsers.toIntOrNull()
                val parsedMaxBranches = maxBranches.toIntOrNull()
                val parsedTierLevel = tierLevel.toIntOrNull()
                validationError = when {
                    name.isBlank() -> "Plan name is required."
                    normalizedId == "plan_" || normalizedId.length < 6 -> "Plan ID is required."
                    description.isBlank() -> "Description is required."
                    parsedPriceBdt == null -> "Valid BDT price is required."
                    parsedMaxStudents == null -> "Student limit is required."
                    parsedMaxBatches == null -> "Batch limit is required."
                    parsedMaxUsers == null -> "User limit is required."
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
                            maxBatches = parsedMaxBatches!!,
                            maxUsers = parsedMaxUsers!!,
                            maxBranches = parsedMaxBranches!!,
                            tag = tag.trim(),
                            tierLevel = parsedTierLevel!!
                        )
                    )
                }
            }) {
                Text("Save", color = AccentCyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) } },
        containerColor = CardBg
    )
}

@Composable
private fun StatCard(label: String, value: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(value, color = TextWhite, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(label, color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun RevenueCard(title: String, amount: String, color: Color, icon: ImageVector) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, color = TextMuted, fontSize = 12.sp)
                Text(amount, color = TextWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Projected Revenue (Prediction Card) ───────────────────────
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
                        Text("PREDICTION · AI", color = AccentViolet.copy(alpha = 0.8f), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("BDT ${NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 0 }.format(amount)}",
                        color = TextWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Expected Next Month Revenue", color = TextMuted, fontSize = 13.sp)
                    Text("Based on $activeCount active subscriptions × avg BDT ${NumberFormat.getNumberInstance(Locale.getDefault()).format(avgFee.toInt())}",
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
    var showManageDialog by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf(false) }

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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Chat, null, tint = AccentGreen.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(inst.whatsappNumber, color = AccentGreen.copy(alpha = 0.8f), fontSize = 12.sp)
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = {
                                    val phone = inst.whatsappNumber.replace("+", "").replace(" ", "").replace("-", "")
                                    val msg = "Hi, this is BatchFee developer. I'm reaching out regarding your institute \"${inst.name}\"."
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        data = Uri.parse("https://wa.me/$phone?text=${Uri.encode(msg)}")
                                    }
                                    ctx.startActivity(intent)
                                },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(Icons.Filled.Chat, null, tint = Color(0xFF25D366), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CalendarMonth, null, tint = TextMuted.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Until ${dateFormat.format(Date(inst.trialEndDateMs))}", color = TextMuted, fontSize = 11.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AccessTime, null, tint = TextMuted.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Last active: $lastActive", color = TextMuted.copy(alpha = 0.6f), fontSize = 11.sp)
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
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                ) {
                    Icon(Icons.Filled.Update, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Extend", fontSize = 13.sp)
                }

                val blocked = inst.subscriptionStatus == "blocked"
                OutlinedButton(
                    onClick = { viewModel.toggleBlock(inst.id, blocked) },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (blocked) AccentGreen else AccentRed)
                ) {
                    Icon(if (blocked) Icons.Filled.LockOpen else Icons.Filled.Block, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (blocked) "Unblock" else "Block", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = { showManageDialog = true },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentViolet)
                ) {
                    Icon(Icons.Filled.Settings, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Manage", fontSize = 13.sp)
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
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                ) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Remove", fontSize = 12.sp)
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
                                    Text("\"${inst.name}\" will be moved to trash. This action can be reversed within 10 days.", color = TextMuted, fontSize = 13.sp)
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
                                ) { Text("Move to Trash", color = Color.White, fontWeight = FontWeight.Bold) }
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
                            title = { Text("Remove ${inst.name}?", color = TextWhite, fontWeight = FontWeight.Bold) },
                            text = {
                                Text("Are you sure you want to remove this institute?\n\nAll data will be moved to trash and become inaccessible. You can restore it within 10 days before permanent deletion.", color = TextMuted, fontSize = 13.sp)
                            },
                            confirmButton = {
                                Button(onClick = { showRemovePassword = true }, colors = ButtonDefaults.buttonColors(containerColor = AccentRed)) {
                                    Text("Yes, Remove", color = Color.White)
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
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber)
                ) {
                    Icon(Icons.Filled.Password, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset Pwd", fontSize = 12.sp)
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
                    border = ButtonDefaults.outlinedButtonBorder,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPink)
                ) {
                    Icon(Icons.Filled.Pin, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (inst.securityPin.isNullOrBlank()) "Set PIN" else "Edit PIN", fontSize = 12.sp)
                }
            }
        }
    }

    // ── Detail Sheet ──
    if (showDetailSheet) {
        val planPrice = remember(subscriptionPlans, inst.currentPlanId) {
            planDisplayPrice(inst.currentPlanId, subscriptionPlans)
        }

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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    DetailRow("WhatsApp", inst.whatsappNumber ?: "N/A")
                    DetailRow("Email", inst.email ?: "N/A")
                    DetailRow("Plan", "$currentPlanName (BDT ${NumberFormat.getNumberInstance(Locale.getDefault()).format(planPrice.toInt())})")
                    DetailRow("Status", inst.subscriptionStatus.replaceFirstChar { it.uppercase() }, when (inst.subscriptionStatus) {
                        "active" -> AccentGreen; "trial" -> AccentCyan; "expired" -> AccentRed; "blocked" -> AccentAmber; else -> TextMuted
                    })
                    DetailRow("Expiry", dateFormat.format(Date(inst.trialEndDateMs)))
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
        AlertDialog(
            onDismissRequest = { showExtendDialog = false },
            title = { Text("Extend Subscription", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Add days to ${inst.name}'s subscription:", color = TextMuted, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = extendDays, onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) extendDays = it },
                        label = { Text("Days") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val days = extendDays.toIntOrNull() ?: 0
                    if (days > 0) { viewModel.extendSubscription(inst.id, days); showExtendDialog = false }
                }) { Text("Extend", color = AccentCyan, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showExtendDialog = false }) { Text("Cancel", color = TextMuted) } }
        )
    }

    // Manage Dialog
    if (showManageDialog) {
        val cal = remember { Calendar.getInstance() }
        if (inst.trialEndDateMs > 0) cal.timeInMillis = inst.trialEndDateMs
        var editYear by remember { mutableIntStateOf(cal.get(Calendar.YEAR)) }
        var editMonth by remember { mutableIntStateOf(cal.get(Calendar.MONTH)) }
        var editDay by remember { mutableIntStateOf(cal.get(Calendar.DAY_OF_MONTH)) }
        var editStudentLimit by remember { mutableStateOf("50") }
        var editStaffLimit by remember { mutableStateOf("10") }
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

        fun computedExpiryMs(): Long {
            val c = Calendar.getInstance()
            c.set(editYear, editMonth, editDay, 23, 59, 59)
            val addMonthsVal = editAddMonths.toIntOrNull()?.coerceIn(0, 120) ?: 0
            if (addMonthsVal > 0) c.add(Calendar.MONTH, addMonthsVal)
            return c.timeInMillis
        }

        AlertDialog(
            onDismissRequest = { showManageDialog = false },
            title = { Text("Manage ${inst.name}", fontWeight = FontWeight.Bold, color = TextWhite) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Plan", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    var editPlanDropdown by remember { mutableStateOf(false) }
                    Box {
                        OutlinedTextField(
                            value = selectedPlanName,
                            onValueChange = { },
                            readOnly = true,
                            trailingIcon = { Icon(if (editPlanDropdown) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown, null, modifier = Modifier.clickable { editPlanDropdown = !editPlanDropdown }) },
                            modifier = Modifier.fillMaxWidth().clickable { editPlanDropdown = !editPlanDropdown },
                            shape = RoundedCornerShape(12.dp)
                        )
                        DropdownMenu(expanded = editPlanDropdown, onDismissRequest = { editPlanDropdown = false }) {
                            planOptions.forEach { (id, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = { selectedPlanId = id; selectedPlanName = name; editPlanDropdown = false }
                                )
                            }
                        }
                    }

                    Text("Expiry Date", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = editDay.toString().padStart(2, '0'),
                            onValueChange = { val v = it.toIntOrNull(); if (v != null && v in 1..31) editDay = v },
                            label = { Text("Day", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = (editMonth + 1).toString().padStart(2, '0'),
                            onValueChange = { val v = it.toIntOrNull(); if (v != null && v in 1..12) editMonth = v - 1 },
                            label = { Text("Month", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editYear.toString(),
                            onValueChange = { val v = it.toIntOrNull(); if (v != null && v in 2024..2099) editYear = v },
                            label = { Text("Year", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = editAddMonths,
                            onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) editAddMonths = it },
                            label = { Text("+ Months", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Text("→ ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(computedExpiryMs()))}", color = AccentCyan, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    }

                    HorizontalDivider(color = BorderSub, modifier = Modifier.padding(vertical = 4.dp))

                    Text("Student Limit", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = editStudentLimit,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) editStudentLimit = it },
                        label = { Text("Max Students", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(color = BorderSub, modifier = Modifier.padding(vertical = 4.dp))

                    Text("Staff Limit", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = editStaffLimit,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d+$"))) editStaffLimit = it },
                        label = { Text("Max Staff", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(color = BorderSub, modifier = Modifier.padding(vertical = 4.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Account Active", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Switch(
                            checked = editIsActive,
                            onCheckedChange = { editIsActive = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentGreen,
                                checkedTrackColor = AccentGreen.copy(alpha = 0.25f),
                                uncheckedThumbColor = AccentRed,
                                uncheckedTrackColor = AccentRed.copy(alpha = 0.25f)
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val studentLimit = editStudentLimit.toIntOrNull()?.coerceAtLeast(1) ?: 50
                    val staffLimit = editStaffLimit.toIntOrNull()?.coerceAtLeast(1) ?: 10
                    viewModel.manageInstitute(inst.id, computedExpiryMs(), studentLimit, staffLimit, selectedPlanId, editIsActive) {
                        showManageDialog = false
                    }
                }) { Text("Save", color = AccentCyan, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showManageDialog = false }) { Text("Cancel", color = TextMuted) } }
        )
    }

    // ── Share receipt event ──────────────────────────────
    val context = LocalContext.current
    val shareEvent by viewModel.shareReceiptEvent.collectAsState()
    LaunchedEffect(shareEvent) {
        shareEvent?.let { (bitmap, phone) ->
            shareSubscriptionReceipt(context, bitmap, phone)
            viewModel.consumeShareEvent()
        }
    }
}

// ── Subscription Receipt PDF ─────────────────────────────
private fun generateSubscriptionReceiptPdf(context: Context, r: SubscriptionReceiptData): File {
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()) // A4
    val canvas = page.canvas
    val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    val w = 595f; val h = 842f

    val white = android.graphics.Color.WHITE
    val darkBg = android.graphics.Color.parseColor("#0F172A")
    val cyan = android.graphics.Color.parseColor("#22D3EE")
    val muted = android.graphics.Color.parseColor("#64748B")
    val textDark = android.graphics.Color.parseColor("#1E293B")
    val lightBg = android.graphics.Color.parseColor("#F1F5F9")
    val green = android.graphics.Color.parseColor("#16A34A")
    val red = android.graphics.Color.parseColor("#EF4444")
    val gray200 = android.graphics.Color.parseColor("#E2E8F0")

    val fill = Paint().apply { style = Paint.Style.FILL }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = textDark }
    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; color = textDark; isFakeBoldText = true }
    val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; color = white; isFakeBoldText = true }

    // ── Header ──
    fill.color = darkBg
    canvas.drawRect(0f, 0f, w, 140f, fill)
    whiteText.textSize = 22f
    canvas.drawText("SUBSCRIPTION RECEIPT", 40f, 55f, whiteText)
    whiteText.textSize = 13f
    canvas.drawText(r.instituteName, 40f, 80f, whiteText)
    whiteText.textSize = 10f
    canvas.drawText("Receipt #: ${r.receiptNumber}  •  ${dateFmt.format(Date(r.startDateMs))}", 40f, 100f, whiteText)

    // ── Institute Owner Info ──
    var y = 170f
    fill.color = lightBg
    canvas.drawRect(30f, y - 5, w - 30f, y + 65f, fill)
    bold.textSize = 14f; bold.color = textDark
    canvas.drawText("Institute Details", 40f, y + 15f, bold)
    text.textSize = 10f; text.color = muted
    canvas.drawText("Owner: ${r.ownerName}", 40f, y + 30f, text)
    canvas.drawText("Phone: ${r.ownerPhone}", 40f, y + 42f, text)
    if (r.ownerEmail.isNotBlank()) canvas.drawText("Email: ${r.ownerEmail}", 40f, y + 54f, text)
    if (r.instituteCode.isNotBlank()) canvas.drawText("Institute Code: ${r.instituteCode}", 240f, y + 30f, text)
    if (r.instituteAddress.isNotBlank()) canvas.drawText("Address: ${r.instituteAddress}", 240f, y + 42f, text)

    // ── Plan Details ──
    y += 85f
    bold.textSize = 14f
    canvas.drawText("Subscription Details", 40f, y, bold)
    y += 20f
    fill.color = gray200; canvas.drawRect(40f, y, w - 40f, y + 1f, fill); y += 15f

    val rows = listOf(
        "Plan" to r.planName,
        "Duration" to "${r.durationMonths} Month(s)",
        "Period" to "${dateFmt.format(Date(r.startDateMs))} — ${dateFmt.format(Date(r.endDateMs))}",
        "Amount Paid" to "BDT ${"%,.0f".format(r.amountPaid)}",
        "Payment Method" to r.paymentMethod.uppercase(),
        "Transaction Ref" to "***${r.transactionLast4}"
    )
    rows.forEach { (label, value) ->
        text.textSize = 10f; text.color = muted
        canvas.drawText(label, 40f, y + 8f, text)
        text.textSize = 11f; text.color = if (label == "Amount Paid") green else textDark
        text.isFakeBoldText = label == "Amount Paid"
        canvas.drawText(value, 280f, y + 8f, text)
        text.isFakeBoldText = false
        y += if (label == "Amount Paid") 24f else 18f
    }

    // ── Status ──
    y += 15f
    fill.color = lightBg
    canvas.drawRect(40f, y, w - 40f, y + 30f, fill)
    bold.textSize = 12f; bold.color = green
    canvas.drawText("STATUS: APPROVED & ACTIVE", 50f, y + 19f, bold)

    // ── Expiry Warning ──
    y += 50f
    bold.textSize = 11f; bold.color = red
    canvas.drawText("Subscription Expires:", 40f, y, bold)
    bold.textSize = 13f
    canvas.drawText(dateFmt.format(Date(r.endDateMs)), 200f, y, bold)

    // ── Footer ──
    y = h - 50f
    fill.color = gray200; canvas.drawRect(40f, y, w - 40f, y + 1f, fill)
    y += 15f
    text.textSize = 9f; text.color = muted
    canvas.drawText("Generated by BatchFee Super Admin  •  This is a computer-generated receipt.", 40f, y + 5f, text)

    document.finishPage(page)
    val file = File(context.cacheDir, "sub_receipt_${r.receiptNumber}.pdf")
    file.outputStream().use { document.writeTo(it) }
    document.close()
    return file
}

// ── Subscription Receipt Bitmap (Canvas) ──────────────────
private fun createSubscriptionReceiptBitmap(
    receiptNumber: String,
    instituteName: String,
    planName: String,
    durationMonths: Int,
    amountPaid: Double,
    paymentMethod: String,
    transactionLast4: String,
    startDateMs: Long,
    endDateMs: Long
): Bitmap {
    val w = 600; val h = 800
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    val darkBg = android.graphics.Color.parseColor("#0F172A")
    val white = android.graphics.Color.WHITE
    val muted = android.graphics.Color.parseColor("#94A3B8")
    val cyan = android.graphics.Color.parseColor("#22D3EE")
    val dark = android.graphics.Color.parseColor("#1E293B")
    val textDark = android.graphics.Color.parseColor("#0F172A")

    // White background
    c.drawColor(white)

    // ── Header bar ──
    val headerBg = Paint().apply { color = darkBg }
    c.drawRect(0f, 0f, w.toFloat(), 120f, headerBg)

    // BF logo circle
    val logoBg = Paint().apply { color = cyan; isAntiAlias = true }
    c.drawCircle(50f, 60f, 28f, logoBg)
    val logoTxt = Paint().apply { color = darkBg; textSize = 28f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    c.drawText("BF", 50f, 72f, logoTxt)

    // Institute name
    val headerName = Paint().apply { color = white; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    c.drawText(instituteName, 95f, 50f, headerName)
    // Subtitle
    val headerSub = Paint().apply { color = muted; textSize = 13f; isAntiAlias = true }
    c.drawText("BatchFee Subscription", 95f, 70f, headerSub)
    c.drawText("Management Platform", 95f, 88f, headerSub)

    // ── Title ──
    val titlePaint = Paint().apply { color = darkBg; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    c.drawText("SUBSCRIPTION RECEIPT", w / 2f, 160f, titlePaint)

    val lbl = Paint().apply { color = muted; textSize = 18f; isAntiAlias = true }
    val vlu = Paint().apply { color = darkBg; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val div = Paint().apply { color = android.graphics.Color.parseColor("#E2E8F0"); strokeWidth = 1.5f }

    var y = 210f; val lh = 44f; val c1 = 40f; val c2 = 220f

    // Receipt number + date
    c.drawText("Receipt #", c1, y, lbl)
    c.drawText(receiptNumber, c2, y, vlu); y += lh
    c.drawText("Date", c1, y, lbl)
    c.drawText(dateFmt.format(Date(startDateMs)), c2, y, vlu); y += lh + 10f
    c.drawLine(c1, y, w - 40f, y, div); y += 24f

    // ── Plan details ──
    c.drawText("Plan", c1, y, lbl)
    c.drawText(planName, c2, y, vlu); y += lh
    c.drawText("Duration", c1, y, lbl)
    c.drawText("${durationMonths} Month(s)", c2, y, vlu); y += lh
    c.drawText("Period", c1, y, lbl)
    c.drawText("${dateFmt.format(Date(startDateMs))} - ${dateFmt.format(Date(endDateMs))}", c2, y, Paint().apply { color = darkBg; textSize = 17f; isAntiAlias = true }); y += lh + 10f
    c.drawLine(c1, y, w - 40f, y, div); y += 24f

    // ── Payment ──
    c.drawText("Amount Paid", c1, y, lbl)
    c.drawText("BDT ${"%,.0f".format(amountPaid)}", c2, y, Paint().apply { color = cyan; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }); y += lh + 8f
    c.drawText("Method", c1, y, lbl)
    c.drawText(paymentMethod.uppercase(), c2, y, vlu); y += lh
    c.drawText("Transaction", c1, y, lbl)
    c.drawText("***$transactionLast4", c2, y, vlu); y += lh + 10f
    c.drawLine(c1, y, w - 40f, y, div); y += 24f

    // ── Expiry ──
    c.drawText("Expiry Date", c1, y, lbl)
    c.drawText(dateFmt.format(Date(endDateMs)), c2, y, Paint().apply { color = android.graphics.Color.parseColor("#F87171"); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }); y += lh + 10f

    // ── Footer ──
    c.drawLine(c1, y, w - 40f, y, div); y += 30f
    val foot = Paint().apply { color = muted; textSize = 14f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    c.drawText("Generated by BatchFee Super Admin", w / 2f, y, foot); y += 22f
    c.drawText("This is a computer-generated receipt.", w / 2f, y, foot)

    return bmp
}

private fun shareSubscriptionReceipt(context: Context, bitmap: Bitmap, phone: String?) {
    val cleanPhone = phone?.replace("+", "")?.replace(" ", "")?.replace("-", "")?.takeIf { it.isNotBlank() }
    val file = File(context.cacheDir, "sub_receipt_${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        `package` = "com.whatsapp"
        if (cleanPhone != null) {
            putExtra("jid", "${cleanPhone}@s.whatsapp.net")
        }
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share Subscription Receipt"))
    } catch (_: Exception) {
        // Fallback: generic share
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share Receipt"))
    }
}

