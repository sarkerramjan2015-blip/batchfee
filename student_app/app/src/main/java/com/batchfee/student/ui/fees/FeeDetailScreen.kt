package com.batchfee.student.ui.fees

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
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
fun FeeDetailScreen(feeId: String, onBack: () -> Unit) {
    val studentId by SessionManager.currentStudentId.collectAsState()
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val repo = remember { StudentFirestoreRepository() }

    var fee by remember { mutableStateOf<Fee?>(null) }
    var payments by remember { mutableStateOf<List<Payment>>(emptyList()) }
    var student by remember { mutableStateOf<Student?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(feeId, studentId, instituteId) {
        val sid = studentId ?: return@LaunchedEffect
        val iid = instituteId ?: return@LaunchedEffect
        try {
            val fees = repo.getFees(iid, sid)
            fee = fees.find { it.id == feeId }
            payments = repo.getPayments(iid, sid).filter { it.feeId == feeId }
            student = repo.getStudent(iid, sid)
        } catch (_: Exception) { }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fee Details", fontWeight = FontWeight.Bold) },
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
        } else if (fee == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Fee not found", color = TextSecondaryLight)
            }
        } else {
            val f = fee!!
            val statusColor = when (f.status) {
                "paid" -> Color(0xFF10B981)
                "partially_paid" -> Color(0xFFF59E0B)
                "unpaid", "overdue" -> Color(0xFFEF4444)
                else -> TextSecondaryLight
            }
            val statusText = when (f.status) {
                "paid" -> "PAID"
                "partially_paid" -> "PARTIAL"
                "unpaid" -> "UNPAID"
                "overdue" -> "OVERDUE"
                "cancelled" -> "CANCELLED"
                else -> f.status.uppercase()
            }
            val progress = if (f.totalAmount > 0) (f.paidAmount / f.totalAmount).toFloat() else 0f

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // ════════════════════════════════════
                // HERO — Status Card
                // ════════════════════════════════════
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Box {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.linearGradient(
                                            colors = when (f.status) {
                                                "paid" -> listOf(Color(0xFF065F46), Color(0xFF059669))
                                                "partially_paid" -> listOf(Color(0xFF92400E), Color(0xFFD97706))
                                                else -> listOf(Color(0xFF991B1B), Color(0xFFDC2626))
                                            },
                                            start = Offset.Zero, end = Offset(400f, 200f)
                                        ),
                                        RoundedCornerShape(24.dp)
                                    )
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(f.feePeriod,
                                            color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                                        Text(f.feeType,
                                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color.White.copy(alpha = 0.2f)
                                    ) {
                                        Text(statusText,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }

                                Spacer(Modifier.height(24.dp))

                                // Amount
                                Text("\u09F3${String.format("%,.0f", f.totalAmount)}",
                                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 40.sp,
                                    letterSpacing = 1.sp)
                                Text("Total Amount", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)

                                Spacer(Modifier.height(16.dp))

                                // Progress Bar
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = Color.White,
                                    trackColor = Color.White.copy(alpha = 0.2f),
                                )

                                Spacer(Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("\u09F3${String.format("%,.0f", f.paidAmount)}",
                                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                        Text("Paid", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("\u09F3${String.format("%,.0f", f.dueAmount)}",
                                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                        Text("Due", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                                    }
                                    if (f.dueDateMs > 0) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                SimpleDateFormat("dd MMM", Locale.getDefault())
                                                    .format(Date(f.dueDateMs)),
                                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Text("Due Date", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ════════════════════════════════════
                // BREAKDOWN — Amount Details
                // ════════════════════════════════════
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Payment Breakdown",
                            fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 12.dp))

                        BreakdownRow("Base Amount", "\u09F3${String.format("%,.0f", f.baseAmount)}", Color(0xFF3B82F6))
                        if (f.discountAmount > 0) {
                            BreakdownRow("Discount", "-\u09F3${String.format("%,.0f", f.discountAmount)}", Color(0xFF10B981))
                        }
                        if (f.lateFeeAmount > 0) {
                            BreakdownRow("Late Fee", "+\u09F3${String.format("%,.0f", f.lateFeeAmount)}", Color(0xFFF59E0B))
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        BreakdownRow("Total", "\u09F3${String.format("%,.0f", f.totalAmount)}", MaterialTheme.colorScheme.onSurface, bold = true)
                        BreakdownRow("Paid", "\u09F3${String.format("%,.0f", f.paidAmount)}", Color(0xFF10B981), bold = true)
                        if (f.dueAmount > 0) {
                            BreakdownRow("Remaining Due", "\u09F3${String.format("%,.0f", f.dueAmount)}", Color(0xFFEF4444), bold = true)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ════════════════════════════════════
                // PAYMENT HISTORY for this fee
                // ════════════════════════════════════
                if (payments.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text("Payment History",
                                fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                modifier = Modifier.padding(bottom = 12.dp))

                            payments.forEachIndexed { index, payment ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier.size(36.dp),
                                        shape = CircleShape,
                                        color = StatusGreen.copy(alpha = 0.1f)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.Payment, null,
                                                tint = StatusGreen, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("\u09F3${String.format("%,.0f", payment.amount)}",
                                            fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(
                                            "${payment.paymentMethod.replaceFirstChar { it.uppercase() }} · ${
                                                SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                                                    .format(Date(payment.paymentDateMs))
                                            }",
                                            color = TextSecondaryLight, fontSize = 12.sp)
                                    }
                                    if (payment.receiptNumber.isNotEmpty()) {
                                        Text("#${payment.receiptNumber}",
                                            color = PrimaryBlue, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                                if (index < payments.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }
                }

                // ════════════════════════════════════
                // NOTES
                // ════════════════════════════════════
                if (f.note != null) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Filled.Info, null,
                                tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(f.note, color = Color(0xFF92400E), fontSize = 13.sp)
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    label: String,
    value: String,
    color: Color,
    bold: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label,
            color = if (bold) MaterialTheme.colorScheme.onSurface else TextSecondaryLight,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp)
        Text(value,
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp)
    }
}
