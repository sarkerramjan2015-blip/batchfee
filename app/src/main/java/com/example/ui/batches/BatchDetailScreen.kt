package com.example.ui.batches

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.firestore.BatchStudentSyncHelper
import com.example.data.firestore.InstituteCacheRefreshManager
import com.example.data.models.AttendanceEntity
import com.example.data.models.PaymentEntity
import com.example.data.models.StaffEntity
import com.example.domain.appendInstituteSignature
import com.example.domain.loadInstituteSignature
import com.example.domain.SessionManager
import com.example.ui.components.buildWhatsAppUrl
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
    onEdit: () -> Unit,
    onEnroll: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val instId = SessionManager.currentInstituteId.collectAsState().value
    val paymentVM: BatchPaymentViewModel = viewModel(factory = BatchPaymentViewModelFactory(db))
    val batch by paymentVM.batch.collectAsState()
    val studentsWF by paymentVM.studentsWithFee.collectAsState()
    val totalEnrolled by paymentVM.totalEnrolled.collectAsState()
    val paidCount by paymentVM.paidCount.collectAsState()
    val dueCount by paymentVM.dueCount.collectAsState()
    val totalExpected by paymentVM.totalExpected.collectAsState()
    val totalCollected by paymentVM.totalCollected.collectAsState()
    val isLoading by paymentVM.isLoading.collectAsState()
    var instituteSignature by remember { mutableStateOf("") }

    LaunchedEffect(instId) {
        instituteSignature = loadInstituteSignature(db, instId)
    }

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
    var recentPayments by remember { mutableStateOf<List<PaymentEntity>>(emptyList()) }
    var monthOffset by remember { mutableStateOf(0) }
    var monthAttendance by remember { mutableStateOf<List<AttendanceEntity>>(emptyList()) }
    var allowedStaff by remember { mutableStateOf<List<StaffEntity>>(emptyList()) }
    var showBatchMenu by remember { mutableStateOf(false) }

    // Dialogs
    var sendMessageTarget by remember { mutableStateOf<BatchStudentWithFee?>(null) }
    var sendAllDueChoice by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Load data on first composition
    LaunchedEffect(batchId) { paymentVM.loadBatchDetail(batchId) }
    LaunchedEffect(instId, batchId, monthOffset) {
        val instituteId = instId ?: return@LaunchedEffect
        InstituteCacheRefreshManager.refreshIfStale(db, instituteId)
        val range = monthRangeForOffset(monthOffset)
        launch {
            db.attendanceDao().getAttendanceForBatchByDateRange(instituteId, batchId, range.first, range.second)
                .collect { monthAttendance = it }
        }
        launch {
            db.staffDao().getActiveStaff(instituteId).collect { staff ->
                allowedStaff = staff.filter { it.isAssignedToBatch(batchId) }
            }
        }
        launch {
            db.paymentDao().getRecentPayments(instituteId).collect { recentPayments = it }
        }
    }

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
        val msg = appendInstituteSignature(buildDueMessage(target), instituteSignature)
        val url = buildWhatsAppUrl(target.student.phone, msg)
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=${
            java.net.URLEncoder.encode(msg, "UTF-8") }"))) }
    }

    fun openSMS(target: BatchStudentWithFee) {
        val msg = appendInstituteSignature(buildDueMessage(target), instituteSignature)
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
                val msg = appendInstituteSignature("$batchName\nDue Fees:\n$lines", instituteSignature)
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

    fun shareBatchReport(title: String, text: String) {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, appendInstituteSignature(text, instituteSignature))
                },
                title
            )
        )
    }

    // ── Scaffold ─────────────────────────────────────────
    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BatchAvatar(Modifier.size(48.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                batch?.name ?: "Batch Details",
                                color = TextWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                batch?.status?.replaceFirstChar { it.uppercase() } ?: "",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite)
                    }
                },
                actions = {
                    IconButton(onClick = { showBatchMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Batch menu", tint = TextWhite)
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
            val batchFeeIds = studentsWF.mapNotNull { it.fee?.id }.toSet()
            val batchPayments = recentPayments.filter { it.feeId in batchFeeIds }
            BatchDashboardSummary(
                batchName = batch?.name ?: "Batch",
                studentCount = totalEnrolled,
                paidCount = paidCount,
                dueCount = dueCount,
                totalDue = (totalExpected - totalCollected).coerceAtLeast(0.0),
                paidThisMonth = paidThisMonth,
                dueThisMonth = dueThisMonth,
                todayCollected = batchPayments.sumByDateRange(dayRange(System.currentTimeMillis())),
                monthCollected = batchPayments.sumByDateRange(monthRangeForOffset(monthOffset)),
                totalCollected = totalCollected,
                monthLabel = monthLabelForOffset(monthOffset),
                attendance = monthAttendance,
                allowedStaff = allowedStaff,
                onPreviousMonth = { monthOffset -= 1 },
                onNextMonth = { monthOffset += 1 },
                onReport = {
                    shareBatchReport(
                        "Batch Attendance Report",
                        buildBatchAttendanceReport(batch?.name ?: "Batch", monthLabelForOffset(monthOffset), monthAttendance)
                    )
                }
            )

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
    if (showBatchMenu) {
        BatchMenuDialog(
            onDismiss = { showBatchMenu = false },
            onEdit = {
                showBatchMenu = false
                onEdit()
            },
            onClose = {
                showBatchMenu = false
                scope.launch { snackbarHostState.showSnackbar("Close batch will be added with confirmation next.") }
            },
            onShift = {
                showBatchMenu = false
                scope.launch { snackbarHostState.showSnackbar("Shift single students from the Student Profile screen.") }
            },
            onDelete = {
                showBatchMenu = false
                scope.launch { snackbarHostState.showSnackbar("Delete batch needs strong confirmation; not enabled yet.") }
            },
            onAssignedStudents = {
                showBatchMenu = false
                onEnroll()
            }
        )
    }

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

    if (showDeleteDialog && batch != null) {
        var isDeleting by remember { mutableStateOf(false) }
        val batchEntity = batch!!
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            containerColor = CardBg,
            icon = { Icon(Icons.Filled.Warning, null, tint = AccentRed, modifier = Modifier.size(40.dp)) },
            title = { Text("Delete Batch Permanently", color = AccentRed, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Are you sure you want to delete this batch?",
                        color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                    Text(
                        "\"${batchEntity.name.take(30)}\"",
                        color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "All batch information will be permanently removed. No student records, fee records, or attendance data related to this batch will remain accessible.",
                        color = TextMuted, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Delete, null, tint = AccentRed.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("This action cannot be undone.", color = AccentRed.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeleting = true
                        (paymentVM as BatchViewModel).deleteBatch(
                            batch = batchEntity,
                            onError = { msg ->
                                isDeleting = false
                                showDeleteDialog = false
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            },
                            onSuccess = {
                                isDeleting = false
                                showDeleteDialog = false
                                scope.launch { snackbarHostState.showSnackbar("Batch deleted successfully.") }
                                onBack()
                            }
                        )
                    },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isDeleting) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Text("Yes, Delete Permanently", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }, enabled = !isDeleting) {
                    Text("Cancel", color = TextMuted)
                }
            }
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
private fun BatchMenuDialog(
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onClose: () -> Unit,
    onShift: () -> Unit,
    onDelete: () -> Unit,
    onAssignedStudents: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, BorderSub)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Student Batch Menu",
                        color = AccentAmber,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFFFFB4B4), modifier = Modifier.size(30.dp))
                    }
                }
                HorizontalDivider(color = BorderSub)
                BatchMenuItem(Icons.Filled.Edit, "Edit batch", "You can edit batch here", onEdit)
                BatchMenuItem(Icons.Filled.Close, "Close Batch", "You can mark batch status as you want Active or Close", onClose)
                BatchMenuItem(Icons.Filled.Groups, "Shift Batch", "You can shift batch's students to another batch.", onShift)
                BatchMenuItem(Icons.Filled.Delete, "Delete Batch", "You can delete batch forever.", onDelete)
                BatchMenuItem(Icons.Filled.Groups, "Batch Assigned Students", "You can assign this batch to students.", onAssignedStudents, showDivider = false)
            }
        }
    }
}

