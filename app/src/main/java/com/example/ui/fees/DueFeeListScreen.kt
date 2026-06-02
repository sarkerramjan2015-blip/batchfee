package com.example.ui.fees

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Whatsapp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.data.database.AppDatabase
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val CardBgAlt = Color(0xFF111827)
private val BorderSub = Color(0xFF1E293B)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val AccentAmber = Color(0xFFF59E0B)
private val AccentPink = Color(0xFFEF4444)
private val AccentRed = Color(0xFFEF4444)
private val AccentOrange = Color(0xFFF59E0B)
private val WAGreen = Color(0xFF25D366)

private data class DueStudentGroup(
    val studentId: String,
    val studentName: String,
    val studentPhone: String?,
    val studentStatus: String,
    val items: List<DueFeeDetail>
) {
    val totalDue: Double = items.sumOf { it.dueAmount }
}

private data class BatchDueStat(val batchName: String, val amount: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueFeeListScreen(db: AppDatabase, onBack: () -> Unit, onCollectPayment: (String) -> Unit) {
    val viewModel: FeeViewModel = viewModel(factory = FeeViewModelFactory(db))
    val dueDetails by viewModel.dueFeesWithDetails.collectAsState()
    val totalDue by viewModel.totalDueAmount.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var searchVisible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showFilter by remember { mutableStateOf(false) }
    var showChart by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedBatch by remember { mutableStateOf("All Batches") }
    var sortBy by remember { mutableStateOf("Name") }
    var statusFilter by remember { mutableStateOf("Active") }

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
                item.batchName.contains(query, ignoreCase = true) ||
                item.feePeriod.contains(query, ignoreCase = true)
            batchMatch && statusMatch && searchMatch
        }
        val grouped = filtered.groupBy { it.studentId }.map { (_, list) ->
            val first = list.first()
            DueStudentGroup(first.studentId, first.studentName, first.studentPhone, first.studentStatus, list)
        }
        when (sortBy) {
            "Amount" -> grouped.sortedByDescending { it.totalDue }
            "Date" -> grouped.sortedBy { group -> group.items.minOf { it.dueDateMs } }
            else -> grouped.sortedBy { it.studentName.lowercase() }
        }
    }
    val visibleTotalDue = filteredDetails.sumOf { it.totalDue }
    val visibleCount = filteredDetails.size
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
                    putExtra(Intent.EXTRA_TEXT, body)
                },
                title
            )
        )
    }

    fun openWhatsApp(body: String) {
        val encoded = URLEncoder.encode(body, "UTF-8")
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$encoded")))
    }

    fun openSms(body: String) {
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply { putExtra("sms_body", body) })
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
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = TextWhite)
                    }
                    IconButton(onClick = { showFilter = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filter", tint = TextWhite)
                    }
                    IconButton(onClick = { showChart = !showChart }) {
                        Icon(if (showChart) Icons.Filled.TableChart else Icons.Filled.TrendingUp, contentDescription = "Chart", tint = TextWhite)
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
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (searchVisible) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Search due fees...", color = TextMuted) },
                        colors = dueTextFieldColors(),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }

            item {
                DueFeeSummaryCard(totalDue = visibleTotalDue, count = visibleCount)
            }

            if (showChart) {
                item {
                    DueFeeChartCard(stats = batchStats, totalDue = visibleTotalDue)
                }
            } else if (filteredDetails.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("No due fees found.", color = TextMuted, fontSize = 14.sp)
                    }
                }
            } else {
                items(filteredDetails, key = { it.studentId }) { group ->
                    DueStudentCard(group = group, onClick = { group.items.firstOrNull()?.let { onCollectPayment(it.feeId) } })
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
                scope.launch { snackbarHostState.showSnackbar("In-app reminder will be connected next.") }
            },
            onExport = {
                showMenu = false
                shareText("Due Fee Records", reportText)
            }
        )
    }
}

@Composable
private fun DueFeeSummaryCard(totalDue: Double, count: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Viewing Summary for", color = TextMuted, fontSize = 16.sp)
                Text(SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date()), color = AccentAmber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(formatAmount(totalDue), color = AccentPink, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("($count)", color = TextMuted, fontSize = 16.sp)
        }
    }
}

