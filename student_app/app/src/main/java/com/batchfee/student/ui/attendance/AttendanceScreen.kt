package com.batchfee.student.ui.attendance

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.student.data.firebase.StudentFirestoreRepository
import com.batchfee.student.data.models.Attendance
import com.batchfee.student.data.models.AttendanceSummary
import com.batchfee.student.data.models.Batch
import com.batchfee.student.demo.DemoDataProvider
import com.batchfee.student.domain.SessionManager
import com.batchfee.student.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

enum class Period(val label: String) {
    TODAY("Today"),
    WEEK("This Week"),
    MONTH("This Month"),
    LAST_MONTH("Last Month"),
    ALL("All Time")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(onBack: () -> Unit) {
    val studentId by SessionManager.currentStudentId.collectAsState()
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val repo = remember { StudentFirestoreRepository() }

    var batches by remember { mutableStateOf<List<Batch>>(emptyList()) }
    var selectedBatch by remember { mutableStateOf<Batch?>(null) }
    var allRecords by remember { mutableStateOf<List<Attendance>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedPeriod by remember { mutableStateOf(Period.MONTH) }

    // Animation
    val contentVisible = remember { mutableStateOf(false) }

    LaunchedEffect(studentId, instituteId) {
        val sid = studentId ?: return@LaunchedEffect
        val iid = instituteId ?: return@LaunchedEffect
        try {
            batches = repo.getStudentBatches(iid, sid)
            if (batches.isNotEmpty()) {
                selectedBatch = batches.first()
                allRecords = repo.getAttendance(iid, sid, batches.first().id)
            }
        } catch (_: Exception) { }
        isLoading = false
        contentVisible.value = true
    }

    LaunchedEffect(selectedBatch) {
        val batch = selectedBatch ?: return@LaunchedEffect
        val sid = studentId ?: return@LaunchedEffect
        val iid = instituteId ?: return@LaunchedEffect
        try {
            allRecords = repo.getAttendance(iid, sid, batch.id)
        } catch (_: Exception) { }
    }

    // Filter records by selected period
    val filteredRecords = remember(selectedPeriod, allRecords) {
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        val (start, end) = when (selectedPeriod) {
            Period.TODAY -> DemoDataProvider.getTodayBounds()
            Period.WEEK -> DemoDataProvider.getWeekBounds(0)
            Period.MONTH -> DemoDataProvider.getMonthBounds(0)
            Period.LAST_MONTH -> DemoDataProvider.getMonthBounds(-1)
            Period.ALL -> 0L to Long.MAX_VALUE
        }
        allRecords.filter { it.attendanceDateMs in start..end }
            .sortedByDescending { it.attendanceDateMs }
    }

    val summary = remember(filteredRecords) {
        val total = filteredRecords.size
        AttendanceSummary(
            totalClasses = total,
            present = filteredRecords.count { it.status == "present" },
            absent = filteredRecords.count { it.status == "absent" },
            late = filteredRecords.count { it.status == "late" }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Attendance", fontWeight = FontWeight.Bold) },
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
        } else if (batches.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No batches found", color = TextSecondaryLight)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                AnimatedVisibility(
                    visible = contentVisible.value,
                    enter = fadeIn(tween(500))
                ) {
                    Column {
                        // ═════════════════════════════════════
                        // BATCH SELECTOR
                        // ═════════════════════════════════════
                        if (batches.size > 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                batches.forEach { batch ->
                                    FilterChip(
                                        selected = selectedBatch?.id == batch.id,
                                        onClick = { selectedBatch = batch },
                                        label = { Text(batch.name, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = PrimaryBlue.copy(alpha = 0.15f),
                                            selectedLabelColor = PrimaryBlue
                                        )
                                    )
                                }
                            }
                        }

                        // ═════════════════════════════════════
                        // PERIOD TABS
                        // ═════════════════════════════════════
                        ScrollableTabRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            selectedTabIndex = selectedPeriod.ordinal,
                            containerColor = Color.Transparent,
                            edgePadding = 0.dp,
                            divider = {}
                        ) {
                            Period.entries.forEachIndexed { index, period ->
                                Tab(
                                    selected = selectedPeriod == period,
                                    onClick = { selectedPeriod = period },
                                    text = {
                                        Text(
                                            period.label,
                                            fontWeight = if (selectedPeriod == period) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 12.sp
                                        )
                                    },
                                    selectedContentColor = PrimaryBlue,
                                    unselectedContentColor = TextSecondaryLight
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // ═════════════════════════════════════
                        // HERO — Percentage Card
                        // ═════════════════════════════════════
                        val pct = summary.percentage
                        val passThreshold = 75f
                        val isGood = pct >= passThreshold

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
                        ) {
                            Box {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.linearGradient(
                                                colors = if (isGood)
                                                    listOf(Color(0xFF065F46), Color(0xFF059669))
                                                else
                                                    listOf(Color(0xFF92400E), Color(0xFFD97706)),
                                                start = Offset.Zero, end = Offset(600f, 200f)
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
                                    // Animated Percentage
                                    val animatedPct = remember { Animatable(0f) }
                                    LaunchedEffect(pct) {
                                        animatedPct.snapTo(0f)
                                        animatedPct.animateTo(pct, tween(1000))
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "${String.format("%.0f", animatedPct.value)}%",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 56.sp,
                                            letterSpacing = 2.sp,
                                            style = MaterialTheme.typography.displaySmall.copy(
                                                shadow = Shadow(Color.Black.copy(alpha = 0.2f), Offset(2f, 4f), 8f)
                                            )
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Icon(
                                            if (isGood) Icons.Filled.Verified else Icons.Filled.Warning,
                                            null,
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }

                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        if (isGood) "Excellent Attendance! 🎯" else "Needs Improvement ⚠️",
                                        color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp
                                    )

                                    Spacer(Modifier.height(16.dp))

                                    // Progress Ring visual
                                    LinearProgressIndicator(
                                        progress = { pct / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(10.dp)
                                            .clip(RoundedCornerShape(5.dp)),
                                        color = Color.White,
                                        trackColor = Color.White.copy(alpha = 0.2f),
                                    )

                                    Spacer(Modifier.height(8.dp))
                                    Text("${selectedPeriod.label} · ${summary.totalClasses} classes",
                                        color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // ═════════════════════════════════════
                        // STATS ROW — Glassmorphism
                        // ═════════════════════════════════════
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AttStatCard(Modifier.weight(1f), "Total", "${summary.totalClasses}",
                                Color(0xFF3B82F6), Color(0xFFEFF6FF))
                            AttStatCard(Modifier.weight(1f), "Present", "${summary.present}",
                                Color(0xFF10B981), Color(0xFFECFDF5))
                            AttStatCard(Modifier.weight(1f), "Absent", "${summary.absent}",
                                Color(0xFFEF4444), Color(0xFFFEF2F2))
                            AttStatCard(Modifier.weight(1f), "Late", "${summary.late}",
                                Color(0xFFF59E0B), Color(0xFFFFFBEB))
                        }

                        Spacer(Modifier.height(20.dp))

                        // ═════════════════════════════════════
                        // THIS MONTH — Calendar Heatmap
                        // ═════════════════════════════════════
                        if (selectedPeriod == Period.MONTH || selectedPeriod == Period.LAST_MONTH) {
                            MonthHeatmap(filteredRecords)
                            Spacer(Modifier.height(16.dp))
                        }

                        // ═════════════════════════════════════
                        // DAY-BY-DAY RECORDS
                        // ═════════════════════════════════════
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Daily Records",
                                fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("${filteredRecords.size} entries",
                                color = TextSecondaryLight, fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(8.dp))

                        if (filteredRecords.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.CalendarMonth, null,
                                        modifier = Modifier.size(48.dp), tint = TextSecondaryLight.copy(alpha = 0.4f))
                                    Spacer(Modifier.height(8.dp))
                                    Text("No records for ${selectedPeriod.label.lowercase()}",
                                        color = TextSecondaryLight, fontSize = 14.sp)
                                }
                            }
                        } else {
                            filteredRecords.forEachIndexed { index, record ->
                                AttendanceDayCard(
                                    record = record,
                                    index = index,
                                    isFirst = index == 0
                                )
                            }
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

// ── Stat Card ──
@Composable
private fun AttStatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color,
    bgColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = color)
            Text(label, fontSize = 11.sp, color = color.copy(alpha = 0.7f))
        }
    }
}

// ── Month Heatmap ──
@Composable
private fun MonthHeatmap(records: List<Attendance>) {
    val cal = Calendar.getInstance()
    val today = cal.timeInMillis

    // Group by date
    val dayMap = records.groupBy { record ->
        val c = Calendar.getInstance().apply { timeInMillis = record.attendanceDateMs }
        "${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH)}/${c.get(Calendar.YEAR)}"
    }

    // Generate days of current month
    cal.set(Calendar.DAY_OF_MONTH, 1)
    val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sun, 1=Mon...
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val monthName = SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(cal.time)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(monthName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(Color(0xFF10B981), "P")
                    Spacer(Modifier.width(6.dp))
                    LegendDot(Color(0xFFEF4444), "A")
                    Spacer(Modifier.width(6.dp))
                    LegendDot(Color(0xFFF59E0B), "L")
                    Spacer(Modifier.width(6.dp))
                    LegendDot(Color(0xFFE2E8F0), "-")
                }
            }

            Spacer(Modifier.height(10.dp))

            // Day-of-week headers
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { day ->
                    Text(day, modifier = Modifier.weight(1f),
                        fontSize = 9.sp, color = TextSecondaryLight, textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Calendar grid (6 rows max)
            val totalCells = firstDayOfWeek + daysInMonth
            val rows = (totalCells + 6) / 7

            for (row in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (col in 0..6) {
                        val cellIndex = row * 7 + col
                        val dayNum = cellIndex - firstDayOfWeek + 1

                        if (dayNum in 1..daysInMonth) {
                            val dayCal = Calendar.getInstance().apply {
                                timeInMillis = cal.timeInMillis
                                set(Calendar.DAY_OF_MONTH, dayNum)
                            }
                            val key = "${dayNum}/${dayCal.get(Calendar.MONTH)}/${dayCal.get(Calendar.YEAR)}"
                            val record = dayMap[key]
                            val statusColor = when (record?.firstOrNull()?.status) {
                                "present" -> Color(0xFF10B981)
                                "absent" -> Color(0xFFEF4444)
                                "late" -> Color(0xFFF59E0B)
                                else -> Color(0xFFE2E8F0)
                            }
                            val isToday = dayCal.timeInMillis.let { today ->
                                val t = Calendar.getInstance()
                                t.timeInMillis = today
                                t.get(Calendar.DAY_OF_YEAR) == dayCal.get(Calendar.DAY_OF_YEAR) &&
                                        t.get(Calendar.YEAR) == dayCal.get(Calendar.YEAR)
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    modifier = Modifier.size(28.dp),
                                    shape = CircleShape,
                                    color = if (isToday) statusColor.copy(alpha = 0.3f)
                                    else statusColor.copy(alpha = 0.15f),
                                    border = if (isToday) androidx.compose.foundation.BorderStroke(
                                        2.dp, statusColor
                                    ) else null
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("$dayNum",
                                            fontSize = 10.sp,
                                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isToday) statusColor else statusColor.copy(alpha = 0.7f))
                                    }
                                }
                            }
                        } else {
                            // Empty cell
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = color) {}
        Spacer(Modifier.width(2.dp))
        Text(label, fontSize = 9.sp, color = TextSecondaryLight)
    }
}

// ── Day Record Card ──
@Composable
private fun AttendanceDayCard(
    record: Attendance,
    index: Int,
    isFirst: Boolean
) {
    val statusColor = when (record.status) {
        "present" -> Color(0xFF10B981)
        "absent" -> Color(0xFFEF4444)
        "late" -> Color(0xFFF59E0B)
        else -> TextSecondaryLight
    }
    val statusIcon = when (record.status) {
        "present" -> Icons.Filled.CheckCircle
        "absent" -> Icons.Filled.Cancel
        "late" -> Icons.Filled.Schedule
        else -> Icons.Filled.Help
    }
    val statusText = when (record.status) {
        "present" -> "Present"
        "absent" -> "Absent"
        "late" -> "Late"
        else -> record.status
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(300 + index * 50)) +
                slideInVertically(tween(300 + index * 50)) { it / 3 }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(14.dp),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (isFirst) 6.dp else 2.dp
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 4.dp, end = 16.dp,
                        top = 12.dp, bottom = 12.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status color bar
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(statusColor)
                )
                Spacer(Modifier.width(14.dp))

                // Icon
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    color = statusColor.copy(alpha = 0.12f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))

                // Date & Info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())
                            .format(Date(record.attendanceDateMs)),
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                    )
                    if (record.note != null) {
                        Text(record.note, color = TextSecondaryLight, fontSize = 11.sp)
                    }
                }

                // Status badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.12f)
                ) {
                    Text(statusText,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = statusColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