@Composable
private fun BatchMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 22.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = TextMuted,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (showDivider) HorizontalDivider(color = BorderSub)
    }
}

@Composable
private fun BatchDashboardSummary(
    batchName: String,
    studentCount: Int,
    paidCount: Int,
    dueCount: Int,
    totalDue: Double,
    paidThisMonth: Int,
    dueThisMonth: Int,
    todayCollected: Double,
    monthCollected: Double,
    totalCollected: Double,
    monthLabel: String,
    attendance: List<AttendanceEntity>,
    allowedStaff: List<StaffEntity>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onReport: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        // ── Batch header card ────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.20f)),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, BorderSub)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(ElectricBlue, Cyan))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        batchName.take(1).uppercase(),
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        batchName,
                        color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("$studentCount students", color = TextMuted, fontSize = 13.sp)
                }
            }
        }

        // ── Key metrics row ──────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard("Students", "$studentCount", "Enrolled", SkyBlue, Modifier.weight(1f))
            MetricCard("Paid", "$paidCount", "This month", WAGreen, Modifier.weight(1f))
            MetricCard("Due", "$dueCount", "Total due", if (dueCount > 0) AccentRed else TextMuted, Modifier.weight(1f))
        }

        // ── Collections card ──────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, BorderSub)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Collections", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onPreviousMonth, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.KeyboardArrowLeft, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                        Text(monthLabel, color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = onNextMonth, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.KeyboardArrowRight, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem("Today", money(todayCollected), Cyan)
                    StatItem(monthLabel, money(monthCollected), WAGreen)
                    StatItem("All Time", money(totalCollected), SkyBlue)
                }
            }
        }

        // ── Attendance summary ────────────────────────
        if (attendance.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Attendance", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(monthLabel, color = Cyan, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    AttendanceMiniChart(attendance)
                    Spacer(Modifier.height(10.dp))
                    AttendanceLegend()
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onReport,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Cyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                    ) {
                        Icon(Icons.Filled.IosShare, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share Report", fontSize = 13.sp)
                    }
                }
            }
        }

        // ── Allowed staff ─────────────────────────────
        if (allowedStaff.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Assigned Staff", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("${allowedStaff.size}", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    allowedStaff.take(3).forEach { member ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(ElectricBlue.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    member.fullName.take(1).uppercase(),
                                    color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(member.fullName, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(member.roleTitle, color = TextMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(label, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun BatchAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF7C3AED), SkyBlue))),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.AccountBalance, contentDescription = null, tint = TextWhite, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun FeeStateBlock(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MonthSwitcher(label: String, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous month", tint = AccentAmber)
        }
        Text(label, color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next month", tint = AccentAmber)
        }
    }
}