@Composable
private fun DueStudentCard(group: DueStudentGroup, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(group.studentName, color = TextWhite, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    if (group.items.size == 1) {
                        val item = group.items.first()
                        Text("${item.batchName.ifBlank { "No Batch" }} • ${item.feePeriod}", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
                    } else {
                        group.items.take(3).forEach { item ->
                            DueFeeMiniItem(item)
                            Spacer(Modifier.height(7.dp))
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(formatAmount(group.totalDue), color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DueFeeMiniItem(item: DueFeeDetail) {
    Row(
        modifier = Modifier
            .width(250.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CardBgAlt)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.batchName.ifBlank { "No Batch" }, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Batch Fee •", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(item.feePeriod, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(formatAmount(item.dueAmount), color = TextWhite, fontSize = 16.sp)
    }
}

@Composable
private fun DueFeeChartCard(stats: List<BatchDueStat>, totalDue: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            DueDonutChart(stats = stats, totalDue = totalDue)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Due Fees", color = TextWhite, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("৳", color = TextWhite, fontSize = 16.sp, modifier = Modifier.width(90.dp))
                Text("%", color = TextWhite, fontSize = 16.sp, modifier = Modifier.width(56.dp))
            }
            HorizontalDivider(color = BorderSub, modifier = Modifier.padding(vertical = 10.dp))
            stats.forEachIndexed { index, stat ->
                val color = chartColors[index % chartColors.size]
                val percent = if (totalDue <= 0.0) 0.0 else stat.amount / totalDue * 100.0
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(8.dp))
                    Text(stat.batchName, color = TextWhite, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(formatAmount(stat.amount), color = TextWhite, fontSize = 16.sp, modifier = Modifier.width(90.dp))
                    Text("${"%.2f".format(percent)} %", color = TextWhite, fontSize = 16.sp, modifier = Modifier.width(70.dp))
                }
            }
        }
    }
}

@Composable
private fun DueDonutChart(stats: List<BatchDueStat>, totalDue: Double) {
    Box(modifier = Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(210.dp)) {
            val strokeWidth = 74f
            if (stats.isEmpty() || totalDue <= 0.0) {
                drawArc(Color(0xFF3A414C), 0f, 360f, false, style = Stroke(strokeWidth, cap = StrokeCap.Butt))
            } else {
                var start = -90f
                stats.forEachIndexed { index, stat ->
                    val sweep = (stat.amount / totalDue * 360.0).toFloat()
                    drawArc(chartColors[index % chartColors.size], start, sweep, false, style = Stroke(strokeWidth, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
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
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg)
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Due Fees Filter", color = AccentAmber, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = AccentRed, modifier = Modifier.size(30.dp))
                    }
                }
                Column(Modifier.padding(horizontal = 18.dp)) {
                    Text("Select Batch", color = TextWhite, fontSize = 15.sp)
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(62.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(CardBg)
                                .clickable { dropdownOpen = true }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Groups, contentDescription = null, tint = TextMuted, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(selectedBatch, color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("▾", color = TextWhite, fontSize = 18.sp)
                        }
                        DropdownMenu(expanded = dropdownOpen, onDismissRequest = { dropdownOpen = false }) {
                            batchOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        onBatchChange(option)
                                        dropdownOpen = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    FilterChipRow("Sort by", listOf("Name", "Amount", "Date"), sortBy, onSortChange)
                    Spacer(Modifier.height(18.dp))
                    FilterChipRow("Status", listOf("Any", "Active", "Close"), statusFilter, onStatusChange)
                }
                Spacer(Modifier.height(24.dp))
                Box(Modifier.fillMaxWidth().background(CardBgAlt).padding(18.dp)) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color(0xFF231B02))
                    ) {
                        Text("Apply Filter", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipRow(label: String, options: List<String>, selected: String, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { option ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected == option) AccentAmber.copy(alpha = 0.16f) else CardBgAlt)
                        .clickable { onSelected(option) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(option, color = if (selected == option) AccentAmber else TextWhite, fontSize = 17.sp)
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
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, BorderSub)
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Due Fee Menu", color = AccentAmber, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = AccentRed, modifier = Modifier.size(30.dp))
                    }
                }
                HorizontalDivider(color = BorderSub)
                MenuRow(Icons.Filled.Sms, "SMS", "You can send due fee SMS to student", onSms)
                MenuRow(Icons.Filled.Whatsapp, "WhatsApp", "You can send due fee message to student", onWhatsApp)
                MenuRow(Icons.Filled.Notifications, "In-App Reminder", "Send push notification to all students with dues", onReminder)
                MenuRow(Icons.Filled.Download, "Export", "You can download due fee record here", onExport, showDivider = false)
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, showDivider: Boolean = true) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextMuted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    focusedBorderColor = AccentAmber,
    unfocusedBorderColor = BorderSub,
    focusedContainerColor = CardBg,
    unfocusedContainerColor = CardBg,
    cursorColor = AccentAmber
)

private val chartColors = listOf(AccentRed, AccentAmber, Color(0xFF3B82F6), Color(0xFF14B8A6), Color(0xFF6366F1))

private fun String.isClosedStatus(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "close" || normalized == "closed" || normalized == "inactive"
}

private fun formatAmount(amount: Double): String =
    NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 0
    }.format(amount)

private fun buildDueFeeExportText(groups: List<DueStudentGroup>, totalDue: Double): String =
    buildString {
        appendLine("Due Fee Records")
        appendLine("Date: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())}")
        appendLine("Total Due: ${formatAmount(totalDue)}")
        appendLine("Students: ${groups.size}")
        appendLine()
        groups.forEach { group ->
            appendLine("${group.studentName} - ${formatAmount(group.totalDue)}")
            group.items.forEach { item ->
                appendLine("  ${item.batchName.ifBlank { "No Batch" }} • ${item.feePeriod}: ${formatAmount(item.dueAmount)}")
            }
        }
    }
