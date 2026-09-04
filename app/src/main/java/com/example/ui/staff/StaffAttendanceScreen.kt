package com.batchfee.edu.ui.staff

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.audit.StaffActivityLogger
import com.batchfee.edu.data.firestore.AttendanceSyncHelper
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.StaffAttendanceEntity
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.data.models.TeachingSessionEntity
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.StaffPermissions
import com.batchfee.edu.ui.attendance.startOfDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
private val AccentSky = Color(0xFF38BDF8)
private val AccentGray = Color(0xFF64748B)

class StaffAttendanceViewModel(private val db: AppDatabase) : ViewModel() {
    private val _staff = MutableStateFlow<List<StaffEntity>>(emptyList())
    val staff = _staff.asStateFlow()

    private val _records = MutableStateFlow<Map<String, StaffAttendanceEntity>>(emptyMap())
    val records = _records.asStateFlow()

    private val _selectedDateMs = MutableStateFlow(startOfDay(System.currentTimeMillis()))
    val selectedDateMs = _selectedDateMs.asStateFlow()

    private val _lastOperation = MutableStateFlow<String?>(null)
    val lastOperation = _lastOperation.asStateFlow()

    // Institute-level Entry & Exit Time Tracking toggle (default OFF)
    private val _trackEntryExit = MutableStateFlow(false)
    val trackEntryExit = _trackEntryExit.asStateFlow()

    init {
        loadDate()
    }

    fun navigateDate(delta: Int) {
        val current = _selectedDateMs.value
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = current }
        cal.add(java.util.Calendar.DAY_OF_MONTH, delta)
        val newDate = startOfDay(cal.timeInMillis)
        if (newDate <= startOfDay(System.currentTimeMillis())) {
            _selectedDateMs.value = newDate
            loadDate()
        }
    }

    fun canGoForward(): Boolean {
        val today = startOfDay(System.currentTimeMillis())
        val next = _selectedDateMs.value + 24L * 60 * 60 * 1000
        return next <= today
    }

    fun canGoBackward(): Boolean = true

    private fun loadDate() {
        val instId = SessionManager.currentInstituteId.value ?: return
        val start = _selectedDateMs.value
        val end = start + 24L * 60 * 60 * 1000
        viewModelScope.launch {
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            db.staffDao().getActiveStaff(instId).collect { _staff.value = it }
        }
        viewModelScope.launch {
            db.staffAttendanceDao().getAttendanceByDate(instId, start, end).collect { rows ->
                _records.value = rows.associateBy { it.staffId }
            }
        }
        viewModelScope.launch {
            db.instituteDao().getInstituteFlow(instId).collect { institute ->
                _trackEntryExit.value = institute?.trackStaffEntryExit ?: false
            }
        }
    }

    /** Owner-only toggle for the whole institute. */
    fun setTrackEntryExit(enabled: Boolean) {
        if (!SessionManager.isAdmin()) return
        val instId = SessionManager.currentInstituteId.value ?: return
        viewModelScope.launch {
            val institute = db.instituteDao().getInstitute(instId) ?: return@launch
            db.instituteDao().updateInstitute(institute.copy(trackStaffEntryExit = enabled))
        }
    }

    fun setEntryTime(staffId: String, timeMs: Long?) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val userId = SessionManager.currentUserId.value ?: return
        val selectedDate = _selectedDateMs.value
        viewModelScope.launch {
            val existing = _records.value[staffId]
            val now = System.currentTimeMillis()
            val record = existing?.copy(entryTimeMs = timeMs, updatedAtMs = now)
                ?: StaffAttendanceEntity(
                    id = UUID.randomUUID().toString(),
                    instituteId = instId,
                    staffId = staffId,
                    attendanceDateMs = selectedDate,
                    status = "present",
                    note = null,
                    markedByUserId = userId,
                    createdAtMs = now,
                    updatedAtMs = now,
                    entryTimeMs = timeMs
                )
            AttendanceSyncHelper.upsertStaffAttendance(record)
            db.staffAttendanceDao().insertOrUpdateAttendance(record)
        }
    }

    fun setExitTime(staffId: String, timeMs: Long?) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val userId = SessionManager.currentUserId.value ?: return
        val selectedDate = _selectedDateMs.value
        viewModelScope.launch {
            val existing = _records.value[staffId]
            val now = System.currentTimeMillis()
            val record = existing?.copy(exitTimeMs = timeMs, updatedAtMs = now)
                ?: StaffAttendanceEntity(
                    id = UUID.randomUUID().toString(),
                    instituteId = instId,
                    staffId = staffId,
                    attendanceDateMs = selectedDate,
                    status = "present",
                    note = null,
                    markedByUserId = userId,
                    createdAtMs = now,
                    updatedAtMs = now,
                    exitTimeMs = timeMs
                )
            AttendanceSyncHelper.upsertStaffAttendance(record)
            db.staffAttendanceDao().insertOrUpdateAttendance(record)
        }
    }

    fun mark(staffId: String, status: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val userId = SessionManager.currentUserId.value ?: return
        val selectedDate = _selectedDateMs.value
        viewModelScope.launch {
            val existing = _records.value[staffId]
            val now = System.currentTimeMillis()
            val record = existing?.copy(status = status, updatedAtMs = now)
                ?: StaffAttendanceEntity(
                    id = UUID.randomUUID().toString(),
                    instituteId = instId,
                    staffId = staffId,
                    attendanceDateMs = selectedDate,
                    status = status,
                    note = null,
                    markedByUserId = userId,
                    createdAtMs = now,
                    updatedAtMs = now
                )
            AttendanceSyncHelper.upsertStaffAttendance(record)
            db.staffAttendanceDao().insertOrUpdateAttendance(record)
            StaffActivityLogger.logCompletedAction(
                db, "staff_attendance_marked", "staff", "Marked staff attendance as ${status.replaceFirstChar { it.uppercase() }}"
            )
            _lastOperation.value = staffId
        }
    }

    fun undo(staffId: String) {
        viewModelScope.launch {
            val existing = _records.value[staffId] ?: return@launch
            db.staffAttendanceDao().deleteAttendance(existing.id)
            StaffActivityLogger.logCompletedAction(
                db, "staff_attendance_removed", "staff", "Removed a staff attendance mark"
            )
            _lastOperation.value = null
        }
    }

    fun canUndo(staffId: String): Boolean = _records.value[staffId] != null
}

class StaffAttendanceViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StaffAttendanceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StaffAttendanceViewModel(db) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffAttendanceScreen(
    db: AppDatabase,
    onBack: () -> Unit
) {
    val canManage = remember {
        SessionManager.isAdmin() || SessionManager.hasPermission(StaffPermissions.MANAGE_STAFF_ATTENDANCE)
    }
    val viewModel: StaffAttendanceViewModel = viewModel(factory = StaffAttendanceViewModelFactory(db))
    val staff by viewModel.staff.collectAsState()
    val records by viewModel.records.collectAsState()
    val trackEntryExit by viewModel.trackEntryExit.collectAsState()
    val todayLabel = remember {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(System.currentTimeMillis()))
    }
    var selectedTab by remember { mutableStateOf("administration") }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Staff Attendance", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        if (!canManage) {
            Box(Modifier.padding(padding).fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Text("You do not have staff attendance permission.", color = TextMuted, fontSize = 14.sp)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Two-part switch: Administration attendance vs Teacher class attendance
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBgAlt)
                    .border(1.dp, BorderSub, RoundedCornerShape(12.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AttendanceTabChip(
                    label = "Administration",
                    selected = selectedTab == "administration",
                    color = Cyan,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedTab = "administration" }
                )
                AttendanceTabChip(
                    label = "Teacher Attendance",
                    selected = selectedTab == "teacher",
                    color = AccentGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedTab = "teacher" }
                )
            }
            Spacer(Modifier.height(12.dp))

            if (selectedTab == "administration") {
                StaffAttendanceSummaryCard(staff = staff, records = records, todayLabel = todayLabel)
                Spacer(Modifier.height(12.dp))

                if (SessionManager.isAdmin()) {
                    EntryExitToggleCard(
                        enabled = trackEntryExit,
                        onToggle = { viewModel.setTrackEntryExit(it) }
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (staff.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No active staff found.", color = TextMuted, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(staff, key = { it.id }) { member ->
                            StaffAttendanceCard(
                                staff = member,
                                record = records[member.id],
                                trackEntryExit = trackEntryExit,
                                onMark = { status -> viewModel.mark(member.id, status) },
                                onSetEntry = { ms -> viewModel.setEntryTime(member.id, ms) },
                                onSetExit = { ms -> viewModel.setExitTime(member.id, ms) }
                            )
                        }
                    }
                }
            } else {
                TeacherAttendanceContent(
                    db = db,
                    staff = staff,
                    records = records,
                    todayLabel = todayLabel
                )
            }
        }
    }
}

