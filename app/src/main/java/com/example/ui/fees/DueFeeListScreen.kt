package com.batchfee.edu.ui.fees

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Whatsapp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.appendInstituteSignature
import com.batchfee.edu.domain.loadInstituteSignature
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val CardBgAlt = Color(0xFF111827)
private val CardHi = Color(0xFF132033)
private val BorderSub = Color(0xFF1E293B)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val AccentCyan = Color(0xFF22D3EE)
private val SkyBlue = Color(0xFF38BDF8)
private val ElectricBlue = Color(0xFF3B82F6)
private val AccentRed = Color(0xFFEF4444)
private val WAGreen = Color(0xFF25D366)
private val SoftLine = Color(0x5522D3EE)
private val SoftCyan = Color(0x1A22D3EE)

private data class DueStudentGroup(
    val studentId: String,
    val studentName: String,
    val studentPhone: String?,
    val studentStatus: String,
    val items: List<DueFeeDetail>
) {
    val totalDue: Double = items.sumOf { it.dueAmount }
    val monthCount: Int = items.map { it.feePeriod }.distinct().size
}

private data class BatchDueStat(val batchName: String, val amount: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueFeeListScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: FeeViewModel = viewModel(factory = FeeViewModelFactory(db))
    val dueDetails by viewModel.dueFeesWithDetails.collectAsState()
    val context = LocalContext.current
    val instId = SessionManager.currentInstituteId.value
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var instituteSignature by remember { mutableStateOf("") }

    LaunchedEffect(instId) {
        instituteSignature = loadInstituteSignature(db, instId)
    }

    var searchVisible by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var showFilter by remember { mutableStateOf(false) }
    var showChart by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedBatch by remember { mutableStateOf("All Batches") }
    var sortBy by remember { mutableStateOf("Name") }
    var statusFilter by remember { mutableStateOf("Any") }

    val batchOptions = remember(dueDetails) {
        listOf("All Batches") + dueDetails.map { it.batchName.ifBlank { "No Batch" } }.distinct().sorted()
    }
    val filteredDetails = remember(dueDetails, query, selectedBatch, sortBy, statusFilter) {
        val filtered = dueDetails.filter { item ->
            val batchMatch = selectedBatch == "All Batches" || item.batchName.ifBlank { "No Batch" } == selectedBatch
            val statusMatch = when (statusFilter) {
                "Active" -> !item.studentStatus.isClosedStatus()
                "Close" -> item.studentStatus.isClosedStatus()
                else -> true
            }
            val searchMatch = query.isBlank() ||
                item.studentName.contains(query, ignoreCase = true) ||
                item.studentPhone.orEmpty().contains(query, ignoreCase = true) ||
                item.batchName.contains(query, ignoreCase = true) ||
                item.feePeriod.contains(query, ignoreCase = true)
            batchMatch && statusMatch && searchMatch
        }
        val grouped = filtered.groupBy { it.studentId }.map { (_, list) ->
            val first = list.first()
            DueStudentGroup(
                studentId = first.studentId,
                studentName = first.studentName,
                studentPhone = first.studentPhone,
                studentStatus = first.studentStatus,
                items = list.sortedBy { it.dueDateMs }
            )
        }
        when (sortBy) {
            "Amount" -> grouped.sortedByDescending { it.totalDue }
            "Date" -> grouped.sortedBy { group -> group.items.minOf { it.dueDateMs } }
            else -> grouped.sortedBy { it.studentName.lowercase() }
        }
    }

    val visibleTotalDue = filteredDetails.sumOf { it.totalDue }
    val visibleStudentCount = filteredDetails.size
    val visibleFeeCount = filteredDetails.sumOf { it.items.size }
    val visiblePeriodCount = filteredDetails.flatMap { group -> group.items.map { it.feePeriod } }.distinct().size
    val batchStats = remember(filteredDetails) {
        filteredDetails.flatMap { it.items }
            .groupBy { it.batchName.ifBlank { "No Batch" } }
            .map { (batch, list) -> BatchDueStat(batch, list.sumOf { it.dueAmount }) }
            .sortedByDescending { it.amount }
    }
    val reportText = buildDueFeeExportText(filteredDetails, visibleTotalDue)

    fun shareText(title: String, body: String) {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, appendInstituteSignature(body, instituteSignature))
                },
                title
            )
        )
    }

    fun openWhatsApp(body: String) {
        val encoded = URLEncoder.encode(appendInstituteSignature(body, instituteSignature), "UTF-8")
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$encoded")))
    }

    fun openSms(body: String) {
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply {
            putExtra("sms_body", appendInstituteSignature(body, instituteSignature))
        })
    }

    fun sendReminder(group: DueStudentGroup, channel: String) {
        val periods = group.items.joinToString(", ") { it.feePeriod }.ifBlank { "fee period" }
        viewModel.sendDueNotification(
            context = context,
            studentName = group.studentName,
            phone = group.studentPhone,
            dueAmount = group.totalDue,
            feePeriod = periods,
            channel = channel
        )
    }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Due Fees", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                actions = {
                    IconButton(onClick = { searchVisible = !searchVisible }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = if (searchVisible) AccentCyan else TextWhite)
                    }
                    IconButton(onClick = { showFilter = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filter", tint = TextWhite)
                    }
                    IconButton(onClick = { showChart = !showChart }) {
                        Icon(
                            if (showChart) Icons.Filled.TableChart else Icons.Filled.TrendingUp,
                            contentDescription = "Report",
                            tint = if (showChart) AccentCyan else TextWhite
                        )
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (searchVisible) {
                item {
                    DueSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onClear = { query = "" }
                    )
                }
            }

            item {
                DueFeeSummaryCard(
                    totalDue = visibleTotalDue,
                    studentCount = visibleStudentCount,
                    feeCount = visibleFeeCount,
                    periodCount = visiblePeriodCount,
                    selectedBatch = selectedBatch
                )
            }

            if (showChart) {
                item {
                    DueFeeChartCard(stats = batchStats, totalDue = visibleTotalDue)
                }
            }

            item {
                DueListHeader(
                    count = visibleStudentCount,
                    sortBy = sortBy,
                    statusFilter = statusFilter
                )
            }

            if (filteredDetails.isEmpty()) {
                item {
                    DueEmptyState()
                }
            } else {
                items(filteredDetails, key = { it.studentId }) { group ->
                    DueStudentCard(
                        group = group,
                        onSms = { sendReminder(group, "sms") },
                        onWhatsApp = { sendReminder(group, "whatsapp") }
                    )
                }
            }
        }
    }

    if (showFilter) {
        DueFeesFilterDialog(
            batchOptions = batchOptions,
            selectedBatch = selectedBatch,
            sortBy = sortBy,
            statusFilter = statusFilter,
            onBatchChange = { selectedBatch = it },
            onSortChange = { sortBy = it },
            onStatusChange = { statusFilter = it },
            onDismiss = { showFilter = false }
        )
    }

    if (showMenu) {
        DueFeeMenuDialog(
            onDismiss = { showMenu = false },
            onSms = {
                showMenu = false
                openSms(reportText)
            },
            onWhatsApp = {
                showMenu = false
                openWhatsApp(reportText)
            },
            onReminder = {
                showMenu = false
                scope.launch { snackbarHostState.showSnackbar("Use SMS or WhatsApp to send due reminders now.") }
            },
            onExport = {
                showMenu = false
                shareText("Due Fee Report", reportText)
            }
        )
    }
}

