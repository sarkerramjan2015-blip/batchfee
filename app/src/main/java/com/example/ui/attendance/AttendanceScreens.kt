package com.batchfee.edu.ui.attendance

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.domain.SessionManager
import java.text.SimpleDateFormat
import java.util.*

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val CardBgAlt = Color(0xFF111827)
private val BorderSub = Color(0xFF1E293B)
private val Cyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val AccentGreen = Color(0xFF22C55E)
private val AccentRed = Color(0xFFEF4444)
private val AccentAmber = Color(0xFFF59E0B)
private val AccentGray = Color(0xFF64748B)
private val WAGreen = Color(0xFF25D366)
private val AccentSky = Color(0xFF38BDF8)
private val AccentViolet = Color(0xFF6366F1)

@Composable
private fun sectionLabel(text: String) {
    Text(text, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
}

@Composable
private fun gradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier.height(48.dp).clip(RoundedCornerShape(14.dp))
            .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(0.35f))
            .let { if (enabled) it.background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))) else it.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(14.dp)) },
        contentAlignment = Alignment.Center
    ) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxSize(), enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = if (enabled) Color.White else TextMuted, disabledContentColor = TextMuted)
        ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
    }
}

// ═══════════════════════════════════════════════════════════════
//  BatchSelectScreen
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceBatchSelectScreen(db: AppDatabase, onBack: () -> Unit, onSelectBatch: (String) -> Unit) {
    val viewModel: AttendanceViewModel = viewModel(factory = AttendanceViewModelFactory(db))
    val batches by viewModel.batches.collectAsState()
    val summaries by viewModel.dailyBatchSummaries.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadDailySummaries() }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Take Attendance", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        if (batches.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No batches available.", color = TextMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(batches, key = { it.id }) { batch ->
                    val summary = summaries.find { it.batchId == batch.id }
                    val pct = summary?.presentPct ?: 0f
                    val absPct = summary?.absentPct ?: 0f
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(0.2f)),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, BorderSub),
                        onClick = { onSelectBatch(batch.id) }
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(44.dp).clip(CircleShape)
                                    .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))),
                                    contentAlignment = Alignment.Center
                                ) { Text(batch.name.take(1).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(batch.name, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val total = summary?.totalStudents ?: 0
                                    val marked = summary?.markedCount ?: 0
                                    Text("$marked/$total marked today", color = TextMuted, fontSize = 12.sp)
                                }
                                Icon(Icons.Filled.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(20.dp))
                            }
                            if (summary != null) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = AccentGreen, trackColor = CardBgAlt
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    miniChip("Present", "${"%.0f".format(pct)}%", AccentGreen)
                                    miniChip("Absent", "${"%.0f".format(absPct)}%", AccentRed)
                                    miniChip("Leave", "${"%.0f".format(summary.leavePct)}%", AccentSky)
                                    miniChip("Holiday", "${"%.0f".format(summary.holidayPct)}%", AccentGray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun miniChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 9.sp)
    }
}