@Composable
private fun AttendanceTabChip(
    label: String,
    selected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(9.dp))
            .background(if (selected) color.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) color else TextMuted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun StaffAttendanceSummaryCard(
    staff: List<StaffEntity>,
    records: Map<String, StaffAttendanceEntity>,
    todayLabel: String
) {
    val total = staff.size
    val marked = staff.count { records[it.id] != null }
    val progress = if (total == 0) 0f else marked.toFloat() / total
    Card(
        modifier = Modifier.fillMaxWidth().shadow(3.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.16f)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(todayLabel, color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("$marked/$total staff marked", color = TextMuted, fontSize = 12.sp)
                }
                Icon(Icons.Filled.Badge, null, tint = Cyan, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
                color = AccentGreen,
                trackColor = CardBgAlt
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MiniStat("P", records.values.count { it.status == "present" }, AccentGreen)
                MiniStat("A", records.values.count { it.status == "absent" }, AccentRed)
                MiniStat("L", records.values.count { it.status == "leave" }, AccentSky)
                MiniStat("H", records.values.count { it.status == "holiday" }, AccentGray)
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun StaffAttendanceCard(
    staff: StaffEntity,
    record: StaffAttendanceEntity?,
    trackEntryExit: Boolean,
    onMark: (String) -> Unit,
    onSetEntry: (Long?) -> Unit,
    onSetExit: (Long?) -> Unit
) {
    val status = record?.status ?: "not_marked"
    val activeColor = when (status) {
        "present" -> AccentGreen
        "absent" -> AccentRed
        "leave" -> AccentSky
        "holiday" -> AccentGray
        else -> TextMuted
    }

    Card(
        modifier = Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(12.dp), spotColor = activeColor.copy(alpha = 0.16f)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(ElectricBlue, Cyan))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(staff.fullName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(staff.fullName, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${staff.staffCode} - ${staff.roleTitle}", color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                AttendanceChip("present", "P", status, AccentGreen, Modifier.weight(1f), onMark)
                AttendanceChip("absent", "A", status, AccentRed, Modifier.weight(1f), onMark)
                AttendanceChip("leave", "L", status, AccentSky, Modifier.weight(1f), onMark)
                AttendanceChip("holiday", "H", status, AccentAmber, Modifier.weight(1f), onMark)
            }
            if (trackEntryExit) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    EntryExitField(
                        label = "Entry",
                        timeMs = record?.entryTimeMs,
                        modifier = Modifier.weight(1f),
                        onSetTime = onSetEntry
                    )
                    EntryExitField(
                        label = "Exit",
                        timeMs = record?.exitTimeMs,
                        modifier = Modifier.weight(1f),
                        onSetTime = onSetExit
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryExitToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, if (enabled) AccentGreen.copy(alpha = 0.5f) else BorderSub)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Enable Entry & Exit Time Tracking", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (enabled) "Entry and exit time columns are shown on each staff card." else "Turn on to record staff entry and exit times.",
                    color = TextMuted, fontSize = 10.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = AccentGreen,
                    checkedTrackColor = AccentGreen.copy(alpha = 0.4f),
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = BorderSub
                )
            )
        }
    }
}

