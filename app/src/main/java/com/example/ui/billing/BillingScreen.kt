package com.batchfee.edu.ui.billing

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.SubscriptionPlanEntity
import com.batchfee.edu.data.models.SubscriptionRequest
import com.batchfee.edu.data.repository.SubscriptionRepository
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.ui.superadmin.SubscriptionReceiptData
import com.batchfee.edu.ui.superadmin.generateSubscriptionReceiptPdf
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.crashlytics.FirebaseCrashlytics
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgDark = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val BorderSub = Color(0xFF1E293B)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val Cyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val SkyBlue = Color(0xFF38BDF8)
private val Amber = Color(0xFFF59E0B)
private val Green = Color(0xFF22C55E)
private val AccentRed = Color(0xFFF87171)

class BillingViewModel(private val db: AppDatabase) : ViewModel() {
    private val _institute = MutableStateFlow<InstituteEntity?>(null)
    val institute = _institute.asStateFlow()

    private val _currentPlan = MutableStateFlow<SubscriptionPlanEntity?>(null)
    val currentPlan = _currentPlan.asStateFlow()

    private val _latestRequest = MutableStateFlow<SubscriptionRequest?>(null)
    val latestRequest = _latestRequest.asStateFlow()

    private val _receipts = MutableStateFlow<List<SubscriptionReceiptData>>(emptyList())
    val receipts = _receipts.asStateFlow()

    private val firestore = FirebaseFirestore.getInstance()

    init {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            db.instituteDao().getInstituteFlow(instId).collect { inst ->
                _institute.value = inst
                if (inst != null) {
                    _currentPlan.value = db.subscriptionPlanDao().getPlanById(inst.currentPlanId)
                }
            }
        }
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            firestore.collection("institutes").document(instId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    val data = snapshot.data ?: return@addSnapshotListener
                    val now = System.currentTimeMillis()
                    val currentPlanId = data["currentPlanId"] as? String ?: "plan_free_trial"
                    val subscriptionStatus = data["subscriptionStatus"] as? String
                        ?: if (currentPlanId == "plan_free_trial") "trial" else "active"
                    val institute = InstituteEntity(
                        id = snapshot.id,
                        name = data["instituteName"] as? String ?: "Institute",
                        currentPlanId = currentPlanId,
                        subscriptionStatus = subscriptionStatus,
                        trialStartDateMs = data["createdAt"] as? Long ?: now,
                        trialEndDateMs = data["trialEndDate"] as? Long ?: now,
                        currentPeriodEndMs = (data["currentPeriodEndMs"] as? Long)
                            ?: (data["trialEndDate"] as? Long ?: now),
                        createdAtMs = data["createdAt"] as? Long ?: now,
                        phone = data["phone"] as? String,
                        whatsappNumber = data["whatsappNumber"] as? String,
                        ownerName = data["ownerName"] as? String,
                        email = data["email"] as? String,
                        instituteCode = data["instituteCode"] as? String,
                        securityPin = data["securityPin"] as? String
                    )
                    viewModelScope.launch {
                        db.instituteDao().insertInstitute(institute)
                        _institute.value = institute
                        _currentPlan.value = db.subscriptionPlanDao().getPlanById(institute.currentPlanId)
                    }
                }
        }
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            firestore.collection("subscriptionRequests")
                .whereEqualTo("instituteId", instId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    val doc = snapshot?.documents
                        ?.maxByOrNull { (it.data?.get("requestSentAt") as? Number)?.toLong() ?: 0L }
                    if (doc != null && doc.exists()) {
                        val data = doc.data ?: return@addSnapshotListener
                        _latestRequest.value = SubscriptionRequest.fromFirestore(doc.id, data)
                    } else {
                        _latestRequest.value = null
                    }
                }
        }
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            // This canonical record is created by the trusted approval service.
            // Student fee receipts intentionally share no billing UI or data path.
            firestore.collection("institutes").document(instId)
                .collection("subscription_receipts")
                .orderBy("approvedAt", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        FirebaseCrashlytics.getInstance().recordException(error)
                        return@addSnapshotListener
                    }
                    _receipts.value = snapshot?.documents?.mapNotNull { doc ->
                        val d = doc.data ?: return@mapNotNull null
                        SubscriptionReceiptData(
                            receiptNumber = d["receiptNumber"] as? String ?: doc.id,
                            instituteName = d["instituteName"] as? String ?: "",
                            ownerName = d["ownerName"] as? String ?: "",
                            ownerPhone = d["ownerPhone"] as? String ?: "",
                            ownerEmail = d["ownerEmail"] as? String ?: "",
                            instituteCode = d["instituteCode"] as? String ?: "",
                            instituteAddress = d["instituteAddress"] as? String ?: "",
                            planName = d["planName"] as? String ?: "",
                            durationMonths = (d["durationMonths"] as? Number)?.toInt() ?: 1,
                            amountPaid = (d["amountPaid"] as? Number)?.toDouble() ?: 0.0,
                            paymentMethod = d["paymentMethod"] as? String ?: "",
                            transactionLast4 = d["transactionLast4"] as? String ?: "",
                            startDateMs = (d["startDateMs"] as? Number)?.toLong() ?: (d["approvedAt"] as? Number)?.toLong() ?: 0L,
                            endDateMs = (d["endDateMs"] as? Number)?.toLong() ?: 0L
                        )
                    }?.sortedByDescending { it.startDateMs } ?: emptyList()
                }
        }
    }
}

class BillingViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BillingViewModel::class.java)) return BillingViewModel(db) as T
        throw IllegalArgumentException()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onUpgrade: () -> Unit
) {
    val viewModel: BillingViewModel = viewModel(factory = BillingViewModelFactory(db))
    val institute by viewModel.institute.collectAsState()
    val plan by viewModel.currentPlan.collectAsState()
    val latestRequest by viewModel.latestRequest.collectAsState()
    val receipts by viewModel.receipts.collectAsState()
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val fallbackPlanName = remember(institute?.currentPlanId) {
        when (institute?.currentPlanId) {
            "plan_free_trial" -> "Free Trial"
            "plan_starter" -> "Starter"
            "plan_growth" -> "Growth"
            "plan_pro" -> "Pro"
            "plan_institute" -> "Institute"
            null -> "Loading..."
            else -> institute?.currentPlanId?.replace('_', ' ')
                ?.split(' ')
                ?.joinToString(" ") { token -> token.replaceFirstChar { c -> c.uppercase() } }
                ?: "Loading..."
        }
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("Billing & Subscription", color = TextWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val isTrial = institute?.subscriptionStatus == "trial"
            val planTitle = plan?.name ?: fallbackPlanName
            val studentEntitlement = when {
                isTrial -> "Unlimited"
                plan != null -> "Up to ${plan!!.maxStudents}"
                else -> "Plan limit"
            }
            val entitlementTransition = rememberInfiniteTransition(label = "billingEntitlementGlow")
            val entitlementGlow by entitlementTransition.animateFloat(
                initialValue = 0.16f,
                targetValue = 0.42f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2200, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "billingEntitlementGlowAlpha"
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        Brush.horizontalGradient(
                            listOf(ElectricBlue.copy(alpha = entitlementGlow), Cyan.copy(alpha = entitlementGlow), BorderSub)
                        ),
                        RoundedCornerShape(16.dp)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(70.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(ElectricBlue.copy(alpha = 0.15f), Cyan.copy(alpha = 0.06f), Color.Transparent)
                                )
                            )
                    )
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Current Plan", color = TextMuted, fontSize = 12.sp)
                                Spacer(Modifier.height(3.dp))
                                Text(planTitle, color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            val status = institute?.subscriptionStatus.orEmpty()
                            val chipColor = when (status) {
                                "active" -> Green
                                "trial" -> Cyan
                                "expired" -> AccentRed
                                else -> TextMuted
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(chipColor.copy(alpha = 0.15f))
                                    .border(1.dp, chipColor.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(status.uppercase().ifBlank { "UNKNOWN" }, color = chipColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (isTrial) {
                                "Free Trial includes full access while you evaluate BatchFee."
                            } else {
                                "Your student seat limit is protected by this plan."
                            },
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SubscriptionEntitlementCell(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.Group,
                                label = "Students",
                                value = studentEntitlement,
                                accent = SkyBlue
                            )
                            SubscriptionEntitlementCell(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.Groups,
                                label = "Batches",
                                value = "Unlimited",
                                accent = Cyan
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SubscriptionEntitlementCell(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.Badge,
                                label = "Staff",
                                value = "Unlimited",
                                accent = Green
                            )
                            SubscriptionEntitlementCell(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.CalendarToday,
                                label = if (isTrial) "Trial ends" else "Renews",
                                value = institute?.let {
                                    dateFormat.format(Date(if (isTrial) it.trialEndDateMs else it.currentPeriodEndMs))
                                } ?: "—",
                                accent = if (isTrial) Cyan else Green
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (latestRequest != null) {
                val req = latestRequest!!
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, BorderSub)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Your Request", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            val reqStatus = req.status
                            val chipBg = when (reqStatus) {
                                "pending" -> Amber.copy(alpha = 0.15f)
                                "approved" -> Green.copy(alpha = 0.15f)
                                "rejected" -> AccentRed.copy(alpha = 0.15f)
                                else -> TextMuted.copy(alpha = 0.15f)
                            }
                            val chipColor = when (reqStatus) {
                                "pending" -> Amber
                                "approved" -> Green
                                "rejected" -> AccentRed
                                else -> TextMuted
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(chipBg)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(reqStatus.uppercase(), color = chipColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Plan: ${req.requestedPlanId} · ${req.durationMonths} Month(s)", color = TextMuted, fontSize = 12.sp)
                        Text("Amount: BDT ${"%.0f".format(req.amountPaid)} · ${req.paymentMethod}", color = TextMuted, fontSize = 12.sp)
                        if (req.studentLimitAtRequest > 0) {
                            Text("Student access: Up to ${req.studentLimitAtRequest} students", color = Cyan, fontSize = 11.sp)
                        }
                        if (req.senderPhone.isNotBlank()) {
                            Text("Sent from: ${req.senderPhone}", color = TextMuted, fontSize = 11.sp)
                        }
                        if (req.reviewerNote != null) {
                            Spacer(Modifier.height(4.dp))
                            Text("Note: ${req.reviewerNote}", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── Payment History ──
            Spacer(Modifier.height(24.dp))
            Text("Payment History", color = TextMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            if (receipts.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, BorderSub)
                ) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text("No payment receipts yet.", color = TextMuted, fontSize = 13.sp)
                    }
                }
            } else {
                val ctx = LocalContext.current
                receipts.take(20).forEach { r ->
                    var showReceiptDialog by remember { mutableStateOf(false) }
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { showReceiptDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, BorderSub)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Green.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.ReceiptLong, null, tint = Green, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.planName, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${r.durationMonths} mo · ${r.paymentMethod.uppercase()} · ${SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(Date(r.startDateMs))}",
                                    color = TextMuted, fontSize = 11.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "BDT ${"%,.0f".format(r.amountPaid)}",
                                    color = Green,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(Date(r.startDateMs))} — ${SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(Date(r.endDateMs))}",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                    if (showReceiptDialog) {
                        AlertDialog(
                            onDismissRequest = { showReceiptDialog = false },
                            title = { Text("Payment Receipt", color = TextWhite, fontWeight = FontWeight.Bold) },
                            text = { Text("${r.planName}\nBDT ${"%,.0f".format(r.amountPaid)}\n${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(r.startDateMs))}", color = TextMuted, fontSize = 13.sp) },
                            confirmButton = {
                                Button(onClick = {
                                    try {
                                        val file = generateSubscriptionReceiptPdf(ctx, r)
                                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "application/pdf")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        })
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "Unable to open PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                    showReceiptDialog = false
                                }, colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)) {
                                    Text("View / Download PDF", color = Color.White)
                                }
                            },
                            dismissButton = { TextButton(onClick = { showReceiptDialog = false }) { Text("Close", color = TextMuted) } },
                            containerColor = CardBg,
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.horizontalGradient(listOf(Cyan, ElectricBlue)))
                    .clickable { onUpgrade() },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Upgrade, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Upgrade Plan", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun SubscriptionEntitlementCell(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    accent: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.20f), RoundedCornerShape(11.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, color = TextMuted, fontSize = 9.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

