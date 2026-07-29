package com.batchfee.edu.ui.reports

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val CardBgAlt = Color(0xFF111827)
private val BorderSub = Color(0xFF1E293B)
private val Cyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val AccentGreen = Color(0xFF10B981)
private val AccentAmber = Color(0xFFF59E0B)
private val AccentViolet = Color(0xFF8B5CF6)

private fun formatAmount(amount: Double): String {
    val fmt = NumberFormat.getNumberInstance(Locale("en", "IN"))
    fmt.minimumFractionDigits = 0
    fmt.maximumFractionDigits = 0
    return fmt.format(amount)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(db: AppDatabase, period: String = "today", onBack: () -> Unit) {
    val viewModel: ReportsViewModel = viewModel(factory = ReportsViewModelFactory(db, period))
    val studentCount by viewModel.studentCount.collectAsState()
    val grandTotal by viewModel.grandTotal.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Flat list (today)
    val payments by viewModel.payments.collectAsState()
    // Day groups (month)
    val dayGroups by viewModel.dayGroups.collectAsState()
    // Month groups (lifetime)
    val monthGroups by viewModel.monthGroups.collectAsState()
    // Drill-down
    val selectedMonth by viewModel.selectedMonth.collectAsState()
    val selectedDay by viewModel.selectedDay.collectAsState()

    val isDrilled = selectedMonth != null || selectedDay != null

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(viewModel.drillTitle, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "${viewModel.periodLabel} · $studentCount students",
                            color = TextMuted, fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (isDrilled) { { viewModel.goBack() } } else onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cyan, strokeWidth = 3.dp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Grand Total Card
                item {
                    GrandTotalCard(
                        total = grandTotal,
                        label = viewModel.periodLabel,
                        period = period
                    )
                }

                when {
                    // Drilled into a month → show days
                    selectedMonth != null -> {
                        val month = selectedMonth!!
                        item {
                            MonthHeader(month.label, month.count, month.total)
                        }
                        if (month.days.isEmpty()) {
                            item { EmptyState("No payments in ${month.label}") }
                        } else {
                            items(month.days, key = { it.dateMs }) { day ->
                                DayRow(
                                    day = day,
                                    onClick = { viewModel.drillIntoDay(day) }
                                )
                            }
                        }
                    }
                    // Drilled into a day → show payments
                    selectedDay != null -> {
                        val day = selectedDay!!
                        item {
                            DayHeader(day.label, day.count, day.total)
                        }
                        if (day.payments.isEmpty()) {
                            item { EmptyState("No details") }
                        } else {
                            items(day.payments, key = { it.payment.id }) { item ->
                                PaymentCard(item)
                            }
                        }
                    }
                    // Today → show flat payment list
                    period == "today" -> {
                        item { SectionLabel("All Payments") }
                        if (payments.isEmpty()) {
                            item { EmptyState("No payments today") }
                        } else {
                            items(payments, key = { it.payment.id }) { item ->
                                PaymentCard(item)
                            }
                        }
                    }
                    // Month → show day groups
                    period == "month" -> {
                        item { SectionLabel("Day-wise Collection") }
                        if (dayGroups.isEmpty()) {
                            item { EmptyState("No payments this month") }
                        } else {
                            items(dayGroups, key = { it.dateMs }) { day ->
                                DayRow(
                                    day = day,
                                    onClick = { viewModel.drillIntoDay(day) }
                                )
                            }
                        }
                    }
                    // Lifetime → show month groups
                    period == "lifetime" -> {
                        item { SectionLabel("Month-wise Collection") }
                        if (monthGroups.isEmpty()) {
                            item { EmptyState("No payment history") }
                        } else {
                            items(monthGroups, key = { it.monthKey }) { month ->
                                MonthRow(
                                    month = month,
                                    onClick = { viewModel.drillIntoMonth(month) }
                                )
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun GrandTotalCard(total: Double, label: String, period: String) {
    val accent = when (period) {
        "month" -> AccentAmber
        "lifetime" -> AccentViolet
        else -> Cyan
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(label.uppercase(), color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "BDT ${formatAmount(total)}",
                color = accent,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text("Total Collected", color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun MonthHeader(label: String, count: Int, total: Double) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CalendarMonth, null, tint = AccentViolet, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("$count pay · BDT ${formatAmount(total)}", color = AccentViolet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = BorderSub, thickness = 1.dp)
    }
}

@Composable
private fun DayHeader(label: String, count: Int, total: Double) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Today, null, tint = AccentAmber, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("$count · BDT ${formatAmount(total)}", color = AccentAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = BorderSub, thickness = 1.dp)
    }
}

@Composable
private fun MonthRow(month: MonthGroup, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBgAlt),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(AccentViolet.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.CalendarMonth, null, tint = AccentViolet, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(month.label, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${month.count} payments", color = TextMuted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("BDT ${formatAmount(month.total)}", color = AccentViolet, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${month.days.size} days", color = TextMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = TextMuted.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun DayRow(day: DayGroup, onClick: () -> Unit) {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = day.dateMs }
    val dayNum = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val dayName = SimpleDateFormat("EEE", Locale.getDefault()).format(day.dateMs)

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBgAlt),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Date badge
            Column(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(ElectricBlue, Cyan))),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(dayName, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(dayNum.toString(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${day.count} payment${if (day.count > 1) "s" else ""}", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("BDT ${formatAmount(day.total)}", color = AccentGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("tap to view", color = TextMuted, fontSize = 10.sp)
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = TextMuted.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PaymentCard(item: PaymentItem) {
    val payment = item.payment
    val student = item.student
    val timeFmt = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(ElectricBlue.copy(alpha = 0.3f), Cyan.copy(alpha = 0.12f)))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    student?.fullName?.firstOrNull()?.uppercase() ?: "?",
                    color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    student?.fullName ?: "Unknown Student",
                    color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${timeFmt.format(Date(payment.paymentDateMs))} · ${payment.paymentMethod.uppercase()}",
                    color = TextMuted, fontSize = 11.sp
                )
            }
            Text(
                "BDT ${formatAmount(payment.amount)}",
                color = AccentGreen, fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ReceiptLong, null, tint = TextMuted.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(10.dp))
            Text(message, color = TextMuted.copy(alpha = 0.6f), fontSize = 14.sp)
        }
    }
}