@Composable
private fun EntryExitField(
    label: String,
    timeMs: Long?,
    modifier: Modifier = Modifier,
    onSetTime: (Long?) -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val context = LocalContext.current
    Column(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(CardBgAlt)
            .border(1.dp, BorderSub, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Text(
            timeMs?.let { timeFormat.format(Date(it)) } ?: "--:--",
            color = if (timeMs != null) TextWhite else TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable {
                val now = java.util.Calendar.getInstance()
                val initial = timeMs?.let { java.util.Calendar.getInstance().apply { timeInMillis = it } } ?: now
                android.app.TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        val cal = java.util.Calendar.getInstance().apply {
                            timeInMillis = timeMs ?: System.currentTimeMillis()
                            set(java.util.Calendar.HOUR_OF_DAY, hour)
                            set(java.util.Calendar.MINUTE, minute)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }
                        onSetTime(cal.timeInMillis)
                    },
                    initial.get(java.util.Calendar.HOUR_OF_DAY),
                    initial.get(java.util.Calendar.MINUTE),
                    false
                ).show()
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  Teacher Attendance
//  - Monthly teachers: mark P/A/L/H exactly like administration staff.
//  - Per-class / per-hour teachers: Present korar somoy class count dite hobe,
//    optionally kon kon batch + subject class niche record korte parbe.
//    Amount automatic: per_class → rate × classes, per_hour → rate × minutes ÷ 60.
// ═══════════════════════════════════════════════════════════════
private data class ClassDetailDraft(
    val id: String = UUID.randomUUID().toString(),
    val batchId: String? = null,
    val subject: String = "",
    val durationMinutes: String = "60"
)

@Composable
private fun TeacherAttendanceContent(
    db: AppDatabase,
    staff: List<StaffEntity>,
    records: Map<String, StaffAttendanceEntity>,
    todayLabel: String
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val teacherStaff = staff.filter { it.staffCategory == "teacher" }

    var selectedDateMs by remember { mutableStateOf(startOfDay(System.currentTimeMillis())) }
    var batches by remember { mutableStateOf<List<BatchEntity>>(emptyList()) }
    var sessionsForDate by remember { mutableStateOf<List<TeachingSessionEntity>>(emptyList()) }

    // Present-with-classes dialog state (per-class / per-hour teachers only)
    var classCountTeacher by remember { mutableStateOf<StaffEntity?>(null) }
    var classCountText by remember { mutableStateOf("1") }
    var addClassDetails by remember { mutableStateOf(false) }
    val classDetailsDrafts = remember { mutableStateListOf<ClassDetailDraft>() }
    var saving by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        val instId = SessionManager.currentInstituteId.value ?: return@LaunchedEffect
        batches = db.batchDao().getBatchesByInstituteOnce(instId).filter { it.status == "active" }
    }

    LaunchedEffect(selectedDateMs) {
        val instId = SessionManager.currentInstituteId.value ?: return@LaunchedEffect
        val start = startOfDay(selectedDateMs)
        val end = start + 24L * 60 * 60 * 1000
        sessionsForDate = db.teachingSessionDao().getSessionsForDate(instId, start, end)
            .filter { it.deletedAtMs == null }
    }

    Column {
        SnackbarHost(snackbarHostState)
        Spacer(Modifier.height(4.dp))
        // Date navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = selectedDateMs }
                cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
                selectedDateMs = startOfDay(cal.timeInMillis)
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous", tint = Cyan)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(dateFormat.format(Date(selectedDateMs)), color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${sessionsForDate.size} classes recorded",
                    color = TextMuted, fontSize = 11.sp
                )
            }
            IconButton(
                onClick = {
                    val next = selectedDateMs + 24L * 60 * 60 * 1000
                    if (next <= startOfDay(System.currentTimeMillis())) selectedDateMs = next
                },
                enabled = selectedDateMs + 24L * 60 * 60 * 1000 <= startOfDay(System.currentTimeMillis())
            ) {
                Icon(Icons.Filled.KeyboardArrowRight, "Next", tint = if (selectedDateMs + 24L * 60 * 60 * 1000 <= startOfDay(System.currentTimeMillis())) Cyan else TextMuted)
            }
        }

        Spacer(Modifier.height(8.dp))

        if (teacherStaff.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Text(
                    "No teacher-category staff found. Add teachers from Staff module first.",
                    color = TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(teacherStaff, key = { it.id }) { member ->
                    val isPerClass = member.salaryType == "per_class" || member.salaryType == "per_hour"
                    val todaySessions = sessionsForDate.filter { it.staffId == member.id }
                    TeacherAttendanceCard(
                        staff = member,
                        record = records[member.id],
                        isPerClassPaid = isPerClass,
                        todaySessions = todaySessions,
                        batches = batches,
                        onMark = { status ->
                            if (isPerClass && status == "present") {
                                // Per-class/per-hour teacher: class count required
                                classCountTeacher = member
                                classCountText = todaySessions.size.toString().ifBlank { "1" }
                                addClassDetails = todaySessions.isNotEmpty()
                                classDetailsDrafts.clear()
                                todaySessions.forEach { session ->
                                    classDetailsDrafts.add(
                                        ClassDetailDraft(
                                            id = session.id,
                                            batchId = session.batchId,
                                            subject = session.subject.orEmpty(),
                                            durationMinutes = session.durationMinutes.toString()
                                        )
                                    )
                                }
                            } else {
                                val instId = SessionManager.currentInstituteId.value.orEmpty()
                                val userId = SessionManager.currentUserId.value.orEmpty()
                                val now = System.currentTimeMillis()
                                val existing = records[member.id]
                                val record = existing?.copy(status = status, updatedAtMs = now)
                                    ?: StaffAttendanceEntity(
                                        id = UUID.randomUUID().toString(),
                                        instituteId = instId,
                                        staffId = member.id,
                                        attendanceDateMs = startOfDay(selectedDateMs),
                                        status = status,
                                        note = null,
                                        markedByUserId = userId,
                                        createdAtMs = now,
                                        updatedAtMs = now
                                    )
                                scope.launch {
                                    db.staffAttendanceDao().insertOrUpdateAttendance(record)
                                    StaffActivityLogger.logCompletedAction(
                                        db, "teacher_attendance_marked", "staff",
                                        "Marked teacher attendance as ${status.replaceFirstChar { it.uppercase() }}"
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // ── Present + class count dialog ──
    classCountTeacher?.let { teacher ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!saving) classCountTeacher = null },
            containerColor = CardBg,
            shape = RoundedCornerShape(18.dp),
            title = { Text("${teacher.fullName} — Present", color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "How many classes did this teacher take?",
                        color = TextMuted, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = classCountText,
                        onValueChange = { new -> if (new.length <= 3 && new.all { it.isDigit() }) classCountText = new },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Number of classes", fontSize = 11.sp) },
                        colors = staffTextFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(10.dp))
                            .clickable { addClassDetails = !addClassDetails }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Add class details (batch & subject)",
                            color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (addClassDetails) "▴" else "▾",
                            color = Cyan, fontSize = 14.sp
                        )
                    }
                    if (addClassDetails) {
                        Spacer(Modifier.height(8.dp))
                        classDetailsDrafts.forEachIndexed { index, draft ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(Modifier.weight(1.4f)) {
                                    var batchExpanded by remember { mutableStateOf(false) }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBgAlt)
                                            .border(1.dp, BorderSub, RoundedCornerShape(10.dp))
                                            .clickable { batchExpanded = true }
                                            .padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val selectedBatch = batches.find { it.id == draft.batchId }
                                        Text(
                                            selectedBatch?.name ?: "Batch",
                                            color = if (selectedBatch != null) TextWhite else TextMuted,
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text("▾", color = Cyan, fontSize = 11.sp)
                                    }
                                    DropdownMenu(
                                        expanded = batchExpanded,
                                        onDismissRequest = { batchExpanded = false },
                                        containerColor = CardBgAlt
                                    ) {
                                        batches.forEach { batch ->
                                            DropdownMenuItem(
                                                text = { Text(batch.name, color = TextWhite, fontSize = 12.sp) },
                                                onClick = {
                                                    classDetailsDrafts[index] = draft.copy(batchId = batch.id)
                                                    batchExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = draft.subject,
                                    onValueChange = { classDetailsDrafts[index] = draft.copy(subject = it) },
                                    modifier = Modifier.weight(1.2f).height(52.dp),
                                    placeholder = { Text("Subject", fontSize = 10.sp, color = TextMuted) },
                                    colors = staffTextFieldColors(),
                                    shape = RoundedCornerShape(10.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = draft.durationMinutes,
                                    onValueChange = { new -> if (new.length <= 3 && new.all { it.isDigit() }) classDetailsDrafts[index] = draft.copy(durationMinutes = new) },
                                    modifier = Modifier.weight(0.7f).height(52.dp),
                                    placeholder = { Text("Min", fontSize = 10.sp, color = TextMuted) },
                                    colors = staffTextFieldColors(),
                                    shape = RoundedCornerShape(10.dp),
                                    singleLine = true,
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                                )
                                IconButton(
                                    onClick = { classDetailsDrafts.removeAt(index) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Filled.Delete, "Remove", tint = AccentRed, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                        TextButton(onClick = { classDetailsDrafts.add(ClassDetailDraft()) }) {
                            Icon(Icons.Filled.Add, null, tint = Cyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add another class", color = Cyan, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        val count = classCountText.toIntOrNull() ?: 0
                        if (count <= 0) {
                            scope.launch { snackbarHostState.showSnackbar("Enter the number of classes (at least 1).") }
                            return@TextButton
                        }
                        if (addClassDetails && classDetailsDrafts.any { it.batchId == null }) {
                            scope.launch { snackbarHostState.showSnackbar("Select a batch for every class detail.") }
                            return@TextButton
                        }
                        saving = true
                        val instId = SessionManager.currentInstituteId.value.orEmpty()
                        val userId = SessionManager.currentUserId.value.orEmpty()
                        val now = System.currentTimeMillis()
                        val dayStart = startOfDay(selectedDateMs)
                        val rate = when (teacher.salaryType) {
                            "per_class" -> teacher.perClassRate
                            "per_hour" -> teacher.perHourRate
                            else -> 0.0
                        }
                        // Mark Present in staff attendance (same record used by the admin tab)
                        val existing = records[teacher.id]
                        val attendanceRecord = existing?.copy(status = "present", updatedAtMs = now)
                            ?: StaffAttendanceEntity(
                                id = UUID.randomUUID().toString(),
                                instituteId = instId,
                                staffId = teacher.id,
                                attendanceDateMs = dayStart,
                                status = "present",
                                note = null,
                                markedByUserId = userId,
                                createdAtMs = now,
                                updatedAtMs = now
                            )
                        scope.launch {
                            try {
                                db.staffAttendanceDao().insertOrUpdateAttendance(attendanceRecord)
                                StaffActivityLogger.logCompletedAction(
                                    db, "teacher_attendance_marked", "staff",
                                    "Marked teacher present with $count classes"
                                )
                                // Class records: either simple count or detailed batch+subject rows
                                val detailed = addClassDetails && classDetailsDrafts.isNotEmpty()
                                if (detailed) {
                                    classDetailsDrafts.forEach { draft ->
                                        val batch = batches.find { it.id == draft.batchId } ?: return@forEach
                                        val minutes = draft.durationMinutes.toIntOrNull() ?: 60
                                        val key = "${teacher.id}|${batch.id}|${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(dayStart))}|${
                                            draft.subject.trim()
                                        }"
                                        val stableId = "class-${UUID.nameUUIDFromBytes(key.toByteArray()).toString()}"
                                        val amount = when (teacher.salaryType) {
                                            "per_class" -> rate
                                            "per_hour" -> rate * minutes / 60.0
                                            else -> 0.0
                                        }
                                        val session = TeachingSessionEntity(
                                            id = stableId,
                                            instituteId = instId,
                                            staffId = teacher.id,
                                            batchId = batch.id,
                                            sessionKey = key,
                                            subject = draft.subject.trim().ifBlank { null },
                                            sessionDateMs = dayStart,
                                            durationMinutes = minutes,
                                            salaryTypeSnapshot = teacher.salaryType,
                                            rateSnapshot = rate,
                                            calculatedAmount = amount,
                                            createdByUserId = userId,
                                            createdAtMs = now,
                                            updatedAtMs = now
                                        )
                                        val existingSession = db.teachingSessionDao().getBySessionKey(instId, key)
                                        if (existingSession == null || existingSession.deletedAtMs != null) {
                                            com.batchfee.edu.data.firestore.TeachingSessionSyncHelper.createSessionIfAvailable(session)
                                            db.teachingSessionDao().insertSession(session)
                                        }
                                    }
                                } else {
                                    repeat(count) { index ->
                                        val key = "${teacher.id}|count|${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(dayStart))}|$index"
                                        val stableId = "class-${UUID.nameUUIDFromBytes(key.toByteArray()).toString()}"
                                        val amount = when (teacher.salaryType) {
                                            "per_class" -> rate
                                            "per_hour" -> rate * 60 / 60.0 // default 60 min when no detail
                                            else -> 0.0
                                        }
                                        val session = TeachingSessionEntity(
                                            id = stableId,
                                            instituteId = instId,
                                            staffId = teacher.id,
                                            batchId = "",
                                            sessionKey = key,
                                            subject = null,
                                            sessionDateMs = dayStart,
                                            durationMinutes = 60,
                                            salaryTypeSnapshot = teacher.salaryType,
                                            rateSnapshot = rate,
                                            calculatedAmount = amount,
                                            createdByUserId = userId,
                                            createdAtMs = now,
                                            updatedAtMs = now
                                        )
                                        val existingSession = db.teachingSessionDao().getBySessionKey(instId, key)
                                        if (existingSession == null || existingSession.deletedAtMs != null) {
                                            com.batchfee.edu.data.firestore.TeachingSessionSyncHelper.createSessionIfAvailable(session)
                                            db.teachingSessionDao().insertSession(session)
                                        }
                                    }
                                }
                                classCountTeacher = null
                                snackbarHostState.showSnackbar("${teacher.fullName} marked Present — $count class(es) recorded.")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar(e.message ?: "Failed to save.")
                            } finally {
                                saving = false
                            }
                        }
                    }
                ) {
                    if (saving) CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Text("Save", color = Cyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!saving) classCountTeacher = null }) { Text("Cancel", color = TextMuted) }
            }
        )
    }
}

@Composable
private fun TeacherAttendanceCard(
    staff: StaffEntity,
    record: StaffAttendanceEntity?,
    isPerClassPaid: Boolean,
    todaySessions: List<TeachingSessionEntity>,
    batches: List<BatchEntity>,
    onMark: (String) -> Unit
) {
    val status = record?.status ?: "not_marked"
    val activeColor = when (status) {
        "present" -> AccentGreen
        "absent" -> AccentRed
        "leave" -> AccentSky
        "holiday" -> AccentGray
        else -> TextMuted
    }

    Card(
        modifier = Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(12.dp), spotColor = activeColor.copy(alpha = 0.16f)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(AccentGreen, Cyan))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(staff.fullName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(staff.fullName, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (isPerClassPaid) {
                            val rateLabel = if (staff.salaryType == "per_class") "per class" else "per hour"
                            "Teacher • BDT ${if (staff.salaryType == "per_class") staff.perClassRate.toLong() else staff.perHourRate.toLong()} $rateLabel"
                        } else {
                            "Teacher • Monthly salary"
                        },
                        color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (isPerClassPaid && todaySessions.isNotEmpty()) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(AccentAmber.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("${todaySessions.size} class${if (todaySessions.size != 1) "es" else ""}", color = AccentAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (isPerClassPaid && todaySessions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                todaySessions.take(3).forEach { session ->
                    val batch = batches.find { it.id == session.batchId }
                    Text(
                        "• ${session.subject ?: "Class"}${batch?.let { " ($it)" } ?: ""} — BDT ${session.calculatedAmount.toLong()}",
                        color = TextMuted, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (todaySessions.size > 3) {
                    Text("• +${todaySessions.size - 3} more", color = TextMuted, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                AttendanceChip("present", "P", status, AccentGreen, Modifier.weight(1f), onMark)
                AttendanceChip("absent", "A", status, AccentRed, Modifier.weight(1f), onMark)
                AttendanceChip("leave", "L", status, AccentSky, Modifier.weight(1f), onMark)
                AttendanceChip("holiday", "H", status, AccentAmber, Modifier.weight(1f), onMark)
            }
        }
    }
}

@Composable
private fun staffTextFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedBorderColor = Cyan,
    unfocusedBorderColor = BorderSub,
    focusedContainerColor = CardBgAlt,
    unfocusedContainerColor = CardBgAlt,
    focusedLabelColor = Cyan,
    unfocusedLabelColor = TextMuted,
    cursorColor = Cyan
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttendanceChip(
    key: String,
    label: String,
    status: String,
    color: Color,
    modifier: Modifier = Modifier,
    onMark: (String) -> Unit
) {
    FilterChip(
        selected = status == key,
        onClick = { onMark(key) },
        label = {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), color = if (status == key) color else TextMuted)
        },
        modifier = modifier.height(34.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.22f),
            containerColor = CardBgAlt,
            selectedLabelColor = color,
            labelColor = TextMuted
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = BorderSub,
            selectedBorderColor = color,
            enabled = true,
            selected = status == key
        )
    )
}