@Composable
private fun CollectedMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = WAGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = TextMuted, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun AttendanceMiniChart(attendance: List<AttendanceEntity>) {
    val dayRecords = (1..10).map { day ->
        attendance.filter {
            Calendar.getInstance().apply { timeInMillis = it.attendanceDateMs }.get(Calendar.DAY_OF_MONTH) == day
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(150.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        dayRecords.forEachIndexed { index, records ->
            val present = records.count { it.status.equals("present", ignoreCase = true) }
            val absent = records.count { it.status.equals("absent", ignoreCase = true) }
            val leave = records.count { it.status.equals("leave", ignoreCase = true) }
            val holiday = records.count { it.status.equals("holiday", ignoreCase = true) }
            val total = records.size.coerceAtLeast(1)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(modifier = Modifier.fillMaxWidth().height(118.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(BorderSub))
                    Column(modifier = Modifier.width(10.dp).height(100.dp), verticalArrangement = Arrangement.Bottom) {
                        AttendanceSegment(holiday, total, SkyBlue)
                        AttendanceSegment(leave, total, AccentAmber)
                        AttendanceSegment(absent, total, AccentRed)
                        AttendanceSegment(present, total, WAGreen)
                    }
                }
                Text((index + 1).toString(), color = TextWhite, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ColumnScope.AttendanceSegment(count: Int, total: Int, color: Color) {
    if (count <= 0) return
    Box(modifier = Modifier.fillMaxWidth().weight(count.toFloat() / total.toFloat()).background(color))
}

@Composable
private fun AttendanceLegend() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        LegendItem("Present", WAGreen)
        LegendItem("Absent", AccentRed)
        LegendItem("Leave", AccentAmber)
        LegendItem("Holiday", SkyBlue)
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextWhite, fontSize = 14.sp)
        Box(Modifier.width(54.dp).height(5.dp).clip(RoundedCornerShape(8.dp)).background(color))
    }
}

@Composable
private fun BatchSmallTile(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(66.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, BorderSub, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.TableChart, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AllowedStaffCard(staff: List<StaffEntity>) {
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Groups, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(10.dp))
            Text("Allowed Staffs", color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF8A6500)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(staff.size.toString(), color = AccentAmber, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(18.dp))
        if (staff.isEmpty()) {
            Text("No staff assigned yet.", color = TextMuted, fontSize = 14.sp)
        } else {
            staff.take(3).forEach { member ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(AccentRed.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = AccentRed, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(member.fullName, color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(member.roleTitle, color = TextMuted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun StaffEntity.isAssignedToBatch(batchId: String): Boolean =
    assignedBatchIds?.split(",")?.map { it.trim() }?.any { it == batchId } ?: false

private fun List<PaymentEntity>.sumByDateRange(range: Pair<Long, Long>): Double =
    filter { it.paymentDateMs in range.first..range.second }.sumOf { it.amount }

private fun dayRange(timeMs: Long): Pair<Long, Long> {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val start = calendar.timeInMillis
    calendar.add(Calendar.DAY_OF_MONTH, 1)
    calendar.add(Calendar.MILLISECOND, -1)
    return start to calendar.timeInMillis
}

private fun monthRangeForOffset(offset: Int): Pair<Long, Long> {
    val calendar = Calendar.getInstance().apply {
        add(Calendar.MONTH, offset)
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val start = calendar.timeInMillis
    calendar.add(Calendar.MONTH, 1)
    calendar.add(Calendar.MILLISECOND, -1)
    return start to calendar.timeInMillis
}

private fun monthLabelForOffset(offset: Int): String {
    val calendar = Calendar.getInstance().apply { add(Calendar.MONTH, offset) }
    return SimpleDateFormat("MMM-yyyy", Locale.getDefault()).format(calendar.time)
}

private fun money(amount: Double, decimals: Boolean = false): String {
    val value = if (decimals) amount else amount.toLong().toDouble()
    val formatter = java.text.NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = if (decimals) 1 else 0
        maximumFractionDigits = if (decimals) 1 else 0
    }
    return formatter.format(value)
}

private fun buildBatchAttendanceReport(batchName: String, monthLabel: String, attendance: List<AttendanceEntity>): String {
    val present = attendance.count { it.status.equals("present", ignoreCase = true) }
    val absent = attendance.count { it.status.equals("absent", ignoreCase = true) }
    val leave = attendance.count { it.status.equals("leave", ignoreCase = true) }
    val holiday = attendance.count { it.status.equals("holiday", ignoreCase = true) }
    return buildString {
        appendLine("Batch Attendance Report")
        appendLine("Batch: $batchName")
        appendLine("Month: $monthLabel")
        appendLine("Present: $present")
        appendLine("Absent: $absent")
        appendLine("Leave: $leave")
        appendLine("Holiday: $holiday")
        appendLine("Total marked records: ${attendance.size}")
    }
}

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
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
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
                                        val enrollment = com.example.data.models.BatchStudentEntity(
                                            id = UUID.randomUUID().toString(),
                                            instituteId = instId,
                                            batchId = batchId,
                                            studentId = s.id,
                                            joinedAtMs = System.currentTimeMillis(),
                                            status = "active",
                                            leftAtMs = null
                                        )
                                        BatchStudentSyncHelper.upsertEnrollment(enrollment)
                                        db.batchStudentDao().enrollStudent(enrollment)
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