// ═══════════════════════════════════════════════════════════════
//  DateStrip
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateStrip(
    selectedDateMs: Long,
    onDateSelected: (Long) -> Unit,
    isDateToday: (Long) -> Boolean
) {
    val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
    val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
    val monthYearFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val cal = Calendar.getInstance()

    val todayMs = startOfDay(System.currentTimeMillis())
    val dates = remember(selectedDateMs) {
        val c = Calendar.getInstance().apply { timeInMillis = selectedDateMs }
        val list = mutableListOf<Long>()
        for (i in -4..4) {
            val clone = c.clone() as Calendar
            clone.add(Calendar.DAY_OF_YEAR, i)
            list.add(startOfDay(clone.timeInMillis))
        }
        list
    }

    var showDatePicker by remember { mutableStateOf(false) }

    Column {
        // Month/Year label
        Text(
            monthYearFormat.format(Date(selectedDateMs)),
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Left arrow
            IconButton(
                onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = selectedDateMs }
                    c.add(Calendar.DAY_OF_YEAR, -1)
                    onDateSelected(c.timeInMillis)
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Filled.ChevronLeft, null, tint = TextMuted, modifier = Modifier.size(18.dp))
            }

            // Date chips
            dates.forEach { dateMs ->
                val isToday = isDateToday(dateMs)
                val isSelected = dateMs == selectedDateMs
                val dayLabel = if (isToday) "Today" else dayFormat.format(Date(dateMs))
                val dateLabel = dateFormat.format(Date(dateMs))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (isSelected && isToday) Modifier.background(
                                Brush.horizontalGradient(listOf(Cyan, ElectricBlue)),
                                RoundedCornerShape(10.dp)
                            ) else if (isSelected) Modifier.background(
                                Cyan.copy(alpha = 0.25f), RoundedCornerShape(10.dp)
                            ) else Modifier.background(CardBg, RoundedCornerShape(10.dp))
                        )
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = when {
                                isSelected -> Cyan.copy(alpha = 0.7f)
                                isToday -> Cyan.copy(alpha = 0.3f)
                                else -> BorderSub
                            },
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onDateSelected(dateMs) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            dayLabel,
                            color = if (isSelected) TextWhite else TextMuted,
                            fontSize = 10.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            dateLabel,
                            color = if (isSelected) TextWhite.copy(alpha = 0.85f) else TextMuted.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            // Right arrow
            IconButton(
                onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = selectedDateMs }
                    c.add(Calendar.DAY_OF_YEAR, 1)
                    onDateSelected(c.timeInMillis)
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Filled.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(18.dp))
            }

            // Calendar picker icon
            IconButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Filled.CalendarToday, null, tint = Cyan, modifier = Modifier.size(18.dp))
            }
        }
    }

    // DatePickerDialog
    if (showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = selectedDateMs)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { onDateSelected(it) }
                    showDatePicker = false
                }) { Text("OK", color = Cyan) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel", color = TextMuted) }
            },
            colors = DatePickerDefaults.colors(containerColor = CardBg)
        ) {
            DatePicker(state = dpState)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  TakeAttendanceScreen
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakeAttendanceScreen(db: AppDatabase, batchId: String, onBack: () -> Unit) {
    val viewModel: AttendanceViewModel = viewModel(factory = AttendanceViewModelFactory(db))
    val students by viewModel.students.collectAsState()
    val records by viewModel.attendanceRecords.collectAsState()
    val batch by viewModel.currentBatch.collectAsState()
    val absentMsgMap by viewModel.absentMessageMap.collectAsState()
    val sendingIds by viewModel.sendingMessageIds.collectAsState()
    val staffLabel by viewModel.staffName.collectAsState()
    val selectedDateMs by viewModel.selectedDateMs.collectAsState()
    val context = LocalContext.current

    val dateLabel = remember(selectedDateMs) {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(selectedDateMs))
    }

    LaunchedEffect(batchId, selectedDateMs) {
        viewModel.loadBatchStudentsAndAttendance(batchId, selectedDateMs)
    }

    // Dialog states
    var showChannelDialog by remember { mutableStateOf(false) }
    var dialogStudentId by remember { mutableStateOf<String?>(null) }
    var showAllChannelDialog by remember { mutableStateOf(false) }

    val studentsWithStatus = remember(students, records) {
        students.map { s ->
            val rec = records[s.id]
            s to (rec?.status ?: "not_marked")
        }
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("${batch?.name ?: ""}", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            // Date strip with navigation
            DateStrip(
                selectedDateMs = selectedDateMs,
                onDateSelected = { viewModel.selectDate(it) },
                isDateToday = { viewModel.isToday(it) }
            )
            Spacer(Modifier.height(14.dp))

            // Summary bar
            val pCount = studentsWithStatus.count { it.second == "present" }
            val aCount = studentsWithStatus.count { it.second == "absent" }
            val lCount = studentsWithStatus.count { it.second == "leave" }
            val hCount = studentsWithStatus.count { it.second == "holiday" }
            val totalStudents = students.size

            Card(
                Modifier.fillMaxWidth().shadow(3.dp, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(Modifier.padding(12.dp)) {
                    LinearProgressIndicator(
                        progress = { if (totalStudents > 0) (pCount + aCount + lCount + hCount).toFloat() / totalStudents else 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = AccentGreen, trackColor = CardBgAlt
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        miniChip("P", "$pCount", AccentGreen)
                        miniChip("A", "$aCount", AccentRed)
                        miniChip("L", "$lCount", AccentSky)
                        miniChip("H", "$hCount", AccentGray)
                        miniChip("Total", "$totalStudents", TextWhite)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // Bulk action row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                gradientButton("Mark All Present", { viewModel.markAll(batchId, selectedDateMs, "present") }, Modifier.weight(1f).height(42.dp))
                OutlinedButton(
                    onClick = { showAllChannelDialog = true },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, WAGreen.copy(0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WAGreen),
                    enabled = aCount > 0
                ) {
                    Icon(Icons.Filled.Message, null, tint = WAGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Msg All", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))

            // Student list
            studentsWithStatus.forEach { (student, status) ->
                val hasMessage = absentMsgMap[student.id] == true
                val isSending = student.id in sendingIds
                StudentAttendanceCard(
                    studentName = student.fullName,
                    studentCode = student.studentCode,
                    status = status,
                    hasMessage = hasMessage,
                    isSending = isSending,
                    onMark = { st -> viewModel.markAttendance(student.id, batchId, selectedDateMs, st) },
                    onUndo = { viewModel.undoAttendance(student.id, selectedDateMs, batchId) },
                    onSendMessage = {
                        dialogStudentId = student.id
                        showChannelDialog = true
                    }
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    // Channel choice dialog for single student
    if (showChannelDialog && dialogStudentId != null) {
        val sid = dialogStudentId!!
        val student = students.find { it.id == sid }
        val bname = batch?.name ?: ""
        AlertDialog(
            onDismissRequest = { showChannelDialog = false },
            containerColor = CardBg,
            title = { Text("Send Absent Message", color = TextWhite, fontSize = 16.sp) },
            text = {
                Column {
                    Text("${student?.fullName ?: "Student"} — absent $dateLabel", color = TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    channelCard("WhatsApp", Icons.Filled.Message, WAGreen, {
                        showChannelDialog = false
                        viewModel.sendAbsentMessage(context, sid, batchId, selectedDateMs, "whatsapp",
                            student?.fullName ?: "", bname, student?.phone, staffLabel, {}, {})
                    })
                    Spacer(Modifier.height(8.dp))
                    channelCard("SMS", Icons.Filled.Sms, ElectricBlue, {
                        showChannelDialog = false
                        viewModel.sendAbsentMessage(context, sid, batchId, selectedDateMs, "sms",
                            student?.fullName ?: "", bname, student?.phone, staffLabel, {}, {})
                    })
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showChannelDialog = false }) { Text("Cancel", color = TextMuted) } }
        )
    }

    // Channel choice for Send All
    if (showAllChannelDialog) {
        AlertDialog(
            onDismissRequest = { showAllChannelDialog = false },
            containerColor = CardBg,
            title = { Text("Send All Absent Messages", color = TextWhite, fontSize = 16.sp) },
            text = {
                Column {
                    val count = studentsWithStatus.count { it.second == "absent" }
                    Text("Send messages to $count students:", color = TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    channelCard("WhatsApp (all)", Icons.Filled.Message, WAGreen, {
                        showAllChannelDialog = false
                        viewModel.sendAllAbsentMessages(context, batchId, selectedDateMs, "whatsapp") {}
                    })
                    Spacer(Modifier.height(8.dp))
                    channelCard("SMS (all)", Icons.Filled.Sms, ElectricBlue, {
                        showAllChannelDialog = false
                        viewModel.sendAllAbsentMessages(context, batchId, selectedDateMs, "sms") {}
                    })
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAllChannelDialog = false }) { Text("Cancel", color = TextMuted) } }
        )
    }
}

@Composable
private fun channelCard(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StudentAttendanceCard(
    studentName: String, studentCode: String, status: String,
    hasMessage: Boolean, isSending: Boolean,
    onMark: (String) -> Unit, onUndo: () -> Unit,
    onSendMessage: () -> Unit
) {
    val chipData = listOf(
        "present" to Triple("P", AccentGreen, "Present"),
        "absent" to Triple("A", AccentRed, "Absent"),
        "leave" to Triple("L", AccentSky, "Leave"),
        "holiday" to Triple("H", AccentGray, "Holiday")
    )
    val activeColor = when (status) {
        "present" -> AccentGreen; "absent" -> AccentRed; "leave" -> AccentSky; "holiday" -> AccentGray; else -> TextMuted
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            .shadow(2.dp, RoundedCornerShape(10.dp), spotColor = activeColor.copy(0.15f)),
        shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(studentName, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(studentCode, color = TextMuted, fontSize = 10.sp)
            }

            // Status chips
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                chipData.forEach { (key, triple) ->
                    FilterChip(
                        selected = status == key,
                        onClick = { onMark(key) },
                        label = { Text(triple.first, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.height(30.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = triple.second.copy(alpha = 0.25f),
                            selectedLabelColor = triple.second,
                            containerColor = CardBgAlt,
                            labelColor = TextMuted
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = BorderSub, selectedBorderColor = triple.second,
                            enabled = true, selected = status == key
                        )
                    )
                }
            }
        }
        // Bottom action row (undo + send msg)
        if (status != "not_marked") {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onUndo,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) { Icon(Icons.Filled.Undo, null, tint = TextMuted, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(2.dp)); Text("Undo", color = TextMuted, fontSize = 11.sp) }

                if (status == "absent") {
                    OutlinedButton(
                        onClick = onSendMessage,
                        enabled = !hasMessage && !isSending,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, WAGreen.copy(0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WAGreen)
                    ) {
                        when {
                            isSending -> CircularProgressIndicator(color = WAGreen, strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
                            hasMessage -> { Icon(Icons.Filled.Check, null, tint = WAGreen, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(2.dp)); Text("Sent", fontSize = 10.sp) }
                            else -> { Icon(Icons.Filled.Message, null, tint = WAGreen, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(2.dp)); Text("Msg", fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  AttendanceReportScreen
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceReportScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: AttendanceViewModel = viewModel(factory = AttendanceViewModelFactory(db))
    val batches by viewModel.batches.collectAsState()
    val summaries by viewModel.batchSummaries.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadMonthlySummaries() }

    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    // Compute overall from all batch summaries
    val overall = remember(summaries) {
        if (summaries.isEmpty()) null
        else BatchAttendanceSummary(
            batchName = "All Batches",
            totalStudents = summaries.sumOf { it.totalStudents },
            presentCount = summaries.sumOf { it.presentCount },
            absentCount = summaries.sumOf { it.absentCount },
            leaveCount = summaries.sumOf { it.leaveCount },
            holidayCount = summaries.sumOf { it.holidayCount },
            expectedStudentDays = summaries.sumOf { it.expectedStudentDays },
            attendanceDays = summaries.maxOfOrNull { it.attendanceDays } ?: 0
        )
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Attendance Report", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            overall?.let { sum ->
                Text("Overall Summary (This Month)", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                summaryCard(sum)
                Spacer(Modifier.height(16.dp))
            }

            if (summaries.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("No attendance recorded yet.", color = TextMuted, fontSize = 14.sp)
                }
            } else {
                Text("Per Batch", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                summaries.forEach { sum ->
                    summaryCard(sum, Modifier.padding(bottom = 10.dp).clickable { selectedBatchId = sum.batchId })
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    // Batch detail dialog
    if (selectedBatchId != null) {
        val bid = selectedBatchId!!
        val batchSum = summaries.find { it.batchId == bid }
        AlertDialog(
            onDismissRequest = { selectedBatchId = null },
            containerColor = CardBg,
            title = { Text(batchSum?.batchName ?: "Batch", color = TextWhite, fontSize = 16.sp) },
            text = {
                batchSum?.let { summaryCard(it, includeProgress = true) }
            },
            confirmButton = { TextButton(onClick = { selectedBatchId = null }) { Text("Close", color = Cyan) } }
        )
    }
}

@Composable
private fun summaryCard(sum: BatchAttendanceSummary, modifier: Modifier = Modifier, includeProgress: Boolean = true) {
    Card(
        modifier = modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(0.15f)),
        shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${sum.markedCount}/${sum.expectedStudentDays} student-days covered", color = TextMuted, fontSize = 12.sp)
                Text("${sum.attendanceDays} active days", color = TextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            if (includeProgress) {
                LinearProgressIndicator(
                    progress = { (sum.coveragePct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                    color = AccentGreen,
                    trackColor = CardBgAlt
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                statChip("Coverage", "${"%.0f".format(sum.coveragePct)}%", sum.markedCount, AccentViolet)
                statChip("Present", "${"%.0f".format(sum.presentPerformancePct)}%", sum.presentCount, AccentGreen)
                statChip("Absent", "${"%.0f".format(sum.absentPerformancePct)}%", sum.absentCount, AccentRed)
                statChip("Leave", sum.leaveCount.toString(), sum.leaveCount, AccentSky)
                statChip("Holiday", sum.holidayCount.toString(), sum.holidayCount, AccentGray)
            }
        }
    }
}

@Composable
private fun statChip(label: String, pct: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(pct, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        val caption = if (label == "Coverage") "$count marked" else "$count $label"
        Text(caption, color = TextMuted, fontSize = 10.sp)
    }
}

