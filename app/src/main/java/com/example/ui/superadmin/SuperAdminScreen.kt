package com.example.ui.superadmin

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.models.InstituteEntity
import com.example.domain.SessionManager
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
private val ElectricBlue = Color(0xFF3B82F6)

data class SuperAdminStats(
    val totalInstitutes: Int = 0,
    val activeSubscriptions: Int = 0,
    val totalRevenue: Double = 0.0
)

class SuperAdminViewModel(private val db: AppDatabase) : ViewModel() {
    private val _institutes = MutableStateFlow<List<InstituteEntity>>(emptyList())
    val institutes = _institutes.asStateFlow()

    private val _stats = MutableStateFlow(SuperAdminStats())
    val stats = _stats.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            db.instituteDao().getAllInstitutes().collect { list ->
                _institutes.value = list
            }
        }
        loadFirestoreStats()
    }

    private fun loadFirestoreStats() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val firestore = FirebaseFirestore.getInstance()
                    val doc = firestore.collection("SuperAdmin")
                        .document("stats")
                        .get()
                        .await()

                    _stats.value = SuperAdminStats(
                        totalInstitutes = doc.getLong("totalInstitutes")?.toInt() ?: 0,
                        activeSubscriptions = doc.getLong("activeSubscriptions")?.toInt() ?: 0,
                        totalRevenue = doc.getDouble("totalRevenue") ?: 0.0
                    )
                }
            } catch (_: Exception) {
                // Offline — keep defaults (0)
            } finally {
                _isLoading.value = false
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperAdminScreen(
    db: AppDatabase,
    onLogout: () -> Unit
) {
    val viewModel: SuperAdminViewModel = viewModel(factory = SuperAdminViewModelFactory(db))
    val institutes by viewModel.institutes.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brush.horizontalGradient(listOf(AccentViolet, ElectricBlue))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Shield, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Super Admin", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("BatchFee Platform", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor),
                actions = {
                    IconButton(onClick = {
                        SessionManager.logout()
                        onLogout()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, "Logout", tint = AccentRed)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ── Stats grid ──
            item {
                Text("Platform Overview", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(
                        "Registered\nInstitutes",
                        if (isLoading) "..." else stats.totalInstitutes.toString(),
                        AccentCyan,
                        Icons.Filled.Business,
                        Modifier.weight(1f)
                    )
                    StatCard(
                        "Active\nSubscriptions",
                        if (isLoading) "..." else stats.activeSubscriptions.toString(),
                        AccentGreen,
                        Icons.Filled.Verified,
                        Modifier.weight(1f)
                    )
                }
            }
            item {
                RevenueCard(
                    if (isLoading) "..." else "BDT ${NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 0 }.format(stats.totalRevenue)}",
                    AccentAmber
                )
            }

            // ── Revenue trend animation ──
            item {
                val pulseAnim = rememberInfiniteTransition()
                val bar1 by pulseAnim.animateFloat(0.6f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse))
                val bar2 by pulseAnim.animateFloat(0.3f, 0.85f, infiniteRepeatable(tween(1000), RepeatMode.Reverse))
                val bar3 by pulseAnim.animateFloat(0.5f, 0.95f, infiniteRepeatable(tween(1400), RepeatMode.Reverse))
                val bar4 by pulseAnim.animateFloat(0.2f, 0.7f, infiniteRepeatable(tween(900), RepeatMode.Reverse))

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(80.dp).padding(16.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeat(12) { i ->
                            val fraction = listOf(bar1, bar2, bar3, bar4, bar1, bar2, bar3, bar4, bar1, bar2, bar3, bar4)[i]
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(fraction)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        Brush.verticalGradient(listOf(AccentCyan, ElectricBlue))
                                    )
                            )
                        }
                    }
                }
            }

            // ── Institute list ──
            item {
                Text(
                    "All Institutes · ${institutes.size}",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (institutes.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No institutes registered yet.", color = TextMuted, fontSize = 14.sp)
                    }
                }
            } else {
                items(institutes, key = { it.id }) { inst ->
                    InstituteCard(inst)
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
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
private fun RevenueCard(amount: String, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.TrendingUp, null, tint = color, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Total Revenue", color = TextMuted, fontSize = 12.sp)
                Text(amount, color = TextWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun InstituteCard(inst: InstituteEntity) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val statusColor = when (inst.subscriptionStatus) {
        "active" -> AccentGreen
        "trial" -> AccentCyan
        "expired" -> AccentRed
        else -> TextMuted
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(listOf(AccentViolet.copy(alpha = 0.3f), ElectricBlue.copy(alpha = 0.15f)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    inst.name.take(2).uppercase(),
                    color = AccentViolet,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(inst.name, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("Plan: ${inst.currentPlanId} · Joined ${dateFormat.format(Date(inst.createdAtMs))}",
                    color = TextMuted, fontSize = 12.sp)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(statusColor.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    inst.subscriptionStatus.replaceFirstChar { it.uppercase() },
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
