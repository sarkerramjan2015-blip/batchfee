package com.batchfee.student.ui.fees

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.student.data.firebase.StudentFirestoreRepository
import com.batchfee.student.data.models.*
import com.batchfee.student.domain.SessionManager
import com.batchfee.student.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeesScreen(
    onBack: () -> Unit,
    onFeeDetail: (String) -> Unit,
    onReceiptView: (String) -> Unit
) {
    val studentId by SessionManager.currentStudentId.collectAsState()
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    var fees by remember { mutableStateOf<List<Fee>>(emptyList()) }
    var payments by remember { mutableStateOf<List<Payment>>(emptyList()) }
    var receipts by remember { mutableStateOf<List<Receipt>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val repo = remember { StudentFirestoreRepository() }

    // Animation flags
    val heroVisible = remember { mutableStateOf(false) }
    val listVisible = remember { mutableStateOf(false) }

    LaunchedEffect(studentId, instituteId) {
        val sid = studentId ?: return@LaunchedEffect
        val iid = instituteId ?: return@LaunchedEffect
        try {
            fees = repo.getFees(iid, sid)
            payments = repo.getPayments(iid, sid)
            receipts = repo.getReceipts(iid, sid)
        } catch (_: Exception) { }
        isLoading = false
        heroVisible.value = true
        listVisible.value = true
    }

    val totalAmount = fees.sumOf { it.totalAmount }
    val totalPaid = fees.filter { it.status == "paid" || it.status == "partially_paid" }
        .sumOf { it.paidAmount }
    val totalDue = fees.filter { it.status == "unpaid" || it.status == "overdue" || it.status == "partially_paid" }
        .sumOf { it.dueAmount }
    val paidCount = fees.count { it.status == "paid" }
    val totalCount = fees.size
    val progress = if (totalAmount > 0) (totalPaid / totalAmount).toFloat() else 0f

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fee Report", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryBlue, strokeWidth = 3.dp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // ════════════════════════════════════════
                //  HERO SECTION — Overall Fee Summary
                // ════════════════════════════════════════
                item {
                    AnimatedVisibility(
                        visible = heroVisible.value,
                        enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 3 }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Hero Card
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
                            ) {
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                Brush.linearGradient(
                                                    colors = listOf(
                                                        Color(0xFF0F172A),
                                                        Color(0xFF1E293B),
                                                        Color(0xFF334155)
                                                    ),
                                                    start = Offset.Zero,
                                                    end = Offset(600f, 400f)
                                                ),
                                                RoundedCornerShape(24.dp)
                                            )
                                    )
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("Total Fees",
                                                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                                Text("\u09F3${String.format("%,.0f", totalAmount)}",
                                                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                                            }
                                            Surface(
                                                modifier = Modifier.size(52.dp),
                                                shape = CircleShape,
                                                color = Color.White.copy(alpha = 0.1f)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Filled.AccountBalanceWallet, null,
                                                        tint = Color.White, modifier = Modifier.size(26.dp))
                                                }
                                            }
                                        }

                                        Spacer(Modifier.height(20.dp))

                                        // Progress Bar
                                        LinearProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(10.dp)
                                                .clip(RoundedCornerShape(5.dp)),
                                            color = Color(0xFF22D3EE),
                                            trackColor = Color.White.copy(alpha = 0.15f),
                                        )

                                        Spacer(Modifier.height(12.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("${String.format("%.0f", progress * 100)}% completed",
                                                color = Color(0xFF22D3EE), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                            Text("${paidCount}/${totalCount} months paid",
                                                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            // Paid & Due Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Paid Card
                                Card(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(18.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFFECFDF5)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(36.dp),
                                            shape = CircleShape,
                                            color = Color(0xFF10B981).copy(alpha = 0.15f)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Filled.CheckCircle, null,
                                                    tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text("\u09F3${String.format("%,.0f", totalPaid)}",
                                            fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF059669))
                                        Text("Paid", fontSize = 12.sp, color = Color(0xFF059669).copy(alpha = 0.7f))
                                    }
                                }

                                // Due Card
                                Card(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(18.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (totalDue > 0) Color(0xFFFEF2F2) else Color(0xFFECFDF5)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(36.dp),
                                            shape = CircleShape,
                                            color = (if (totalDue > 0) Color(0xFFEF4444) else Color(0xFF10B981)).copy(alpha = 0.15f)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    if (totalDue > 0) Icons.Filled.TrendingUp else Icons.Filled.CheckCircle,
                                                    null,
                                                    tint = if (totalDue > 0) Color(0xFFEF4444) else Color(0xFF10B981),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        Text("\u09F3${String.format("%,.0f", totalDue)}",
                                            fontWeight = FontWeight.Bold, fontSize = 20.sp,
                                            color = if (totalDue > 0) Color(0xFFDC2626) else Color(0xFF059669))
                                        Text(if (totalDue > 0) "Due" else "Clear",
                                            fontSize = 12.sp,
                                            color = if (totalDue > 0) Color(0xFFDC2626).copy(alpha = 0.7f) else Color(0xFF059669).copy(alpha = 0.7f))
                                    }
                                }
                            }
                        }
                    }
                }

                // ════════════════════════════════════════
                //  TABS
                // ════════════════════════════════════════
                item {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = PrimaryBlue
                    ) {
                        listOf("Fees", "History", "Receipts").forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = { Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp) }
                            )
                        }
                    }
                }

                // ════════════════════════════════════════
                //  TAB 0 — FEE LIST
                // ════════════════════════════════════════
                if (selectedTab == 0) {
                    if (fees.isEmpty()) {
                        item { EmptyState("No fee records found") }
                    } else {
                        items(fees) { fee ->
                            FeeCard(fee = fee, onClick = { onFeeDetail(fee.id) })
                        }
                    }
                }

                // ════════════════════════════════════════
                //  TAB 1 — PAYMENT HISTORY
                // ════════════════════════════════════════
                if (selectedTab == 1) {
                    if (payments.isEmpty()) {
                        item { EmptyState("No payment history yet") }
                    } else {
                        // Monthly summary header
                        val totalPayments = payments.sumOf { it.amount }
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Total Payments", fontSize = 12.sp, color = TextSecondaryLight)
                                        Text("\u09F3${String.format("%,.0f", totalPayments)}",
                                            fontWeight = FontWeight.Bold, fontSize = 22.sp, color = StatusGreen)
                                    }
                                    Surface(
                                        modifier = Modifier.size(44.dp),
                                        shape = CircleShape,
                                        color = StatusGreen.copy(alpha = 0.1f)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.AccountBalance, null,
                                                tint = StatusGreen, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                }
                            }
                        }

                        items(payments) { payment ->
                            PaymentHistoryCard(payment = payment)
                        }
                    }
                }

                // ════════════════════════════════════════
                //  TAB 2 — RECEIPTS
                // ════════════════════════════════════════
                if (selectedTab == 2) {
                    if (receipts.isEmpty()) {
                        item { EmptyState("No receipts found") }
                    } else {
                        items(receipts) { receipt ->
                            ReceiptCard(receipt = receipt, onClick = { onReceiptView(receipt.id) })
                        }
                    }
                }
            }
        }
    }
}

