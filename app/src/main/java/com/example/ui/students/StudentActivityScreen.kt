package com.batchfee.edu.ui.students

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.activity.StudentActivityEvent
import com.batchfee.edu.data.activity.StudentActivityFeedRepository
import com.batchfee.edu.data.activity.StudentPresence
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ActivityBg = Color(0xFF07111F)
private val ActivityCard = Color(0xFF0F172A)
private val ActivityCardAlt = Color(0xFF111827)
private val ActivityBorder = Color(0xFF1E293B)
private val ActivityCyan = Color(0xFF22D3EE)
private val ActivityBlue = Color(0xFF3B82F6)
private val ActivityGreen = Color(0xFF22C55E)
private val ActivityMuted = Color(0xFF94A3B8)
private val ActivityText = Color(0xFFF8FAFC)

private const val ONLINE_WINDOW_MS = 5 * 60 * 1000L

private enum class StudentActivityPeriod(val label: String) {
    TODAY("Today"), LAST_7_DAYS("7 days"), THIS_MONTH("This month")
}

private data class StudentActivityRow(
    val student: StudentEntity,
    val presence: StudentPresence?,
    val latestEvent: StudentActivityEvent?,
    val lastLogin: StudentActivityEvent?,
    val batchIds: List<String>,
    val lastActiveAtMs: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentActivityScreen(db: AppDatabase, onBack: () -> Unit) {
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val students by remember(instituteId) {
        instituteId?.takeIf { it.isNotBlank() }
            ?.let { db.studentDao().getStudentsByInstitute(it) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList<StudentEntity>())
    }.collectAsState(initial = emptyList())
    val batches by remember(instituteId) {
        instituteId?.takeIf { it.isNotBlank() }
            ?.let { db.batchDao().getBatchesByInstitute(it) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList<BatchEntity>())
    }.collectAsState(initial = emptyList())

    var events by remember { mutableStateOf<List<StudentActivityEvent>>(emptyList()) }
    var presence by remember { mutableStateOf<List<StudentPresence>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedBatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableStateOf(StudentActivityPeriod.TODAY) }
    var selectedRow by remember { mutableStateOf<StudentActivityRow?>(null) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val feedRepository = remember { StudentActivityFeedRepository() }

    // The feed is owner-authorized by the backend and refreshes while this
    // page is open. This keeps current login/presence information fresh even
    // on devices that have not yet received a Firestore rules update.
    LaunchedEffect(instituteId) {
        val activeInstituteId = instituteId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        while (isActive) {
            runCatching { feedRepository.load(activeInstituteId) }
                .onSuccess { feed ->
                    events = feed.events
                    presence = feed.presence
                    loadError = null
                }
                .onFailure { loadError = "Student activity could not be loaded right now." }
            delay(30_000L)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    val studentsById = remember(students) { students.associateBy { it.id } }
    val batchesById = remember(batches) { batches.associateBy { it.id } }
    val periodStart = remember(period, currentTime) { studentActivityPeriodStart(period, currentTime) }
    val rows = remember(events, presence, studentsById, selectedBatchId, periodStart) {
        val eventsByStudent = events.groupBy { it.studentId }
        val presenceByStudent = presence.associateBy { it.studentId }
        (eventsByStudent.keys + presenceByStudent.keys).mapNotNull { studentId ->
            val student = studentsById[studentId] ?: return@mapNotNull null
            val studentEvents = eventsByStudent[studentId].orEmpty()
            val latestEvent = studentEvents.maxByOrNull { it.occurredAtMs }
            val latestLogin = studentEvents.filter { it.eventType == "login" }.maxByOrNull { it.occurredAtMs }
            val studentPresence = presenceByStudent[studentId]
            val batchIds = (studentPresence?.batchIds.orEmpty() + latestEvent?.batchIds.orEmpty()).distinct()
            val lastActive = maxOf(studentPresence?.lastSeenAtMs ?: 0L, latestEvent?.occurredAtMs ?: 0L)
            if (lastActive < periodStart) return@mapNotNull null
            if (selectedBatchId != null && selectedBatchId !in batchIds) return@mapNotNull null
            StudentActivityRow(student, studentPresence, latestEvent, latestLogin, batchIds, lastActive)
        }.sortedByDescending { it.lastActiveAtMs }
    }
    val onlineCount = rows.count { (currentTime - (it.presence?.lastSeenAtMs ?: 0L)) <= ONLINE_WINDOW_MS }
    val activeTodayCount = rows.count { it.lastActiveAtMs >= studentActivityPeriodStart(StudentActivityPeriod.TODAY, currentTime) }
    val eventCount = events.count { it.occurredAtMs >= periodStart &&
        (selectedBatchId == null || selectedBatchId in it.batchIds) }

    Scaffold(
        containerColor = ActivityBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Student Activity", color = ActivityText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Realtime login and app activity", color = ActivityMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ActivityText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ActivityBg)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { StudentActivityHero(onlineCount, activeTodayCount, eventCount) }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StudentActivityPeriod.entries.forEach { option ->
                        ActivityFilterChip(option.label, period == option) { period = option }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActivityFilterChip("All batches", selectedBatchId == null) { selectedBatchId = null }
                    batches.forEach { batch ->
                        ActivityFilterChip(batch.name, selectedBatchId == batch.id) { selectedBatchId = batch.id }
                    }
                }
            }
            if (loadError != null) {
                item { ActivityInfoCard(loadError!!) }
            } else if (rows.isEmpty()) {
                item { EmptyStudentActivityCard(period) }
            } else {
                items(rows, key = { it.student.id }) { row ->
                    StudentActivityRowCard(
                        row = row,
                        currentTime = currentTime,
                        batchNames = row.batchIds.mapNotNull { batchesById[it]?.name },
                        onClick = { selectedRow = row },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    selectedRow?.let { row ->
        val history = events.filter { it.studentId == row.student.id }
            .sortedByDescending { it.occurredAtMs }.take(20)
        ModalBottomSheet(
            onDismissRequest = { selectedRow = null },
            containerColor = ActivityCard,
        ) {
            StudentActivityDetailSheet(
                row = row,
                history = history,
                batchesById = batchesById,
                currentTime = currentTime,
            )
        }
    }
}

@Composable
private fun StudentActivityHero(onlineCount: Int, activeTodayCount: Int, eventCount: Int) {
    val transition = rememberInfiniteTransition(label = "studentActivityPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.66f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "studentActivityPulseAlpha",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = ActivityCard),
        border = BorderStroke(1.dp, ActivityCyan.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(Color(0xFF10284A), ActivityCard, Color(0xFF0D2034)))
            ).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                        .background(ActivityCyan.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Insights, null, tint = ActivityCyan, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Student app activity", color = ActivityText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Live activity from student devices", color = ActivityMuted, fontSize = 11.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(ActivityGreen.copy(alpha = pulse)))
                    Spacer(Modifier.width(5.dp))
                    Text("Live", color = ActivityGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ActivityMetric("Online", onlineCount.toString(), ActivityGreen)
                ActivityMetric("Active today", activeTodayCount.toString(), ActivityCyan)
                ActivityMetric("Activities", eventCount.toString(), ActivityBlue)
            }
        }
    }
}

@Composable
private fun ActivityMetric(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
        Text(value, color = color, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = ActivityMuted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun ActivityFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ActivityCyan.copy(alpha = 0.16f),
            selectedLabelColor = ActivityCyan,
            labelColor = ActivityMuted,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = ActivityBorder,
            selectedBorderColor = ActivityCyan.copy(alpha = 0.62f),
        )
    )
}

@Composable
private fun StudentActivityRowCard(
    row: StudentActivityRow,
    currentTime: Long,
    batchNames: List<String>,
    onClick: () -> Unit,
) {
    val isOnline = currentTime - (row.presence?.lastSeenAtMs ?: 0L) <= ONLINE_WINDOW_MS
    val label = row.presence?.lastActivityLabel ?: row.latestEvent?.label ?: "Used student app"
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ActivityCardAlt),
        border = BorderStroke(1.dp, if (isOnline) ActivityGreen.copy(alpha = 0.42f) else ActivityBorder),
    ) {
        Row(modifier = Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            StudentActivityAvatar(row.student, isOnline)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.student.fullName, color = ActivityText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ActivityStatusChip(if (isOnline) "Online" else "Last active", isOnline)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    batchNames.take(2).joinToString(" · ").ifBlank { "No active batch" },
                    color = ActivityCyan,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, null, tint = ActivityMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(label, color = ActivityMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Text(relativeTime(row.lastActiveAtMs, currentTime), color = ActivityText, fontSize = 11.sp,
                        fontWeight = FontWeight.Medium)
                }
                row.lastLogin?.let { login ->
                    Spacer(Modifier.height(4.dp))
                    Text("Login ${formatTime(login.occurredAtMs)}", color = ActivityMuted.copy(alpha = 0.8f), fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun StudentActivityAvatar(student: StudentEntity, online: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(ActivityBlue, ActivityCyan))),
            contentAlignment = Alignment.Center,
        ) {
            Text(student.fullName.firstOrNull()?.uppercase() ?: "S", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            student.photoUri?.takeIf { it.isNotBlank() }?.let { photo ->
                AsyncImage(model = photo, contentDescription = "${student.fullName} photo", modifier = Modifier.fillMaxSize().clip(CircleShape))
            }
        }
        if (online) {
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape)
                    .background(ActivityGreen).padding(2.dp).background(ActivityCard, CircleShape)
            )
        }
    }
}

@Composable
private fun ActivityStatusChip(label: String, online: Boolean) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background((if (online) ActivityGreen else ActivityBlue).copy(alpha = 0.15f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (online) Icon(Icons.Filled.Circle, null, tint = ActivityGreen, modifier = Modifier.size(7.dp))
        if (online) Spacer(Modifier.width(4.dp))
        Text(label, color = if (online) ActivityGreen else ActivityBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyStudentActivityCard(period: StudentActivityPeriod) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ActivityCard),
        border = BorderStroke(1.dp, ActivityBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 42.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.History, null, tint = ActivityMuted.copy(alpha = 0.55f), modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
            Text("No student activity ${period.label.lowercase()}.", color = ActivityText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("New login and app activity will appear here in realtime.", color = ActivityMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ActivityInfoCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = ActivityCard), border = BorderStroke(1.dp, ActivityBorder),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Wifi, null, tint = ActivityCyan)
            Spacer(Modifier.width(10.dp))
            Text(message, color = ActivityMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StudentActivityDetailSheet(
    row: StudentActivityRow,
    history: List<StudentActivityEvent>,
    batchesById: Map<String, BatchEntity>,
    currentTime: Long,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StudentActivityAvatar(row.student, currentTime - (row.presence?.lastSeenAtMs ?: 0L) <= ONLINE_WINDOW_MS)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.student.fullName, color = ActivityText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(row.student.studentCode, color = ActivityCyan, fontSize = 11.sp)
            }
            Icon(Icons.Filled.Person, null, tint = ActivityMuted)
        }
        Spacer(Modifier.height(18.dp))
        Text("Recent activity", color = ActivityText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(9.dp))
        if (history.isEmpty()) {
            Text("No activity history yet.", color = ActivityMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
        } else {
            history.forEach { event ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(31.dp).clip(CircleShape).background(ActivityCyan.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                        Icon(if (event.eventType == "login") Icons.Filled.Login else Icons.Filled.AccessTime, null,
                            tint = ActivityCyan, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(event.label, color = ActivityText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        val batchName = event.batchIds.mapNotNull { batchesById[it]?.name }.joinToString(" · ")
                        if (batchName.isNotBlank()) Text(batchName, color = ActivityMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(formatDateTime(event.occurredAtMs), color = ActivityMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun studentActivityPeriodStart(period: StudentActivityPeriod, now: Long): Long {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
    when (period) {
        StudentActivityPeriod.TODAY -> {
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
        }
        StudentActivityPeriod.LAST_7_DAYS -> {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, -6)
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
        }
        StudentActivityPeriod.THIS_MONTH -> {
            calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
        }
    }
    return calendar.timeInMillis
}

private fun relativeTime(timeMs: Long, now: Long): String {
    val diff = (now - timeMs).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        else -> "${diff / 86_400_000L}d ago"
    }
}

private fun formatTime(timeMs: Long): String =
    SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timeMs))

private fun formatDateTime(timeMs: Long): String =
    SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(timeMs))
