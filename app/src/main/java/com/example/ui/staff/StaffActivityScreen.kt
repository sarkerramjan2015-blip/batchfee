package com.batchfee.edu.ui.staff

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.AuditLogSyncHelper
import com.batchfee.edu.data.models.AuditLogEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val ActivityBg = Color(0xFF07111F)
private val ActivityCard = Color(0xFF0F172A)
private val ActivityCardAlt = Color(0xFF111827)
private val ActivityBorder = Color(0xFF1E293B)
private val ActivityCyan = Color(0xFF22D3EE)
private val ActivityBlue = Color(0xFF3B82F6)
private val ActivityGreen = Color(0xFF22C55E)
private val ActivityAmber = Color(0xFFF59E0B)
private val ActivityText = Color(0xFFF8FAFC)
private val ActivityMuted = Color(0xFF94A3B8)

private enum class ActivityPeriod(val label: String) {
    TODAY("Today"),
    LAST_7_DAYS("7 days"),
    THIS_MONTH("This month"),
    ALL("All")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffActivityScreen(db: AppDatabase, onBack: () -> Unit) {
    val instituteId = SessionManager.currentInstituteId.collectAsState().value
    val logs by remember(instituteId) {
        instituteId?.takeIf { it.isNotBlank() }
            ?.let { db.auditLogDao().getAuditLogsByInstitute(it) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList<AuditLogEntity>())
    }.collectAsState(initial = emptyList())
    val staff by remember(instituteId) {
        instituteId?.takeIf { it.isNotBlank() }
            ?.let { db.staffDao().getAllStaffByInstitute(it) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList<com.batchfee.edu.data.models.StaffEntity>())
    }.collectAsState(initial = emptyList())
    var period by rememberSaveable { mutableStateOf(ActivityPeriod.TODAY) }
    var selectedStaffId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(instituteId) {
        if (!instituteId.isNullOrBlank()) {
            withContext(Dispatchers.IO) { AuditLogSyncHelper.syncAllFromFirestore(db, instituteId) }
        }
    }

    val staffNames = remember(staff) { staff.associate { it.id to it.fullName } }
    val filteredLogs = remember(logs, period, selectedStaffId) {
        logs.filter { log ->
            (selectedStaffId == null || log.userId == selectedStaffId) &&
                log.createdAtMs >= periodStart(period)
        }
    }

    Scaffold(
        containerColor = ActivityBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Staff Activity", color = ActivityText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Login and work history", color = ActivityMuted, fontSize = 11.sp)
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
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ActivitySummaryCard(
                    logs = filteredLogs,
                    staffCount = if (selectedStaffId == null) staff.size else 1
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                    ActivityPeriod.entries.forEach { option ->
                        FilterChip(
                            selected = period == option,
                            onClick = { period = option },
                            label = { Text(option.label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ActivityCyan.copy(alpha = 0.18f),
                                selectedLabelColor = ActivityCyan,
                                labelColor = ActivityMuted
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = period == option,
                                borderColor = ActivityBorder,
                                selectedBorderColor = ActivityCyan.copy(alpha = 0.6f)
                            )
                        )
                    }
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = selectedStaffId == null,
                        onClick = { selectedStaffId = null },
                        label = { Text("All staff", fontSize = 11.sp) },
                        colors = activityStaffChipColors(selectedStaffId == null)
                    )
                    staff.forEach { member ->
                        FilterChip(
                            selected = selectedStaffId == member.id,
                            onClick = { selectedStaffId = member.id },
                            label = { Text(member.fullName, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = activityStaffChipColors(selectedStaffId == member.id)
                        )
                    }
                }
            }
            if (filteredLogs.isEmpty()) {
                item { EmptyActivityCard(period) }
            } else {
                itemsIndexed(filteredLogs, key = { _, log -> log.id }) { index, log ->
                    val previous = filteredLogs.getOrNull(index - 1)
                    if (previous == null || !isSameDay(previous.createdAtMs, log.createdAtMs)) {
                        ActivityDateLabel(log.createdAtMs)
                    }
                    ActivityRow(log, staffNames[log.userId] ?: "Staff member")
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun ActivitySummaryCard(logs: List<AuditLogEntity>, staffCount: Int) {
    val loginCount = logs.count { it.action.contains("login", ignoreCase = true) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ActivityCard),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, ActivityBorder)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(ActivityBlue.copy(alpha = 0.17f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.History, null, tint = ActivityCyan, modifier = Modifier.size(20.dp)) }
            Column(Modifier.weight(1f)) {
                Text(if (logs.size == 1) "1 activity" else "${logs.size} activities", color = ActivityText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("$loginCount login${if (loginCount == 1) "" else "s"} · $staffCount staff", color = ActivityMuted, fontSize = 11.sp)
            }
            Icon(Icons.Filled.Groups, null, tint = ActivityBlue, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun EmptyActivityCard(period: ActivityPeriod) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ActivityCard),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, ActivityBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 38.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.History, null, tint = ActivityMuted.copy(alpha = 0.5f), modifier = Modifier.size(38.dp))
            Spacer(Modifier.height(8.dp))
            Text("No staff activity ${period.label.lowercase()}.", color = ActivityMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ActivityDateLabel(timestamp: Long) {
    val format = remember { SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault()) }
    Text(format.format(Date(timestamp)), color = ActivityCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 1.dp))
}

@Composable
private fun ActivityRow(log: AuditLogEntity, staffName: String) {
    val (icon, color) = activityVisual(log.action)
    val timeFmt = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ActivityCardAlt),
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, ActivityBorder)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(staffName, color = ActivityText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(log.description, color = ActivityMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(timeFmt.format(Date(log.createdAtMs)), color = ActivityCyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun activityVisual(action: String): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> = when {
    action.contains("login", ignoreCase = true) -> Icons.Filled.Login to ActivityCyan
    action.contains("payment", ignoreCase = true) || action.contains("fee", ignoreCase = true) -> Icons.Filled.Payments to ActivityGreen
    action.contains("attendance", ignoreCase = true) -> Icons.Filled.CheckCircle to ActivityBlue
    action.contains("student", ignoreCase = true) -> Icons.Filled.Person to ActivityCyan
    action.contains("batch", ignoreCase = true) -> Icons.Filled.Groups to ActivityAmber
    action.contains("exam", ignoreCase = true) || action.contains("result", ignoreCase = true) -> Icons.Filled.School to ActivityAmber
    action.contains("salary", ignoreCase = true) -> Icons.Filled.Payments to ActivityGreen
    action.contains("expense", ignoreCase = true) -> Icons.Filled.Payments to ActivityAmber
    else -> Icons.Filled.Work to ActivityMuted
}

@Composable
private fun activityStaffChipColors(selected: Boolean) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = ActivityBlue.copy(alpha = 0.18f),
    selectedLabelColor = ActivityCyan,
    labelColor = ActivityMuted
)

private fun periodStart(period: ActivityPeriod): Long {
    val calendar = Calendar.getInstance()
    when (period) {
        ActivityPeriod.TODAY -> {
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        }
        ActivityPeriod.LAST_7_DAYS -> calendar.add(Calendar.DAY_OF_YEAR, -6)
        ActivityPeriod.THIS_MONTH -> {
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        }
        ActivityPeriod.ALL -> return 0L
    }
    return calendar.timeInMillis
}

private fun isSameDay(first: Long, second: Long): Boolean {
    val firstDay = Calendar.getInstance().apply { timeInMillis = first }
    val secondDay = Calendar.getInstance().apply { timeInMillis = second }
    return firstDay.get(Calendar.YEAR) == secondDay.get(Calendar.YEAR) &&
        firstDay.get(Calendar.DAY_OF_YEAR) == secondDay.get(Calendar.DAY_OF_YEAR)
}
