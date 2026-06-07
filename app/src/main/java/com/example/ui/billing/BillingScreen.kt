package com.example.ui.billing

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.models.InstituteEntity
import com.example.data.models.SubscriptionPlanEntity
import com.example.data.models.SubscriptionRequest
import com.example.data.repository.SubscriptionRepository
import com.example.domain.SessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
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
            firestore.collection("subscriptionRequests")
                .whereEqualTo("instituteId", instId)
                .orderBy("requestSentAt", Query.Direction.DESCENDING)
                .limit(1)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    val doc = snapshot?.documents?.firstOrNull()
                    if (doc != null && doc.exists()) {
                        val data = doc.data ?: return@addSnapshotListener
                        _latestRequest.value = SubscriptionRequest.fromFirestore(doc.id, data)
                    } else {
                        _latestRequest.value = null
                    }
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
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

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
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Current Plan", color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(plan?.name ?: "Loading...", color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val status = institute?.subscriptionStatus ?: ""
                        val chipColor = when (status) {
                            "active" -> Green
                            "trial" -> Cyan
                            "expired" -> AccentRed
                            else -> TextMuted
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(chipColor.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(status.uppercase(), color = chipColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        if (institute?.subscriptionStatus == "trial") {
                            Text("Trial Ends: ${institute?.let { dateFormat.format(Date(it.trialEndDateMs)) }}", color = TextMuted, fontSize = 12.sp)
                        } else {
                            Text("Next Billing: ${institute?.let { dateFormat.format(Date(it.currentPeriodEndMs)) }}", color = TextMuted, fontSize = 12.sp)
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
                        if (req.reviewerNote != null) {
                            Spacer(Modifier.height(4.dp))
                            Text("Note: ${req.reviewerNote}", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Spacer(Modifier.weight(1f))

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
