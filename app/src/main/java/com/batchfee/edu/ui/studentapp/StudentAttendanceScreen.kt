package com.batchfee.edu.ui.studentapp

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.domain.StudentSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.*

private val AbBg     = Color(0xFF07111F)
private val AbCard   = Color(0xFF0F172A)
private val AbStroke = Color(0xFF1E293B)
private val AbCyan   = Color(0xFF22D3EE)
private val AbGreen  = Color(0xFF22C55E)
private val AbRed    = Color(0xFFEF4444)
private val AbViolet = Color(0xFF8B5CF6)
private val AbWhite  = Color(0xFFF8FAFC)
private val AbMuted  = Color(0xFF94A3B8)
private val AbDim    = Color(0xFF64748B)

data class AttRecord(val id: String, val dateMs: Long, val status: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentAttendanceScreen(onBack: () -> Unit) {
    val sid by StudentSessionManager.studentId.collectAsState()
    val iid by StudentSessionManager.instituteId.collectAsState()
    val studentId = sid.orEmpty()
    val instituteId = iid.orEmpty()
    var allRecords by remember(studentId, instituteId) { mutableStateOf<List<AttRecord>>(emptyList()) }
    var loading by remember(studentId, instituteId) { mutableStateOf(true) }
    var syncError by remember(studentId, instituteId) { mutableStateOf<String?>(null) }

    // Month navigation
    val cal = remember { Calendar.getInstance() }
    var selectedMonth by remember { mutableIntStateOf(cal.get(Calendar.MONTH)) }
    var selectedYear by remember { mutableIntStateOf(cal.get(Calendar.YEAR)) }
    val monthDf = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    fun reportListenerError(error: FirebaseFirestoreException?) {
        if (error != null) {
            loading = false
            syncError = if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                "Live access is no longer available. Please sign in again."
            } else {
                "Live updates are paused. Check your connection."
            }
        }
    }

    DisposableEffect(instituteId, studentId) {
        if (instituteId.isBlank() || studentId.isBlank()) {
            onDispose { }
        } else {
        val listener = FirebaseFirestore.getInstance().collection("institutes").document(instituteId).collection("attendance").whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error != null) return@addSnapshotListener
                allRecords = snap?.documents?.map { doc ->
                    AttRecord(id = doc.id, dateMs = (doc.get("attendanceDateMs") as? Number)?.toLong() ?: 0L, status = doc.getString("status") ?: "absent")
                }?.sortedByDescending { it.dateMs } ?: emptyList()
                loading = false
            }
        onDispose { listener.remove() }
        }
    }

    // Filter records for selected month
    val calStart = Calendar.getInstance().apply { set(selectedYear, selectedMonth, 1, 0, 0, 0) }
    val startMs = calStart.timeInMillis
    val calEnd = Calendar.getInstance().apply { set(selectedYear, selectedMonth, calStart.getActualMaximum(Calendar.DAY_OF_MONTH), 23, 59, 59) }
    val endMs = calEnd.timeInMillis
    val monthRecords = allRecords.filter { it.dateMs in startMs..endMs }
    val presentCount = monthRecords.count { it.status == "present" }
    val absentCount = monthRecords.count { it.status == "absent" }
    val lateCount = monthRecords.count { it.status == "late" }

    // Build day grid
    val daysInMonth = calStart.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = calStart.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sun
    val today = Calendar.getInstance()
    val todayDay = if (selectedMonth == today.get(Calendar.MONTH) && selectedYear == today.get(Calendar.YEAR)) today.get(Calendar.DAY_OF_MONTH) else -1
    val days = (1..daysInMonth).toList()

    Scaffold(containerColor = AbBg,
        topBar = { TopAppBar(title = { Text("Attendance", color = AbWhite, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AbMuted) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AbBg)) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(AbBg)) {
            syncError?.let { message ->
                Surface(color = AbRed.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SyncProblem, null, tint = AbRed, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(message, color = AbRed, fontSize = 12.sp)
                    }
                }
            }
            // Month selector
            Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = AbCard), border = BorderStroke(1.dp, AbStroke)) {
                Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        calStart.set(selectedYear, selectedMonth, 1); calStart.add(Calendar.MONTH, -1)
                        selectedMonth = calStart.get(Calendar.MONTH); selectedYear = calStart.get(Calendar.YEAR)
                    }) { Icon(Icons.Filled.ChevronLeft, "Prev", tint = AbCyan) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(monthDf.format(Date(startMs)), color = AbWhite, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        Text("P:$presentCount A:$absentCount L:$lateCount", color = AbMuted, fontSize = 11.sp)
                    }
                    IconButton(onClick = {
                        calStart.set(selectedYear, selectedMonth, 1); calStart.add(Calendar.MONTH, 1)
                        selectedMonth = calStart.get(Calendar.MONTH); selectedYear = calStart.get(Calendar.YEAR)
                    }, enabled = !(selectedMonth == today.get(Calendar.MONTH) && selectedYear == today.get(Calendar.YEAR))) {
                        Icon(Icons.Filled.ChevronRight, "Next", tint = if (selectedMonth == today.get(Calendar.MONTH) && selectedYear == today.get(Calendar.YEAR)) AbDim else AbCyan)
                    }
                }
            }

            if (loading) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AbCyan) }
            else {
                // Day-of-week headers
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                        Text(day, Modifier.weight(1f), color = AbDim, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(6.dp))

                // Day grid
                LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                    // Empty slots before first day
                    items((0 until firstDayOfWeek).toList()) { Spacer(Modifier) }
                    // Day cells
                    items(days) { day ->
                        val dayCal = Calendar.getInstance().apply { set(selectedYear, selectedMonth, day, 12, 0, 0) }
                        val dayMs = dayCal.timeInMillis
                        val record = monthRecords.find { val c = Calendar.getInstance().apply { timeInMillis = it.dateMs }; c.get(Calendar.DAY_OF_MONTH) == day }
                        val isToday = day == todayDay
                        Card(
                            Modifier.padding(3.dp).aspectRatio(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = when {
                                record?.status == "present" -> AbGreen.copy(alpha = 0.15f)
                                record?.status == "absent" -> AbRed.copy(alpha = 0.1f)
                                record?.status == "late" -> Color(0xFFF59E0B).copy(alpha = 0.15f)
                                else -> AbCard
                            }),
                            border = if (isToday) BorderStroke(1.5.dp, AbCyan) else BorderStroke(1.dp, AbStroke)
                        ) {
                            Column(Modifier.fillMaxSize().padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text("$day", color = if (isToday) AbCyan else AbWhite, fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.Normal, fontSize = 12.sp)
                                when (record?.status) {
                                    "present" -> Icon(Icons.Filled.Check, null, tint = AbGreen, modifier = Modifier.size(18.dp))
                                    "absent" -> Icon(Icons.Filled.Close, null, tint = AbRed, modifier = Modifier.size(18.dp))
                                    "late" -> Text("L", color = Color(0xFFF59E0B), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                                    else -> if (dayMs < System.currentTimeMillis()) Icon(Icons.Filled.Remove, null, tint = AbDim.copy(alpha = 0.3f), modifier = Modifier.size(14.dp)) else {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
