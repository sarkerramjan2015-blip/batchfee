package com.example.ui.superadmin

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
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
import com.example.data.database.AppDatabase
import com.example.data.models.InstituteEntity
import com.example.data.models.SubscriptionRequest
import com.example.domain.SessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class SuperAdminViewModel(private val db: AppDatabase) : ViewModel() {
    private val _institutes = MutableStateFlow<List<InstituteCardData>>(emptyList())
    val institutes = _institutes.asStateFlow()

    private val _stats = MutableStateFlow(SuperAdminStats())
    val stats = _stats.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _operationMsg = MutableStateFlow<String?>(null)
    val operationMsg = _operationMsg.asStateFlow()

    private val _shareReceiptEvent = MutableStateFlow<Pair<Bitmap, String>?>(null)
    val shareReceiptEvent = _shareReceiptEvent.asStateFlow()

    private val _lastActiveMap = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastActiveMap = _lastActiveMap.asStateFlow()

    private val _pendingRequests = MutableStateFlow<List<SubscriptionRequest>>(emptyList())
    val pendingRequests = _pendingRequests.asStateFlow()

    private val firestore = FirebaseFirestore.getInstance()

    val projectedRevenue: Double
        get() = _stats.value.totalRevenue

    init {
        loadInstitutesRealtime()
        loadPendingRequestsRealtime()
    }

    fun clearOperationMsg() { _operationMsg.value = null }

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
                val newEnd = withContext(Dispatchers.IO) {
                    val repo = com.example.data.repository.SubscriptionRepository(firestore)
                    android.util.Log.d("SUPERADMIN", "approveRequest DBG: approving subscriptionRequests/${request.requestId}")
                    repo.approveRequest(request.requestId, "sys_super_admin_1")
                    val end = System.currentTimeMillis() + (request.durationMonths * 30L * 24 * 60 * 60 * 1000)
                    android.util.Log.d("SUPERADMIN", "approveRequest DBG: updating institutes/${request.instituteId} → plan=${request.requestedPlanId}, end=$end")
                    firestore.collection("institutes").document(request.instituteId)
                        .update("currentPlanId", request.requestedPlanId, "trialEndDate", end, "isActive", true)
                        .await()
                    end
                }
                _operationMsg.value = "Approved ${request.instituteName} — ${request.requestedPlanId}"
                // Generate receipt and trigger share
                val receiptNumber = "SUB-${System.currentTimeMillis()}"
                val planNames = mapOf(
                    "plan_free_trial" to "Free Trial", "plan_starter" to "Starter",
                    "plan_growth" to "Growth", "plan_pro" to "Pro", "plan_institute" to "Institute"
                )
                val planName = planNames[request.requestedPlanId] ?: request.requestedPlanId
                val bitmap = withContext(Dispatchers.IO) {
                    createSubscriptionReceiptBitmap(
                        receiptNumber = receiptNumber,
                        instituteName = request.instituteName,
                        planName = planName,
                        durationMonths = request.durationMonths,
                        amountPaid = request.amountPaid,
                        paymentMethod = request.paymentMethod,
                        transactionLast4 = request.transactionLast4,
                        startDateMs = System.currentTimeMillis(),
                        endDateMs = newEnd
                    )
                }
                val phone = _institutes.value.find { it.entity.id == request.instituteId }?.entity?.whatsappNumber
                    ?: request.institutePhone
                _shareReceiptEvent.value = Pair(bitmap, phone ?: "")
            } catch (e: Exception) {
                _operationMsg.value = "Approve failed: ${e.message}"
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    fun consumeShareEvent() { _shareReceiptEvent.value = null }

    fun rejectRequest(request: SubscriptionRequest, note: String? = null) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val repo = com.example.data.repository.SubscriptionRepository(firestore)
                    repo.rejectRequest(request.requestId, "sys_super_admin_1", note)
                }
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

                    val status = when {
                        !isActive -> "blocked"
                        trialEnd < now -> "expired"
                        (now - createdAt) < trialWindow -> "trial"
                        else -> "active"
                    }

                    if (isActive && trialEnd > now) activeCount++

                    list.add(
                        InstituteCardData(
                            entity = InstituteEntity(
                                id = uid,
                                name = data["instituteName"] as? String ?: "Institute",
                                currentPlanId = data["currentPlanId"] as? String ?: "plan_free_trial",
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

                val totalStudents = snapshot.documents.sumOf {
                    ((it.data?.get("studentCount") as? Long) ?: 0L).toInt()
                }
                val totalStaff = snapshot.documents.sumOf {
                    ((it.data?.get("staffCount") as? Long) ?: 0L).toInt()
                }

                val actualRevenue = snapshot.documents.sumOf { doc ->
                    val planId = doc.data?.get("currentPlanId") as? String ?: "plan_free_trial"
                    val isAct = (doc.data?.get("isActive") as? Boolean ?: true)
                    val end = (doc.data?.get("trialEndDate") as? Long ?: now)
                    if (isAct && end > now) {
                        when (planId) {
                            "plan_starter" -> 499.0
                            "plan_growth" -> 999.0
                            "plan_pro" -> 1999.0
                            "plan_institute" -> 4999.0
                            else -> 0.0
                        }
                    } else 0.0
                }

                _institutes.value = list
                _lastActiveMap.value = activeMap
                _stats.value = SuperAdminStats(
                    totalInstitutes = list.size,
                    activeSubscriptions = activeCount,
                    totalRevenue = actualRevenue,
                    totalStudents = totalStudents,
                    totalStaff = totalStaff
                )
                _isLoading.value = false
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
        FirebaseAuth.getInstance().sendPasswordResetEmail(email)
            .addOnSuccessListener {
                _operationMsg.value = "Password reset email sent to $email"
            }
            .addOnFailureListener { e ->
                _operationMsg.value = "Failed: ${e.message}"
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
    val stats by viewModel.stats.collectAsState()
    val pendingRequests by viewModel.pendingRequests.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val operationMsg by viewModel.operationMsg.collectAsState()
    val projected = viewModel.projectedRevenue

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(operationMsg) {
        operationMsg?.let { snackbarHostState.showSnackbar(it); viewModel.clearOperationMsg() }
    }

    var announceText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") }

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
                                Button(
                                    onClick = { viewModel.approveRequest(req) },
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
                    InstituteCard(card, viewModel)
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
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
private fun InstituteCard(card: InstituteCardData, viewModel: SuperAdminViewModel) {
    val inst = card.entity
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val statusColor = when (inst.subscriptionStatus) {
        "active" -> AccentGreen; "trial" -> AccentCyan; "expired" -> AccentRed; "blocked" -> AccentAmber; else -> TextMuted
    }
    val lastActive = viewModel.lastActiveLabel(inst.id)

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
                    Text("Plan: ${inst.currentPlanId} · Joined ${dateFormat.format(Date(inst.createdAtMs))}", color = TextMuted, fontSize = 12.sp)
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
        val planPrices = remember { mapOf(
            "plan_free_trial" to 0.0, "plan_starter" to 499.0, "plan_growth" to 999.0,
            "plan_pro" to 1999.0, "plan_institute" to 4999.0
        )}
        val planPrice = planPrices[inst.currentPlanId] ?: 500.0

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
                    DetailRow("Plan", "${inst.currentPlanId} (BDT ${NumberFormat.getNumberInstance(Locale.getDefault()).format(planPrice.toInt())})")
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

        val planOptions = remember { mapOf(
            "plan_free_trial" to "Free Trial",
            "plan_starter" to "Starter",
            "plan_growth" to "Growth",
            "plan_pro" to "Pro",
            "plan_institute" to "Institute"
        )}
        var selectedPlanId by remember { mutableStateOf(inst.currentPlanId) }
        var selectedPlanName by remember { mutableStateOf(planOptions[inst.currentPlanId] ?: "Default") }

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
