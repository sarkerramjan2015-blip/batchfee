package com.batchfee.edu.ui.automation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.repository.DueAutomationPolicy
import com.batchfee.edu.data.repository.DueAutomationPreview
import com.batchfee.edu.data.repository.DueAutomationRepository
import com.batchfee.edu.data.repository.DueAutomationState
import com.batchfee.edu.data.repository.DueReminderRecord
import com.batchfee.edu.data.repository.DueRunSummary
import com.example.domain.MessageTemplateStore
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val BorderSub = Color(0xFF1E293B)
private val Cyan = Color(0xFF22D3EE)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val AccentRed = Color(0xFFEF4444)
private val AccentGreen = Color(0xFF22C55E)
private val AccentAmber = Color(0xFFF59E0B)

private val BEFORE_DAY_OPTIONS = listOf(1L, 2L, 3L, 5L, 7L, 10L, 14L, 15L, 21L, 30L)
private val AFTER_DAY_OPTIONS = listOf(1L, 2L, 3L, 5L, 7L, 10L, 15L, 21L, 30L)
private val HOUR_OPTIONS = (0L..23L).toList()

private val FEE_TYPE_OPTIONS = listOf(
    "monthly_fee" to "Monthly Fee",
    "admission_fee" to "Admission Fee",
    "course_fee" to "Course Fee",
    "exam_fee" to "Exam Fee",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartDueAutomationScreen(db: AppDatabase, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val instituteId by SessionManager.currentInstituteId.collectAsState()

    var state by remember { mutableStateOf<DueAutomationState?>(null) }
    var draft by remember { mutableStateOf<DueAutomationPolicy?>(null) }
    var preview by remember { mutableStateOf<DueAutomationPreview?>(null) }
    var historyReminders by remember { mutableStateOf<List<DueReminderRecord>>(emptyList()) }
    var historyRuns by remember { mutableStateOf<List<DueRunSummary>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }

    var showBatchPicker by remember { mutableStateOf(false) }
    var showStudentPicker by remember { mutableStateOf(false) }
    var showTemplatePicker by remember { mutableStateOf(false) }

    val batchOptions by db.batchDao().getBatchesByInstitute(instituteId.orEmpty())
        .collectAsState(initial = emptyList())
    val studentOptions by db.studentDao().getStudentsByInstitute(instituteId.orEmpty())
        .collectAsState(initial = emptyList())
    val templateOptions by db.reminderTemplateDao().getTemplatesForInstitute(instituteId.orEmpty())
        .collectAsState(initial = emptyList())

    suspend fun reloadState() {
        val loaded = runCatching { DueAutomationRepository.getState() }.getOrNull()
        if (loaded == null) { loadFailed = true; return }
        loadFailed = false
        state = loaded
        draft = loaded.policy
        historyReminders = loaded.recentReminders
        historyRuns = loaded.recentRuns
    }

    suspend fun reloadPreview() {
        previewing = true
        preview = runCatching { DueAutomationRepository.preview() }.getOrNull()
        previewing = false
    }

    LaunchedEffect(instituteId) {
        if (instituteId.isNullOrBlank()) return@LaunchedEffect
        reloadState()
        reloadPreview()
    }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Smart Due Automation", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Automatic due reminders · SMS credit controlled", color = TextMuted, fontSize = 11.sp)
                    }
                },
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (loadFailed) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, BorderSub)
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.CloudOff, null, tint = AccentRed, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Could not load automation settings.", color = TextWhite, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            scope.launch {
                                reloadState()
                                reloadPreview()
                            }
                        }) { Text("Retry") }
                    }
                }
                return@Column
            }
            val current = draft ?: DueAutomationPolicy()
            val currentState = state

            // ── Status + master switch ────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(2.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Autorenew, null, tint = Cyan, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Automatic reminders", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (current.enabled) "Runs every hour inside the send window" else "Paused — no automated messages will go out",
                                color = if (current.enabled) AccentGreen else TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        Switch(checked = current.enabled, onCheckedChange = { draft = current.copy(enabled = it) })
                    }
                    Spacer(Modifier.height(10.dp))
                    val today = currentState
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0B1B2E))
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(Modifier.fillMaxWidth()) {
                            StatCell("SMS balance", (currentState?.smsBalance ?: 0).toString(), Modifier.weight(1f))
                            StatCell(
                                "Est. days remaining",
                                (currentState?.estimatedDaysRemaining ?: 0).toString(),
                                Modifier.weight(1f)
                            )
                        }
                        HorizontalDivider(color = BorderSub.copy(alpha = 0.75f))
                        Row(Modifier.fillMaxWidth()) {
                            StatCell("Sent today", (currentState?.sentToday ?: 0).toString(), Modifier.weight(1f))
                            StatCell(
                                "Daily limit left",
                                currentState?.remainingTodayLimit?.toString() ?: "—",
                                Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // ── Trigger schedule ──────────────────────────────────────────
            SectionCard("Reminder schedule", Icons.Filled.EventRepeat) {
                Text("Send this many days BEFORE the due date", color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                DayChipRow(
                    options = BEFORE_DAY_OPTIONS,
                    selected = current.beforeDueDays,
                    onToggle = { day ->
                        val next = current.beforeDueDays.toMutableList().apply {
                            if (contains(day)) remove(day) else if (size < 5) add(day)
                        }
                        draft = current.copy(beforeDueDays = next.sortedDescending())
                    }
                )
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    title = "Send on the due date itself",
                    checked = current.sendOnDueDay,
                    onCheckedChange = { draft = current.copy(sendOnDueDay = it) }
                )
                Spacer(Modifier.height(6.dp))
                Text("Send this many days AFTER the due date", color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                DayChipRow(
                    options = AFTER_DAY_OPTIONS,
                    selected = current.afterDueDays,
                    onToggle = { day ->
                        val next = current.afterDueDays.toMutableList().apply {
                            if (contains(day)) remove(day) else if (size < 5) add(day)
                        }
                        draft = current.copy(afterDueDays = next.sorted())
                    }
                )
            }

            // ── Channels ──────────────────────────────────────────────────
            SectionCard("Reminder channels", Icons.Filled.MarkUnreadChatAlt) {
                val hasSms = "sms" in current.channels
                val hasWhatsapp = "whatsapp" in current.channels
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChannelChip(
                        label = "SMS",
                        selected = hasSms,
                        onClick = {
                            draft = current.copy(
                                channels = (if (hasSms) current.channels - "sms" else current.channels + "sms").distinct()
                            )
                        }
                    )
                    ChannelChip(
                        label = "WhatsApp",
                        selected = hasWhatsapp,
                        onClick = {
                            draft = current.copy(
                                channels = (if (hasWhatsapp) current.channels - "whatsapp" else current.channels + "whatsapp").distinct()
                            )
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "SMS is fully automatic via the BatchFee server. WhatsApp automation is recorded as " +
                        "\"skipped\" until a WhatsApp Business API provider is connected — manual WhatsApp " +
                        "reminders still work from the Due Fees screen.",
                    color = TextMuted,
                    fontSize = 10.sp
                )
                if (current.channels.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Select at least one channel.", color = AccentAmber, fontSize = 11.sp)
                }
            }

            // ── Fee types ─────────────────────────────────────────────────
            SectionCard("Fee types to include", Icons.Filled.ReceiptLong) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FEE_TYPE_OPTIONS.chunked(2).forEach { pair ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { (value, label) ->
                                ChannelChip(
                                    label = label,
                                    selected = value in current.feeTypes,
                                    onClick = {
                                        draft = current.copy(
                                            feeTypes = (if (value in current.feeTypes)
                                                current.feeTypes - value else current.feeTypes + value).distinct()
                                        )
                                    },
                                    compact = true
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (current.feeTypes.isEmpty()) "All fee types are included." else "Only the selected fee types are included.",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }

            // ── Window + daily cap ────────────────────────────────────────
            SectionCard("Send window & daily cap", Icons.Filled.Schedule) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HourSelector(
                        label = "From",
                        hour = current.sendWindowStartHour,
                        onPick = { draft = current.copy(sendWindowStartHour = it) }
                    )
                    Text(" to ", color = TextMuted, fontSize = 12.sp)
                    HourSelector(
                        label = "Until",
                        hour = current.sendWindowEndHour,
                        onPick = { draft = current.copy(sendWindowEndHour = it) }
                    )
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily SMS limit", color = TextWhite, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = current.dailySmsLimit.toString(),
                        onValueChange = { raw ->
                            val value = raw.filter(Char::isDigit).take(5).toLongOrNull() ?: 0L
                            draft = current.copy(dailySmsLimit = value)
                        },
                        modifier = Modifier.width(110.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedBorderColor = Cyan,
                            unfocusedBorderColor = BorderSub
                        )
                    )
                }
                Text(
                    "0 = no daily cap. The sweep never exceeds this many sends in one Dhaka day.",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }

            // ── Exclusions ────────────────────────────────────────────────
            SectionCard("Exclusions", Icons.Filled.Block) {
                PickerRow(
                    title = "Excluded batches",
                    count = current.excludedBatchIds.size,
                    onClick = { showBatchPicker = true }
                )
                HorizontalDivider(color = BorderSub)
                PickerRow(
                    title = "Excluded students",
                    count = current.excludedStudentIds.size,
                    onClick = { showStudentPicker = true }
                )
            }

            // ── Template ──────────────────────────────────────────────────
            SectionCard("Message template", Icons.Filled.Description) {
                val dueFeeTemplates = templateOptions.filter { it.type == MessageTemplateStore.TYPE_DUE_FEE }
                val selectedTemplate = dueFeeTemplates.firstOrNull { it.id == current.templateId }
                PickerRow(
                    title = selectedTemplate?.title ?: "Default due-fee template",
                    count = 0,
                    onClick = { showTemplatePicker = true },
                    trailing = Icons.Filled.ArrowDropDown
                )
            }

            // ── Estimate preview ──────────────────────────────────────────
            SectionCard("Estimated credits for the next run", Icons.Filled.Calculate) {
                val estimate = preview
                if (estimate == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Cyan,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (previewing) "Estimating..." else "Tap refresh to estimate", color = TextMuted, fontSize = 12.sp)
                    }
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        StatCell("Matched reminders", estimate.matchedCount.toString(), Modifier.weight(1f))
                        StatCell("Estimated SMS credits", estimate.estimatedCredits.toString(), Modifier.weight(1f))
                    }
                    HorizontalDivider(color = BorderSub.copy(alpha = 0.75f), modifier = Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        StatCell("Available credits", estimate.availableCredits.toString(), Modifier.weight(1f))
                        StatCell(
                            "Today's capacity left",
                            estimate.remainingTodayCapacity?.toString() ?: "Unlimited",
                            Modifier.weight(1f)
                        )
                    }
                    if (estimate.whatsappCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${estimate.whatsappCount} WhatsApp reminder(s) will be recorded as skipped (no provider).",
                            color = AccentAmber,
                            fontSize = 10.sp
                        )
                    }
                    if (estimate.estimatedCredits > estimate.availableCredits) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Insufficient credits — the next run will record skipped reminders. Top up the SMS wallet.",
                            color = AccentRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { scope.launch { reloadPreview() } },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Cyan.copy(alpha = 0.5f))
                ) {
                    Text("Refresh estimate", color = Cyan, fontSize = 12.sp)
                }
            }

            // ── History ───────────────────────────────────────────────────
            SectionCard("Recent activity", Icons.Filled.History) {
                if (historyReminders.isEmpty() && historyRuns.isEmpty()) {
                    Text("No automated reminders yet.", color = TextMuted, fontSize = 12.sp)
                } else {
                    historyRuns.take(3).forEach { run ->
                        RunSummaryRow(run)
                        HorizontalDivider(color = BorderSub.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 6.dp))
                    }
                    historyReminders.take(10).forEach { reminder ->
                        ReminderRow(reminder)
                        if (reminder != historyReminders.take(10).lastOrNull()) {
                            HorizontalDivider(color = BorderSub.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }

            // ── Save ──────────────────────────────────────────────────────
            Button(
                onClick = {
                    if (current.channels.isEmpty()) {
                        scope.launch { snackbarHostState.showSnackbar("Select at least one reminder channel.") }
                        return@Button
                    }
                    scope.launch {
                        saving = true
                        val saved = runCatching { DueAutomationRepository.savePolicy(current) }.isSuccess
                        saving = false
                        if (saved) {
                            reloadState()
                            reloadPreview()
                            snackbarHostState.showSnackbar("Automation settings saved.")
                        } else {
                            snackbarHostState.showSnackbar("Could not save settings. Check your connection.")
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = TextWhite, strokeWidth = 2.dp)
                } else {
                    Text("Save automation settings", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showBatchPicker) {
        MultiSelectDialog(
            title = "Excluded batches",
            items = batchOptions.map { it.id to it.name },
            selected = draft?.excludedBatchIds.orEmpty().toSet(),
            onConfirm = { selected ->
                draft = (draft ?: DueAutomationPolicy()).copy(excludedBatchIds = selected.sorted())
                showBatchPicker = false
            },
            onDismiss = { showBatchPicker = false }
        )
    }
    if (showStudentPicker) {
        MultiSelectDialog(
            title = "Excluded students",
            items = studentOptions.map { it.id to it.fullName },
            selected = draft?.excludedStudentIds.orEmpty().toSet(),
            onConfirm = { selected ->
                draft = (draft ?: DueAutomationPolicy()).copy(excludedStudentIds = selected.sorted())
                showStudentPicker = false
            },
            onDismiss = { showStudentPicker = false }
        )
    }
    if (showTemplatePicker) {
        val dueFeeTemplates = templateOptions.filter { it.type == MessageTemplateStore.TYPE_DUE_FEE }
        val options = listOf(null to "Default due-fee template") + dueFeeTemplates.map { it.id to it.title }
        val templateIds = options.mapNotNull { it.first }
        val currentIndex = draft?.templateId?.let { templateIds.indexOf(it) } ?: 0
        SingleChoiceDialog(
            title = "Message template",
            options = options.map { it.second },
            selectedIndex = if (currentIndex >= 0) currentIndex else 0,
            onConfirm = { index ->
                draft = (draft ?: DueAutomationPolicy()).copy(templateId = options.getOrNull(index)?.first ?: "")
                showTemplatePicker = false
            },
            onDismiss = { showTemplatePicker = false }
        )
    }
}

// ── Small pieces ────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Cyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 9.sp)
    }
}

@Composable
private fun DayChipRow(options: List<Long>, selected: List<Long>, onToggle: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { day ->
                    val isSelected = day in selected
                    Text(
                        "${day}d",
                        color = if (isSelected) TextWhite else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Cyan.copy(alpha = 0.18f) else Color(0xFF0B1B2E))
                            .border(
                                BorderStroke(1.dp, if (isSelected) Cyan else BorderSub),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onToggle(day) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextWhite, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChannelChip(label: String, selected: Boolean, onClick: () -> Unit, compact: Boolean = false) {
    Text(
        label,
        color = if (selected) TextWhite else TextMuted,
        fontSize = if (compact) 11.sp else 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Cyan.copy(alpha = 0.18f) else Color(0xFF0B1B2E))
            .border(BorderStroke(1.dp, if (selected) Cyan else BorderSub), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun PickerRow(title: String, count: Int, onClick: () -> Unit, trailing: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = TextWhite,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (count > 0) {
            Text("$count", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            trailing ?: Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun HourSelector(label: String, hour: Long, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, color = TextMuted, fontSize = 9.sp)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0B1B2E))
                .border(BorderStroke(1.dp, BorderSub), RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(formatHour(hour), color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Filled.ArrowDropDown, null, tint = TextMuted, modifier = Modifier.size(16.dp))
        }
    }
    if (open) {
        SingleChoiceDialog(
            title = "$label hour",
            options = HOUR_OPTIONS.map(::formatHour),
            selectedIndex = HOUR_OPTIONS.indexOf(hour).coerceAtLeast(0),
            onConfirm = { index -> HOUR_OPTIONS.getOrNull(index)?.let(onPick); open = false },
            onDismiss = { open = false }
        )
    }
}

private fun formatHour(hour: Long): String {
    val suffix = if (hour < 12) "AM" else "PM"
    val display = when (hour % 12) {
        0L -> 12
        else -> (hour % 12).toInt()
    }
    return "$display $suffix"
}

@Composable
private fun ReminderRow(reminder: DueReminderRecord) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(reminder.status, reminder.skipReason)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${reminder.studentName} · BDT ${String.format(java.util.Locale.US, "%.2f", reminder.dueAmount)} · ${reminder.feePeriods.joinToString(", ")}",
                color = TextWhite,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                reminder.trigger.ifBlank { reminder.channel },
                color = TextMuted,
                fontSize = 9.sp
            )
        }
        Text(shortStatus(reminder), color = statusColor(reminder.status, reminder.skipReason), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RunSummaryRow(run: DueRunSummary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Sweep · ${formatDate(run.runAtMs)}",
                color = TextWhite,
                fontSize = 11.sp
            )
            Text("${run.matchedCount} matched", color = TextMuted, fontSize = 9.sp)
        }
        Text("${run.sentCount} sent", color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        if (run.skippedCount > 0) {
            Text("${run.skippedCount} skipped", color = AccentAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
        }
        if (run.failedCount > 0) {
            Text("${run.failedCount} failed", color = AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusDot(status: String, skipReason: String) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(statusColor(status, skipReason))
    )
}

private fun statusColor(status: String, skipReason: String): Color = when {
    status == "sent" || status == "delivered" -> AccentGreen
    status == "failed" -> AccentRed
    status == "skipped" || status == "pending" -> if (skipReason == "insufficient_credit") AccentRed else AccentAmber
    else -> TextMuted
}

private fun shortStatus(reminder: DueReminderRecord): String = when {
    reminder.status == "skipped" -> when (reminder.skipReason) {
        "insufficient_credit" -> "No credit"
        "daily_limit_reached" -> "Daily cap"
        "no_phone" -> "No phone"
        "whatsapp_provider_unavailable" -> "No WhatsApp provider"
        else -> "Skipped"
    }
    else -> reminder.status.replaceFirstChar { it.uppercase() }
}

private fun formatDate(ms: Long): String = if (ms <= 0) "—" else
    SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(ms))

// ── Dialogs ────────────────────────────────────────────────────────────────

@Composable
private fun MultiSelectDialog(
    title: String,
    items: List<Pair<String, String>>,
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, BorderSub)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    items.forEach { (id, label) ->
                        val isSelected = id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = selected.toMutableSet().apply {
                                        if (isSelected) remove(id) else add(id)
                                    }
                                    onConfirm(next)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Cyan)
                            )
                            Text(label, color = TextWhite, fontSize = 12.sp)
                        }
                    }
                    if (items.isEmpty()) {
                        Text("Nothing to select yet.", color = TextMuted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Done", color = Cyan)
                }
            }
        }
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, BorderSub)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEachIndexed { index, label ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(index) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = index == selectedIndex,
                                onClick = { onConfirm(index) },
                                colors = RadioButtonDefaults.colors(selectedColor = Cyan)
                            )
                            Text(label, color = TextWhite, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel", color = TextMuted)
                }
            }
        }
    }
}
