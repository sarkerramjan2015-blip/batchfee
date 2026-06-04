package com.example.ui.reports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.data.models.PaymentEntity
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val BorderSub = Color(0xFF1E293B)
private val Cyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val AccentGreen = Color(0xFF10B981)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: ReportsViewModel = viewModel(factory = ReportsViewModelFactory(db))
    val studentCount by viewModel.studentCount.collectAsState()
    val todayPayments by viewModel.todayPayments.collectAsState()
    val todayTotal by viewModel.todayTotal.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Today's Collection",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextWhite
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Cyan, strokeWidth = 3.dp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── Summary header ────────────────────────
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .shadow(4.dp, RoundedCornerShape(16.dp), spotColor = Cyan.copy(alpha = 0.20f)),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, BorderSub)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    viewModel.todayLabel,
                                    color = TextMuted,
                                    fontSize = 13.sp
                                )
                                Text(
                                    "${todayPayments.size} payments",
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "BDT ${formatAmount(todayTotal)}",
                                color = AccentGreen,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Collected Today",
                                color = Cyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // ── Student count pill ────────────────────
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Payment History",
                            color = TextWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(ElectricBlue.copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "$studentCount students",
                                color = Cyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (todayPayments.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.CalendarToday,
                                    contentDescription = null,
                                    tint = TextMuted.copy(alpha = 0.4f),
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "No payments collected today.",
                                    color = TextMuted,
                                    fontSize = 15.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Payments will appear here once collected.",
                                    color = TextMuted.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                } else {
                    items(todayPayments, key = { it.payment.id }) { item ->
                        PaymentHistoryCard(item)
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun PaymentHistoryCard(item: TodayPaymentItem) {
    val payment = item.payment
    val student = item.student
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp), spotColor = AccentGreen.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Student avatar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(ElectricBlue.copy(alpha = 0.3f), Cyan.copy(alpha = 0.12f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = Cyan,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    student?.fullName ?: "Unknown",
                    color = TextWhite,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        payment.paymentMethod.replaceFirstChar { it.uppercase() },
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        timeFormat.format(Date(payment.paymentDateMs)),
                        color = TextMuted.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
            }

            // Amount
            Text(
                "BDT ${formatAmount(payment.amount)}",
                color = AccentGreen,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatAmount(amount: Double): String {
    return NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 0
    }.format(amount)
}
