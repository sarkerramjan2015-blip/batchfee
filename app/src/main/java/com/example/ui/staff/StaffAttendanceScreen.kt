package com.example.ui.staff

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.models.StaffAttendanceEntity
import com.example.data.models.StaffEntity
import com.example.domain.SessionManager
import com.example.domain.StaffPermissions
import com.example.ui.attendance.startOfDay
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

    init {
        loadToday()
    }

    private fun loadToday() {
        val instId = SessionManager.currentInstituteId.value ?: return
        val start = startOfDay(System.currentTimeMillis())
        val end = start + 24L * 60 * 60 * 1000
        viewModelScope.launch {
            db.staffDao().getActiveStaff(instId).collect { _staff.value = it }
        }
        viewModelScope.launch {
            db.staffAttendanceDao().getAttendanceByDate(instId, start, end).collect { rows ->
                _records.value = rows.associateBy { it.staffId }
            }
        }
    }

    fun mark(staffId: String, status: String) {
        val instId = SessionManager.currentInstituteId.value ?: return
        val userId = SessionManager.currentUserId.value ?: return
        val today = startOfDay(System.currentTimeMillis())
        viewModelScope.launch {
            val existing = _records.value[staffId]
            val now = System.currentTimeMillis()
            val record = existing?.copy(status = status, updatedAtMs = now)
                ?: StaffAttendanceEntity(
                    id = UUID.randomUUID().toString(),
                    instituteId = instId,
                    staffId = staffId,
                    attendanceDateMs = today,
                    status = status,
                    note = null,
                    markedByUserId = userId,
                    createdAtMs = now,
                    updatedAtMs = now
                )
            db.staffAttendanceDao().insertOrUpdateAttendance(record)
        }
    }
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
    val todayLabel = remember {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(System.currentTimeMillis()))
    }

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
            StaffAttendanceSummaryCard(staff = staff, records = records, todayLabel = todayLabel)
            Spacer(Modifier.height(12.dp))

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
                            status = records[member.id]?.status ?: "not_marked",
                            onMark = { status -> viewModel.mark(member.id, status) }
                        )
                    }
                }
            }
        }
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
    status: String,
    onMark: (String) -> Unit
) {
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
        }
    }
}

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