// ── Fee Card ──
@Composable
private fun FeeCard(fee: Fee, onClick: () -> Unit) {
    val statusColor = when (fee.status) {
        "paid" -> Color(0xFF10B981)
        "partially_paid" -> Color(0xFFF59E0B)
        "unpaid", "overdue" -> Color(0xFFEF4444)
        else -> TextSecondaryLight
    }
    val statusText = when (fee.status) {
        "paid" -> "✓ Paid"
        "partially_paid" -> "⚠ Partial"
        "unpaid" -> "✗ Unpaid"
        "overdue" -> "!! Overdue"
        "cancelled" -> "Cancelled"
        else -> fee.status
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            Surface(
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(14.dp),
                color = statusColor.copy(alpha = 0.12f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when (fee.status) {
                            "paid" -> Icons.Filled.CheckCircle
                            "partially_paid" -> Icons.Filled.HourglassBottom
                            "overdue" -> Icons.Filled.Error
                            else -> Icons.Filled.ErrorOutline
                        },
                        null, tint = statusColor, modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))

            // Fee info
            Column(modifier = Modifier.weight(1f)) {
                Text(fee.feePeriod, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fee.feeType, color = TextSecondaryLight, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = statusColor.copy(alpha = 0.12f)
                    ) {
                        Text(statusText,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (fee.dueDateMs > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Due: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(fee.dueDateMs))}",
                        fontSize = 11.sp, color = TextSecondaryLight)
                }
            }

            // Amount column
            Column(horizontalAlignment = Alignment.End) {
                Text("\u09F3${String.format("%,.0f", fee.totalAmount)}",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (fee.dueAmount > 0) {
                    Text("Due: \u09F3${String.format("%,.0f", fee.dueAmount)}",
                        fontSize = 11.sp, color = StatusRed, fontWeight = FontWeight.Medium)
                } else {
                    Text("Cleared", fontSize = 11.sp, color = StatusGreen, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ── Payment History Card ──
@Composable
private fun PaymentHistoryCard(payment: Payment) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = StatusGreen.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Payment, null, tint = StatusGreen, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("\u09F3${String.format("%,.0f", payment.amount)}",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(payment.paymentMethod.replaceFirstChar { it.uppercase() },
                        color = TextSecondaryLight, fontSize = 12.sp)
                    Text(" · ", color = TextSecondaryLight, fontSize = 12.sp)
                    Text(
                        SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                            .format(Date(payment.paymentDateMs)),
                        color = TextSecondaryLight, fontSize = 12.sp)
                }
            }
            if (payment.receiptNumber.isNotEmpty()) {
                Text("#${payment.receiptNumber}",
                    fontSize = 11.sp, color = PrimaryBlue, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── Receipt Card ──
@Composable
private fun ReceiptCard(receipt: Receipt, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFDBEAFE)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Receipt, null, tint = Color(0xFF2563EB), modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(receipt.receiptNumber, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("\u09F3${String.format("%,.0f", receipt.paidAmount)}",
                        fontWeight = FontWeight.Bold, fontSize = 13.sp, color = StatusGreen)
                    Text(" via ${receipt.paymentMethod}", color = TextSecondaryLight, fontSize = 12.sp)
                }
            }
            Icon(Icons.Filled.ChevronRight, null,
                tint = TextSecondaryLight.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
    }
}

// ── Empty State ──
@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Receipt, null,
                modifier = Modifier.size(56.dp), tint = TextSecondaryLight.copy(alpha = 0.4f))
            Spacer(Modifier.height(12.dp))
            Text(message, color = TextSecondaryLight, fontSize = 14.sp)
        }
    }
}
