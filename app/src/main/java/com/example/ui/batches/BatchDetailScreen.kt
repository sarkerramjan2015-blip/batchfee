package com.example.ui.batches

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.example.data.models.PaymentEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

// ── Colors ──────────────────────────────────────────────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val SkyBlue       = Color(0xFF38BDF8)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val WAGreen       = Color(0xFF25D366)
private val AccentRed     = Color(0xFFEF4444)
private val AccentAmber   = Color(0xFFF59E0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchDetailScreen(
    db: AppDatabase,
    batchId: String,
    onBack: () -> Unit,
    onEnroll: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paymentVM: BatchPaymentViewModel = viewModel(factory = BatchPaymentViewModelFactory(db))
    val batch by paymentVM.batch.collectAsState()
    val studentsWF by paymentVM.studentsWithFee.collectAsState()
    val totalEnrolled by paymentVM.totalEnrolled.collectAsState()
    val paidCount by paymentVM.paidCount.collectAsState()
    val dueCount by paymentVM.dueCount.collectAsState()
    val totalExpected by paymentVM.totalExpected.collectAsState()
    val totalCollected by paymentVM.totalCollected.collectAsState()
    val isLoading by paymentVM.isLoading.collectAsState()

    // This-month stats
    val paidThisMonth by paymentVM.paidThisMonthCount.collectAsState()
    val dueThisMonth by paymentVM.dueThisMonthCount.collectAsState()

    // Message tracking
    val sentIds by paymentVM.sentMessageIds.collectAsState()
    val sendingIds by paymentVM.sendingMessageIds.collectAsState()
    val isSendingAll by paymentVM.isSendingAll.collectAsState()

    // Filters & sort
    var searchQuery by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf("all") }
    var sortBy by remember { mutableStateOf("name") }
    var showPaymentHistoryFor by remember { mutableStateOf<String?>(null) }
    var historyPayments by remember { mutableStateOf<List<PaymentEntity>>(emptyList()) }

    // Dialogs
    var sendMessageTarget by remember { mutableStateOf<BatchStudentWithFee?>(null) }
    var sendAllDueChoice by remember { mutableStateOf(false) }

    // Load data on first composition
    LaunchedEffect(batchId) { paymentVM.loadBatchDetail(batchId) }

    // ── Filter + sort + search ────────────────────────────
    val monthlyFee = batch?.monthlyFeeAmount ?: 0.0
    val displayedStudents = remember(studentsWF, searchQuery, filterStatus, sortBy) {
        var list = when (filterStatus) {
            "paid" -> studentsWF.filter { it.feeStatus == "paid" }
            "due" -> studentsWF.filter { it.dueAmount > 0 && it.paidAmount == 0.0 }
            "partial" -> studentsWF.filter { it.dueAmount > 0 && it.paidAmount > 0 }
            "no_fee" -> studentsWF.filter { it.fee == null }
            else -> studentsWF
        }
        if (searchQuery.isNotBlank()) {
            list = list.filter {
                it.student.fullName.contains(searchQuery, ignoreCase = true) ||
                it.student.studentCode.contains(searchQuery, ignoreCase = true)
            }
        }
        when (sortBy) {
            "due_amount" -> list.sortedByDescending { it.dueAmount }
            "monthly_fee" -> list.sortedByDescending { monthlyFee }
            else -> list.sortedBy { it.student.fullName.lowercase() }
        }
    }

    // ── Send message helpers ──────────────────────────────
    fun buildDueMessage(target: BatchStudentWithFee): String {
        val months = target.monthsDue(monthlyFee)
        val monthText = if (months > 1) "$months months" else (target.fee?.feePeriod ?: "this month")
        return "Dear Parent, Fee for ${target.student.fullName} — " +
                "$monthText due: BDT ${target.dueAmount.toLong()}. " +
                "Batch: ${batch?.name ?: ""}. Please clear at your earliest. - BatchFee"
    }

    fun openWhatsApp(target: BatchStudentWithFee) {
        val msg = buildDueMessage(target)
        val encoded = URLEncoder.encode(msg, "UTF-8")
        val phone = target.student.phone?.takeIf { it.isNotBlank() }
        val url = if (phone != null) "https://wa.me/88$phone?text=$encoded"
                  else "https://wa.me/?text=$encoded"
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$encoded"))) }
    }

    fun openSMS(target: BatchStudentWithFee) {
        val msg = buildDueMessage(target)
        val phone = target.student.phone?.takeIf { it.isNotBlank() }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phone ?: ""}"))
        intent.putExtra("sms_body", msg)
        try { context.startActivity(intent) }
        catch (_: Exception) {
            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply { putExtra("sms_body", msg) })
        }
    }

    fun sendSingle(target: BatchStudentWithFee, channel: String) {
        paymentVM.markSending(target.student.id)
        try {
            when (channel) {
                "whatsapp" -> openWhatsApp(target)
                "sms" -> openSMS(target)
            }
            paymentVM.markSent(target.student.id)
        } catch (_: Exception) {
            paymentVM.markMessageError(target.student.id)
        }
    }

    fun sendAllDue(channel: String) {
        val due = studentsWF.filter { it.dueAmount > 0 }
        paymentVM.markSendingAll()
        try {
            if (due.isNotEmpty()) {
                val batchName = batch?.name ?: "Batch"
                val lines = due.joinToString("\n") { s ->
                    "${
                        s.student.fullName
                    } — Due BDT ${s.dueAmount.toLong()} (${s.fee?.feePeriod ?: "N/A"})"
                }
                val msg = "BatchFee — $batchName\nDue Fees:\n$lines"
                when (channel) {
                    "whatsapp" -> {
                        val encoded = URLEncoder.encode(msg, "UTF-8")
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$encoded")))
                    }
                    "sms" -> {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply { putExtra("sms_body", msg) })
                    }
                }
            }
            paymentVM.markSentAll()
        } catch (_: Exception) {
            paymentVM.markSendAllError()
        }
    }

    // ── Scaffold ─────────────────────────────────────────
    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text(batch?.name ?: "Batch Details", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cyan, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Loading batch data...", color = TextMuted, fontSize = 13.sp)
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            // ── Batch Summary Card ────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Payments, null, tint = Cyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("BDT ${"%.0f".format(monthlyFee)}/mo", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    // Total stats row
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SummaryStat("Students", "$totalEnrolled", SkyBlue)
                        SummaryStat("Paid", "$paidCount", WAGreen)
                        SummaryStat("Due", "$dueCount", if (dueCount > 0) AccentRed else TextMuted)
                    }
                    Spacer(Modifier.height(6.dp))
                    // This-month stats row
                    Text("This Month (${currentMonthPeriod()})", color = TextMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        SummaryStatSmall("Paid", "$paidThisMonth", WAGreen)
                        SummaryStatSmall("Due", "$dueThisMonth", if (dueThisMonth > 0) AccentRed else TextMuted)
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = BorderSub)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Expected: BDT ${"%.0f".format(totalExpected)}", color = TextMuted, fontSize = 12.sp)
                        Text("Collected: BDT ${"%.0f".format(totalCollected)}", color = WAGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ── Action bar ────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                outlinenedButton(
                    text = if (isSendingAll) "Sending..." else "Send All Due",
                    icon = {
                        if (isSendingAll) CircularProgressIndicator(color = WAGreen, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        else Icon(Icons.Filled.Message, null, tint = WAGreen, modifier = Modifier.size(16.dp))
                    },
                    color = WAGreen,
                    enabled = !isSendingAll && studentsWF.any { it.dueAmount > 0 },
                    onClick = { sendAllDueChoice = true }
                )
                outlinenedButton(
                    text = "Enroll",
                    icon = { Icon(Icons.Filled.PersonAdd, null, tint = Cyan, modifier = Modifier.size(16.dp)) },
                    color = Cyan,
                    enabled = true,
                    onClick = onEnroll
                )
            }

            // ── Search bar ────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search students...", color = TextMuted.copy(alpha = 0.5f), fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = outlineFieldColors(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))

            // ── Filters + sort ────────────────────────────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("all" to "All", "paid" to "Paid", "due" to "Due", "partial" to "Partial").forEach { (f, label) ->
                        FilterChip(
                            selected = filterStatus == f,
                            onClick = { filterStatus = f },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricBlue.copy(alpha = 0.2f),
                                selectedLabelColor = Cyan
                            )
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort:", color = TextMuted, fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    listOf("name" to "Name", "due_amount" to "Due", "monthly_fee" to "Fee").forEach { (s, label) ->
                        Text(
                            label,
                            color = if (sortBy == s) Cyan else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = if (sortBy == s) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable { sortBy = s }.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── Student list ──────────────────────────────
            if (displayedStudents.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (studentsWF.isEmpty()) "No students enrolled yet." else "No students match the filter.",
                        color = TextMuted, fontSize = 14.sp
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    displayedStudents.forEach { swf ->
                        val isSent = swf.student.id in sentIds
                        val isSending = swf.student.id in sendingIds
                        BatchStudentCard(
                            studentWF = swf,
                            monthlyFee = monthlyFee,
                            isDueMsgSent = isSent,
                            isSendingDueMsg = isSending,
                            onSendDueMessage = {
                                if (!isSent && !isSending) sendMessageTarget = swf
                            },
                            onViewHistory = {
                                showPaymentHistoryFor = swf.student.id
                                scope.launch {
                                    val instId = SessionManager.currentInstituteId.value ?: return@launch
                                    var done = false
                                    db.feeDao().getFeesByStudent(instId, swf.student.id).collect { feeList ->
                                        val feeIds = feeList.map { it.id }.toSet()
                                        db.paymentDao().getRecentPayments(instId).collect { all ->
                                            historyPayments = all.filter { p -> p.feeId in feeIds }
                                            if (!done) { done = true; return@collect }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    // ── Payment History Dialog ────────────────────────────
    if (showPaymentHistoryFor != null) {
        val studentName = studentsWF.find { it.student.id == showPaymentHistoryFor }?.student?.fullName ?: "Student"
        AlertDialog(
            onDismissRequest = { showPaymentHistoryFor = null },
            containerColor = CardBg,
            title = { Text("Payment History — $studentName", color = TextWhite, fontSize = 16.sp) },
            text = {
                if (historyPayments.isEmpty()) {
                    Text("No payments recorded.", color = TextMuted)
                } else {
                    Column {
                        historyPayments.take(10).forEach { p ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("BDT ${"%.0f".format(p.amount)}", color = WAGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(p.paymentMethod.uppercase(), color = TextMuted, fontSize = 11.sp)
                                }
                                Text(
                                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(p.paymentDateMs)),
                                    color = TextMuted, fontSize = 11.sp
                                )
                            }
                            HorizontalDivider(color = BorderSub)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPaymentHistoryFor = null }) { Text("Close", color = Cyan) } }
        )
    }

    // ── Single student channel choice dialog ─────────────
    if (sendMessageTarget != null) {
        val target = sendMessageTarget!!
        AlertDialog(
            onDismissRequest = { sendMessageTarget = null },
            containerColor = CardBg,
            title = { Text("Send Due Message", color = TextWhite, fontSize = 16.sp) },
            text = {
                Column {
                    val months = target.monthsDue(monthlyFee)
                    val monthLabel = if (months > 1) "$months months" else (target.fee?.feePeriod ?: "this month")
                    Text(
                        "${target.student.fullName} — $monthLabel due: BDT ${target.dueAmount.toLong()}",
                        color = TextMuted, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    channelCard(
                        label = "WhatsApp",
                        subtitle = "Open WhatsApp chat",
                        icon = Icons.Filled.Message,
                        color = WAGreen,
                        onClick = {
                            sendMessageTarget = null
                            paymentVM.markSending(target.student.id)
                            openWhatsApp(target)
                            paymentVM.markSent(target.student.id)
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    channelCard(
                        label = "SMS",
                        subtitle = "Send via text message",
                        icon = Icons.Filled.Sms,
                        color = ElectricBlue,
                        onClick = {
                            sendMessageTarget = null
                            paymentVM.markSending(target.student.id)
                            openSMS(target)
                            paymentVM.markSent(target.student.id)
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sendMessageTarget = null }) { Text("Cancel", color = TextMuted) } }
        )
    }

    // ── Send-all channel choice dialog ────────────────────
    if (sendAllDueChoice) {
        val dueStudents = studentsWF.filter { it.dueAmount > 0 }
        AlertDialog(
            onDismissRequest = { sendAllDueChoice = false },
            containerColor = CardBg,
            title = { Text("Send All Due Messages", color = TextWhite, fontSize = 16.sp) },
            text = {
                Column {
                    Text("Send due reminder to ${dueStudents.size} students:", color = TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    channelCard(
                        label = "WhatsApp (all)",
                        subtitle = "Open WhatsApp with summary",
                        icon = Icons.Filled.Message, color = WAGreen,
                        onClick = { sendAllDueChoice = false; sendAllDue("whatsapp") }
                    )
                    Spacer(Modifier.height(8.dp))
                    channelCard(
                        label = "SMS (all)",
                        subtitle = "Send via text message",
                        icon = Icons.Filled.Sms, color = ElectricBlue,
                        onClick = { sendAllDueChoice = false; sendAllDue("sms") }
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sendAllDueChoice = false }) { Text("Cancel", color = TextMuted) } }
        )
    }
}

// ── Reusable components ─────────────────────────────────────────

@Composable
private fun RowScope.outlinenedButton(
    text: String,
    icon: @Composable () -> Unit,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).height(44.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) {
        icon()
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun outlineFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
    focusedBorderColor = ElectricBlue, unfocusedBorderColor = BorderSub,
    focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
    cursorColor = Cyan
)

@Composable
private fun channelCard(label: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                        color: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BatchStudentCard(
    studentWF: BatchStudentWithFee,
    monthlyFee: Double,
    isDueMsgSent: Boolean,
    isSendingDueMsg: Boolean,
    onSendDueMessage: () -> Unit,
    onViewHistory: () -> Unit
) {
    val fee = studentWF.fee
    val statusColor = when {
        studentWF.feeStatus == "paid" -> WAGreen
        studentWF.dueAmount > 0 && studentWF.paidAmount > 0 -> AccentAmber
        studentWF.dueAmount > 0 -> AccentRed
        else -> TextMuted
    }
    val statusLabel = when {
        studentWF.feeStatus == "paid" -> "PAID"
        studentWF.dueAmount > 0 && studentWF.paidAmount > 0 -> "PARTIAL"
        studentWF.dueAmount > 0 -> "DUE"
        else -> "NO FEE"
    }

    Card(
        modifier = Modifier.fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(12.dp), spotColor = statusColor.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(studentWF.student.fullName, color = TextWhite, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(studentWF.student.studentCode, color = TextMuted, fontSize = 11.sp)
                }
                // Status badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Fee info row
            if (fee != null) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fee.feePeriod, color = TextMuted, fontSize = 12.sp)
                    Text(
                        "Paid BDT ${"%.0f".format(studentWF.paidAmount)} of BDT ${"%.0f".format(studentWF.totalAmount)}",
                        color = TextMuted, fontSize = 12.sp
                    )
                }
                // Multi-month due badge
                val monthsDue = studentWF.monthsDue(monthlyFee)
                if (monthsDue >= 2) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AccentAmber.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("$monthsDue months due", color = AccentAmber, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text("No fee created — BDT ${"%.0f".format(monthlyFee)}/mo", color = TextMuted, fontSize = 11.sp)
            }

            Spacer(Modifier.height(8.dp))

            // Action buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Send Due Message (only if due and student has fee)
                if (studentWF.dueAmount > 0) {
                    OutlinedButton(
                        onClick = onSendDueMessage,
                        enabled = !isSendingDueMsg && !isDueMsgSent,
                        modifier = Modifier.weight(1f).height(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, WAGreen.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WAGreen),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        when {
                            isSendingDueMsg -> {
                                CircularProgressIndicator(color = WAGreen, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Sending...", fontSize = 11.sp)
                            }
                            isDueMsgSent -> {
                                Icon(Icons.Filled.Check, null, tint = WAGreen, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Sent", fontSize = 11.sp)
                            }
                            else -> {
                                Icon(Icons.Filled.Message, null, tint = WAGreen, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Send Due", fontSize = 11.sp)
                            }
                        }
                    }
                }
                // View History
                OutlinedButton(
                    onClick = onViewHistory,
                    modifier = Modifier.weight(1f).height(34.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Cyan.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Filled.History, null, tint = Cyan, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("History", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun SummaryStatSmall(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

// ═══════════════════════════════════════════════════════════════
//  EnrollStudentsScreen  (unchanged)
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollStudentsScreen(db: AppDatabase, batchId: String, onBack: () -> Unit) {
    val instId = SessionManager.currentInstituteId.collectAsState().value
    var allStudents by remember { mutableStateOf<List<com.example.data.models.StudentEntity>>(emptyList()) }
    var enrolledStudents by remember { mutableStateOf<List<com.example.data.models.StudentEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(instId, batchId) {
        if (instId != null) {
            launch {
                db.studentDao().getStudentsByInstitute(instId).collect { allStudents = it }
            }
            launch {
                db.batchStudentDao().getStudentsForBatch(batchId, instId).collect { enrolledStudents = it }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enroll Students") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            val unenrolled = allStudents.filter { s -> enrolledStudents.none { it.id == s.id } }
            if (unenrolled.isEmpty()) {
                item { Text("All students are already enrolled.", modifier = Modifier.padding(16.dp)) }
            } else {
                items(unenrolled) { s ->
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(s.fullName, style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = {
                                if (instId != null) {
                                    scope.launch {
                                        db.batchStudentDao().enrollStudent(
                                            com.example.data.models.BatchStudentEntity(
                                                id = UUID.randomUUID().toString(),
                                                instituteId = instId,
                                                batchId = batchId,
                                                studentId = s.id,
                                                joinedAtMs = System.currentTimeMillis(),
                                                status = "active",
                                                leftAtMs = null
                                            )
                                        )
                                    }
                                }
                            }) { Text("Add") }
                        }
                    }
                }
            }
        }
    }
}