@Composable
private fun DueSearchField(query: String, onQueryChange: (String) -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(22.dp))
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear", tint = TextMuted)
                }
            }
        },
        placeholder = { Text("Search name, phone, batch, month", color = TextMuted) },
        colors = dueTextFieldColors(),
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun DueFeeSummaryCard(
    totalDue: Double,
    studentCount: Int,
    feeCount: Int,
    periodCount: Int,
    selectedBatch: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF101B2F), CardBg)))
            .border(1.dp, SoftLine, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(SoftCyan),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.ReceiptLong, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Due Report", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())} / $selectedBatch",
                        color = TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Total Due", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(formatCurrency(totalDue), color = AccentRed, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DueSummaryPill("Students", studentCount.toString(), Modifier.weight(1f))
                DueSummaryPill("Fee Items", feeCount.toString(), Modifier.weight(1f))
                DueSummaryPill("Months", periodCount.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DueSummaryPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardBgAlt)
            .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(value, color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun DueListHeader(count: Int, sortBy: String, statusFilter: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Students With Due", color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("$count students / Sort: $sortBy / Status: $statusFilter", color = TextMuted, fontSize = 12.sp)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(SoftCyan)
                .border(1.dp, SoftLine, RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text("Report only", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DueEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .border(1.dp, BorderSub, RoundedCornerShape(18.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(SoftCyan),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.ReceiptLong, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("No due fees found", color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Students with pending fees will appear here.", color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DueStudentCard(group: DueStudentGroup, onSms: () -> Unit, onWhatsApp: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, SoftLine)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(ElectricBlue, AccentCyan))),
                    contentAlignment = Alignment.Center
                ) {
                    val initial = group.studentName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                    Text(initial, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.studentName, color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(group.studentPhone ?: "No phone", color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TinyStatusChip(if (group.studentStatus.isClosedStatus()) "Close" else "Active", if (group.studentStatus.isClosedStatus()) AccentRed else WAGreen)
                        TinyStatusChip("${group.monthCount} month${if (group.monthCount == 1) "" else "s"}", AccentCyan)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatCurrency(group.totalDue), color = AccentRed, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("${group.items.size} due item${if (group.items.size == 1) "" else "s"}", color = TextMuted, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = BorderSub)
            Spacer(Modifier.height(10.dp))

            group.items.forEach { item ->
                DueFeeLine(item)
                Spacer(Modifier.height(8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DueReminderButton(Icons.Filled.Sms, "SMS", ElectricBlue, Modifier.weight(1f), onSms)
                DueReminderButton(Icons.Filled.Whatsapp, "WhatsApp", WAGreen, Modifier.weight(1f), onWhatsApp)
            }
        }
    }
}

@Composable
private fun TinyStatusChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DueFeeLine(item: DueFeeDetail) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBgAlt)
            .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.feePeriod, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(item.batchName.ifBlank { "No Batch" }, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatCurrency(item.dueAmount), color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Due", color = AccentRed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DueReminderButton(
    icon: ImageVector,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DueFeeChartCard(stats: List<BatchDueStat>, totalDue: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, SoftLine)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Batch Wise Due Report", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Distribution by batch", color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            DueDonutChart(stats = stats, totalDue = totalDue)
            Spacer(Modifier.height(14.dp))
            if (stats.isEmpty()) {
                Text("No due records to chart.", color = TextMuted, fontSize = 13.sp)
            } else {
                stats.forEachIndexed { index, stat ->
                    DueDistributionRow(stat = stat, totalDue = totalDue, color = chartColors[index % chartColors.size])
                    if (index != stats.lastIndex) Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun DueDonutChart(stats: List<BatchDueStat>, totalDue: Double) {
    Box(modifier = Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(166.dp)) {
            val strokeWidth = 32f
            if (stats.isEmpty() || totalDue <= 0.0) {
                drawArc(Color(0xFF334155), 0f, 360f, false, style = Stroke(strokeWidth, cap = StrokeCap.Round))
            } else {
                var start = -90f
                stats.forEachIndexed { index, stat ->
                    val sweep = (stat.amount / totalDue * 360.0).toFloat().coerceAtLeast(1f)
                    drawArc(chartColors[index % chartColors.size], start, sweep, false, style = Stroke(strokeWidth, cap = StrokeCap.Round))
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatCurrency(totalDue), color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Total due", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DueDistributionRow(stat: BatchDueStat, totalDue: Double, color: Color) {
    val percent = if (totalDue <= 0.0) 0f else (stat.amount / totalDue).toFloat().coerceIn(0f, 1f)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(stat.batchName, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(formatCurrency(stat.amount), color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(BorderSub)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent.coerceAtLeast(0.04f))
                    .height(7.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun DueFeesFilterDialog(
    batchOptions: List<String>,
    selectedBatch: String,
    sortBy: String,
    statusFilter: String,
    onBatchChange: (String) -> Unit,
    onSortChange: (String) -> Unit,
    onStatusChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var dropdownOpen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(0.96f),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, SoftLine)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Due Fees Filter", color = AccentCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = AccentRed, modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Select Batch", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(CardBgAlt)
                            .border(1.dp, BorderSub, RoundedCornerShape(14.dp))
                            .clickable { dropdownOpen = true }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Groups, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(selectedBatch, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("v", color = TextMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(
                        expanded = dropdownOpen,
                        onDismissRequest = { dropdownOpen = false },
                        modifier = Modifier
                            .width(280.dp)
                            .heightIn(max = 320.dp)
                            .background(CardBgAlt),
                        containerColor = CardBgAlt
                    ) {
                        batchOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option, color = TextWhite, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    onBatchChange(option)
                                    dropdownOpen = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                FilterChipRow("Sort by", listOf("Name", "Amount", "Date"), sortBy, onSortChange)
                Spacer(Modifier.height(16.dp))
                FilterChipRow("Status", listOf("Any", "Active", "Close"), statusFilter, onStatusChange)
                Spacer(Modifier.height(22.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(ElectricBlue, AccentCyan)))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Apply Filter", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FilterChipRow(label: String, options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Column {
        Text(label, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { option ->
                val isSelected = selected == option
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) SoftCyan else CardBgAlt)
                        .border(1.dp, if (isSelected) SoftLine else BorderSub, RoundedCornerShape(12.dp))
                        .clickable { onSelected(option) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(option, color = if (isSelected) AccentCyan else TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DueFeeMenuDialog(
    onDismiss: () -> Unit,
    onSms: () -> Unit,
    onWhatsApp: () -> Unit,
    onReminder: () -> Unit,
    onExport: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(0.96f),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, SoftLine)
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Due Fee Menu", color = AccentCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = AccentRed, modifier = Modifier.size(28.dp))
                    }
                }
                HorizontalDivider(color = BorderSub)
                MenuRow(Icons.Filled.Sms, "SMS All", "Share this due report by SMS", ElectricBlue, onSms)
                MenuRow(Icons.Filled.Whatsapp, "WhatsApp All", "Share this due report by WhatsApp", WAGreen, onWhatsApp)
                MenuRow(Icons.Filled.Notifications, "Reminder Note", "Use student cards for direct reminders", AccentCyan, onReminder)
                MenuRow(Icons.Filled.Download, "Export Report", "Download or share due fee records", AccentCyan, onExport, showDivider = false)
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit,
    showDivider: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showDivider) HorizontalDivider(color = BorderSub)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun dueTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedBorderColor = AccentCyan,
    unfocusedBorderColor = SoftLine,
    focusedContainerColor = CardBg,
    unfocusedContainerColor = CardBg,
    cursorColor = AccentCyan
)

private val chartColors = listOf(AccentRed, AccentCyan, ElectricBlue, WAGreen, SkyBlue, Color(0xFF8B5CF6))

private fun String.isClosedStatus(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "close" || normalized == "closed" || normalized == "inactive"
}

private fun formatAmount(amount: Double): String =
    NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 0
    }.format(amount)

private fun formatCurrency(amount: Double): String = "BDT ${formatAmount(amount)}"

private fun buildDueFeeExportText(groups: List<DueStudentGroup>, totalDue: Double): String =
    buildString {
        appendLine("Due Fee Report")
        appendLine("Date: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())}")
        appendLine("Total Due: ${formatCurrency(totalDue)}")
        appendLine("Students With Due: ${groups.size}")
        appendLine()
        groups.forEachIndexed { index, group ->
            appendLine("${index + 1}. ${group.studentName} - ${formatCurrency(group.totalDue)}")
            appendLine("Phone: ${group.studentPhone ?: "N/A"}")
            appendLine("Status: ${if (group.studentStatus.isClosedStatus()) "Close" else "Active"}")
            group.items.forEach { item ->
                appendLine("  - ${item.feePeriod} | ${item.batchName.ifBlank { "No Batch" }} | Due: ${formatCurrency(item.dueAmount)}")
            }
            appendLine()
        }
    }

