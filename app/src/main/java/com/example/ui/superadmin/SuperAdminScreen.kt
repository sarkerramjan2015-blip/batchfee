package com.example.ui.superadmin

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.models.InstituteEntity
import com.example.domain.SessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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

class SuperAdminViewModel(private val db: AppDatabase) : ViewModel() {
    private val _institutes = MutableStateFlow<List<InstituteEntity>>(emptyList())
    val institutes = _institutes.asStateFlow()

    private val _stats = MutableStateFlow(SuperAdminStats())
    val stats = _stats.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _operationMsg = MutableStateFlow<String?>(null)
    val operationMsg = _operationMsg.asStateFlow()

    private val _lastActiveMap = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastActiveMap = _lastActiveMap.asStateFlow()

    private val firestore = FirebaseFirestore.getInstance()

    val projectedRevenue: Double
        get() = _stats.value.activeSubscriptions * STANDARD_MONTHLY_FEE

    init {
        loadInstitutesRealtime()
    }

    fun clearOperationMsg() { _operationMsg.value = null }

    private fun loadInstitutesRealtime() {
        firestore.collection("institutes")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    _isLoading.value = false
                    return@addSnapshotListener
                }

                val list = mutableListOf<InstituteEntity>()
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

                    if (lastActive != null) activeMap[uid] = lastActive

                    val status = when {
                        !isActive -> "blocked"
                        trialEnd < now -> "expired"
                        (now - createdAt) < trialWindow -> "trial"
                        else -> "active"
                    }

                    if (isActive && trialEnd > now) activeCount++

                    list.add(
                        InstituteEntity(
                            id = uid,
                            name = data["instituteName"] as? String ?: "Institute",
                            currentPlanId = "plan_free_trial",
                            subscriptionStatus = status,
                            trialStartDateMs = createdAt,
                            trialEndDateMs = trialEnd,
                            currentPeriodEndMs = trialEnd,
                            createdAtMs = createdAt,
                            phone = data["phone"] as? String,
                            whatsappNumber = data["whatsappNumber"] as? String,
                            ownerName = data["ownerName"] as? String,
                            email = data["email"] as? String,
                            instituteCode = data["instituteCode"] as? String,
                            securityPin = data["securityPin"] as? String
                        )
                    )
                }

                val totalStudents = snapshot.documents.sumOf {
                    ((it.data?.get("studentCount") as? Long) ?: 0L).toInt()
                }
                val totalStaff = snapshot.documents.sumOf {
                    ((it.data?.get("staffCount") as? Long) ?: 0L).toInt()
                }

                _institutes.value = list
                _lastActiveMap.value = activeMap
                _stats.value = SuperAdminStats(
                    totalInstitutes = list.size,
                    activeSubscriptions = activeCount,
                    totalRevenue = activeCount * STANDARD_MONTHLY_FEE,
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
                _operationMsg.value = "Security PIN updated to $pin"
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
    val isLoading by viewModel.isLoading.collectAsState()
    val operationMsg by viewModel.operationMsg.collectAsState()
    val projected = viewModel.projectedRevenue

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(operationMsg) {
        operationMsg?.let { snackbarHostState.showSnackbar(it); viewModel.clearOperationMsg() }
    }

    var announceText by remember { mutableStateOf("") }

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

            // ── Institute list ──
            item {
                Spacer(Modifier.height(4.dp))
                Text("All Institutes · ${institutes.size}", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            if (institutes.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Text("No institutes registered yet.", color = TextMuted, fontSize = 14.sp)
                    }
                }
            } else {
                items(institutes, key = { it.id }) { inst ->
                    InstituteCard(inst, viewModel)
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
                    Text("Based on $activeCount active subscriptions × BDT ${NumberFormat.getNumberInstance(Locale.getDefault()).format(STANDARD_MONTHLY_FEE.toInt())}",
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
private fun InstituteCard(inst: InstituteEntity, viewModel: SuperAdminViewModel) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val statusColor = when (inst.subscriptionStatus) {
        "active" -> AccentGreen; "trial" -> AccentCyan; "expired" -> AccentRed; "blocked" -> AccentAmber; else -> TextMuted
    }
    val lastActive = viewModel.lastActiveLabel(inst.id)

    var showExtendDialog by remember { mutableStateOf(false) }
    var extendDays by remember { mutableStateOf("30") }
    var showManageDialog by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
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

            // Contact Info + Subscription
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Visibility, null, tint = TextMuted.copy(alpha = 0.5f), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("ID: ${inst.id.take(8)}...", color = TextMuted.copy(alpha = 0.5f), fontSize = 10.sp)
                    }
                    if (!inst.securityPin.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Key, null, tint = AccentAmber.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("PIN: ${inst.securityPin}", color = AccentAmber.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
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
}
