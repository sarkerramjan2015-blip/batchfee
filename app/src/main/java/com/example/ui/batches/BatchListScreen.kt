package com.example.ui.batches

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.models.FeeEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// ── Colors ── matching StudentListScreen premium palette ────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val SkyBlue       = Color(0xFF38BDF8)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val WAGreen       = Color(0xFF25D366)
private val AccentGreen   = Color(0xFF22C55E)
private val AccentRed     = Color(0xFFEF4444)

// ── Per-batch this-month stats ──────────────────────────────────
private data class BatchMonthStats(
    val enrolled: Int = 0,
    val paidThisMonth: Int = 0,
    val dueThisMonth: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchListScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onAddBatch: () -> Unit,
    onNavigateToBatch: (String) -> Unit
) {
    val viewModel: BatchViewModel = viewModel(factory = BatchViewModelFactory(db))
    val batches by viewModel.batchList.collectAsState()
    val scope = rememberCoroutineScope()
    val instId = SessionManager.currentInstituteId.collectAsState().value
    val currentMonth = remember { currentMonthPeriod() }

    // Load per-batch this-month stats
    var batchStats by remember { mutableStateOf<Map<String, BatchMonthStats>>(emptyMap()) }
    var statsLoading by remember { mutableStateOf(true) }

    LaunchedEffect(instId, batches) {
        if (instId == null) return@LaunchedEffect
        statsLoading = true
        val stats = mutableMapOf<String, BatchMonthStats>()
        batches.forEach { batch ->
            scope.launch {
                combine(
                    db.batchStudentDao().getStudentsForBatch(batch.id, instId),
                    db.feeDao().getFeesByBatch(batch.id, instId)
                ) { students, fees ->
                    val enrolled = students.size
                    val currentMonthFees = fees.filter { it.feePeriod.equals(currentMonth, ignoreCase = true) }
                    val paid = currentMonthFees.count { it.status == "paid" }
                    val due = currentMonthFees.count { it.dueAmount > 0 }
                    BatchMonthStats(enrolled, paid, due)
                }.collect { stat ->
                    batchStats = batchStats + (batch.id to stat)
                    if (batchStats.size == batches.size) statsLoading = false
                }
            }
        }
        if (batches.isEmpty()) statsLoading = false
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Batches", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddBatch,
                containerColor = Color.Transparent,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Batch", tint = Color.White)
            }
        }
    ) { padding ->
        if (batches.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Class, contentDescription = null,
                        tint = TextMuted.copy(alpha = 0.5f), modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No batches yet.\nTap + to create your first batch.",
                        color = TextMuted, fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
            }
        } else {
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${batches.size} batch${if (batches.size != 1) "es" else ""}",
                        color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                    if (statsLoading) {
                        CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(batches, key = { it.id }) { batch ->
                        val stats = batchStats[batch.id]
                        val enrolled = stats?.enrolled ?: 0
                        val paid = stats?.paidThisMonth ?: 0
                        val due = stats?.dueThisMonth ?: 0

                        BatchCard(
                            batch = batch,
                            enrolled = enrolled,
                            paidThisMonth = paid,
                            dueThisMonth = due,
                            onClick = { onNavigateToBatch(batch.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchCard(
    batch: com.example.data.models.BatchEntity,
    enrolled: Int,
    paidThisMonth: Int,
    dueThisMonth: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.25f)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        batch.name.take(1).uppercase(),
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(batch.name, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text("BDT ${"%.0f".format(batch.monthlyFeeAmount)}/mo", color = TextMuted, fontSize = 12.sp)
                }
                // Right arrow indicator
                Icon(Icons.Filled.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatChip("Enrolled", "$enrolled", SkyBlue)
                StatChip("Paid (this month)", "$paidThisMonth", WAGreen)
                StatChip("Due (this month)", "$dueThisMonth", if (dueThisMonth > 0) AccentRed else TextMuted)
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}
