package com.example.ui.fees

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase

// ── Premium palette ───────────────────────────────────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val AccentRed     = Color(0xFFEF4444)
private val AccentRedBg   = Color(0xFFEF4444).copy(alpha = 0.10f)
private val AccentAmber   = Color(0xFFF59E0B)
private val AccentAmberBg = Color(0xFFF59E0B).copy(alpha = 0.10f)
private val AccentGreen   = Color(0xFF10B981)
private val AccentGreenBg = Color(0xFF10B981).copy(alpha = 0.10f)

// ── Helper: Short month+year label for month-wise cards ─────
private fun shortMonthLabel(period: String): String {
    val parts = period.trim().split("\\s+".toRegex())
    if (parts.size < 2) return period
    return "${parts[0].take(3)} ${parts[1].takeLast(2)}"  // e.g. "Jan 26"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeeDashboardScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onNavigateDueFees: () -> Unit,
    onCreateFee: () -> Unit,
    onCollectPayment: (String) -> Unit
) {
    val viewModel: FeeViewModel = viewModel(factory = FeeViewModelFactory(db))
    val totalDueAmount by viewModel.totalDueAmount.collectAsState()
    val dueFeesWithDetails by viewModel.dueFeesWithDetails.collectAsState()
    val monthWiseDues by viewModel.monthWiseDues.collectAsState()
    val totalCollected by viewModel.totalCollected.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Collection Fee", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // ═════════════════════════════════════════════════════
            //  SECTION 1: Total Due Amount — Read-Only Card
            // ═════════════════════════════════════════════════════
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .shadow(6.dp, RoundedCornerShape(16.dp), spotColor = AccentRed.copy(alpha = 0.25f)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Total Due Amount", color = TextMuted, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "BDT ${"%.2f".format(totalDueAmount)}",
                                color = AccentRed,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(AccentRedBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Warning, null, tint = AccentRed, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (totalDueAmount > 0) {
                        Text(
                            "${dueFeesWithDetails.size} pending fee ${if (dueFeesWithDetails.size == 1) "entry" else "entries"} across ${dueFeesWithDetails.distinctBy { it.studentId }.size} student(s)",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (totalDueAmount > 0) {

                // ═════════════════════════════════════════════════
                //  SECTION 2: Month-Wise Breakdown — Read-Only
                // ═════════════════════════════════════════════════
                Text(
                    "Month-wise Breakdown",
                    color = TextWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(end = 8.dp)
                ) {
                    items(monthWiseDues) { month ->
                        Card(
                            modifier = Modifier
                                .width(130.dp)
                                .height(82.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = CardBgAlt),
                            border = BorderStroke(1.dp, BorderSub)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    shortMonthLabel(month.monthLabel),
                                    color = TextWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "BDT ${"%.0f".format(month.totalDue)}",
                                    color = AccentRed,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    "${month.studentCount} student(s)",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ═════════════════════════════════════════════════
                //  SECTION 3: Pending Dues List — Read-Only
                // ═════════════════════════════════════════════════
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Pending Dues by Student",
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${dueFeesWithDetails.distinctBy { it.studentId }.size} student(s)",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(8.dp))

                dueFeesWithDetails.groupBy { it.studentId }.forEach { (_, fees) ->
                    val first = fees.first()
                    val totalDueForStudent = fees.sumOf { it.dueAmount }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .shadow(3.dp, RoundedCornerShape(14.dp), spotColor = BorderSub),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, BorderSub)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // ── Student header ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        first.studentName,
                                        color = TextWhite,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (!first.batchName.isBlank()) {
                                        Text(
                                            first.batchName,
                                            color = TextMuted,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        "Due: BDT ${"%.0f".format(totalDueForStudent)}",
                                        color = AccentRed,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "${fees.size} period${if (fees.size > 1) "s" else ""}",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // ── Period breakdown ──
                            fees.forEach { fee ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.CalendarMonth,
                                            null,
                                            tint = TextMuted.copy(alpha = 0.6f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            fee.feePeriod,
                                            color = TextMuted,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Text(
                                        "BDT ${"%.0f".format(fee.dueAmount)}",
                                        color = if (fee.status == "unpaid") AccentRed else AccentAmber,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            // ── SMS / WhatsApp notification buttons ──
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // SMS button
                                OutlinedButton(
                                    onClick = {
                                        viewModel.sendDueNotification(
                                            context = context,
                                            studentName = first.studentName,
                                            phone = first.studentPhone,
                                            dueAmount = totalDueForStudent,
                                            feePeriod = "multiple periods",
                                            channel = "sms"
                                        )
                                    },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, BorderSub),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricBlue),
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Icon(Icons.Filled.Sms, null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("SMS", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                // WhatsApp button
                                OutlinedButton(
                                    onClick = {
                                        viewModel.sendDueNotification(
                                            context = context,
                                            studentName = first.studentName,
                                            phone = first.studentPhone,
                                            dueAmount = totalDueForStudent,
                                            feePeriod = "multiple periods",
                                            channel = "whatsapp"
                                        )
                                    },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, BorderSub),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Icon(Icons.Filled.Chat, null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("WhatsApp", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            } else {
                // ── No pending dues ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            null,
                            tint = AccentGreen.copy(alpha = 0.6f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No pending dues!",
                            color = AccentGreen,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "All fees are collected. Great job!",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ═════════════════════════════════════════════════════
            //  SECTION 4: Total Collected Summary
            // ═════════════════════════════════════════════════════
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = AccentGreen.copy(alpha = 0.20f)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Total Collected", color = TextMuted, fontSize = 14.sp)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AccentGreenBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Payments, null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "BDT ${"%.2f".format(totalCollected)}",
                        color = TextWhite,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // ═════════════════════════════════════════════════════
            //  SECTION 5: Action Buttons
            // ═════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Create Fee button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                        )
                        .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(
                        onClick = onCreateFee,
                        modifier = Modifier.fillMaxSize(),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Create Fee", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                // View Due Fees button
                OutlinedButton(
                    onClick = onNavigateDueFees,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Cyan),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                ) {
                    Icon(Icons.Filled.ReceiptLong, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Collect Fee", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
