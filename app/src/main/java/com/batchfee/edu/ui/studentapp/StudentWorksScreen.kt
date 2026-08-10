package com.batchfee.edu.ui.studentapp

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.domain.StudentSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.*

private val WsBg     = Color(0xFF07111F)
private val WsCard   = Color(0xFF0F172A)
private val WsStroke = Color(0xFF1E293B)
private val WsBlue   = Color(0xFF3B82F6)
private val WsAmber  = Color(0xFFF59E0B)
private val WsGreen  = Color(0xFF22C55E)
private val WsRed    = Color(0xFFEF4444)
private val WsCyan   = Color(0xFF22D3EE)
private val WsWhite  = Color(0xFFF8FAFC)
private val WsMuted  = Color(0xFF94A3B8)
private val WsDim    = Color(0xFF64748B)

data class WorkItem(val id: String, val type: String, val title: String, val description: String, val bookPage: String?, val dueDateMs: Long?, val createdAtMs: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentWorksScreen(onBack: () -> Unit) {
    val iid = StudentSessionManager.instituteId.value ?: ""
    var homeworks by remember { mutableStateOf<List<WorkItem>>(emptyList()) }
    var assignments by remember { mutableStateOf<List<WorkItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val df = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    DisposableEffect(iid) {
        val fs = FirebaseFirestore.getInstance()
        val listeners = mutableListOf<ListenerRegistration>()

        listeners += fs.collection("institutes").document(iid).collection("homework")
            .whereEqualTo("status", "active")
            .addSnapshotListener { snap, _ ->
                homeworks = snap?.documents?.mapNotNull { doc ->
                    WorkItem(id = doc.id, type = "HOMEWORK", title = doc.getString("title") ?: return@mapNotNull null, description = doc.getString("instructions") ?: "", bookPage = doc.getString("bookPage"), dueDateMs = (doc.get("dueDateMs") as? Number)?.toLong(), createdAtMs = (doc.get("createdAtMs") as? Number)?.toLong() ?: 0L)
                }?.sortedByDescending { it.createdAtMs } ?: emptyList()
                loading = false
            }

        listeners += fs.collection("institutes").document(iid).collection("assignments")
            .whereEqualTo("status", "published")
            .addSnapshotListener { snap, _ ->
                assignments = snap?.documents?.mapNotNull { doc ->
                    WorkItem(id = doc.id, type = "ASSIGNMENT", title = doc.getString("title") ?: return@mapNotNull null, description = doc.getString("instructions") ?: "", bookPage = null, dueDateMs = (doc.get("dueDateMs") as? Number)?.toLong(), createdAtMs = (doc.get("createdAtMs") as? Number)?.toLong() ?: 0L)
                }?.sortedByDescending { it.createdAtMs } ?: emptyList()
                loading = false
            }

        onDispose { listeners.forEach { it.remove() } }
    }

    Scaffold(containerColor = WsBg,
        topBar = { TopAppBar(title = { Text("Works", color = WsWhite, fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = WsBg)) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(WsBg)) {
            if (loading) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = WsCyan) }
            else if (homeworks.isEmpty() && assignments.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No work assigned yet.", color = WsMuted) }
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (homeworks.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(WsBlue.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Home, null, tint = WsBlue, modifier = Modifier.size(18.dp)) }
                            Spacer(Modifier.width(8.dp))
                            Text("Homework (${homeworks.size})", color = WsWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                    items(homeworks) { w -> WCard(w, df, WsBlue) }
                }
                if (assignments.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(WsAmber.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Assignment, null, tint = WsAmber, modifier = Modifier.size(18.dp)) }
                            Spacer(Modifier.width(8.dp))
                            Text("Assignments (${assignments.size})", color = WsWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                    items(assignments) { w -> WCard(w, df, WsAmber) }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun WCard(w: WorkItem, df: SimpleDateFormat, accent: Color) {
    val isPast = w.dueDateMs != null && w.dueDateMs < System.currentTimeMillis()
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = WsCard), border = BorderStroke(1.dp, WsStroke)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(if (w.type == "HOMEWORK") Icons.Filled.Home else Icons.Filled.Assignment, null, tint = accent, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Text(w.title, Modifier.weight(1f), color = WsWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (w.description.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(w.description, color = WsMuted, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp) }
            w.bookPage?.let { Spacer(Modifier.height(4.dp)); Text("📖 $it", color = WsDim, fontSize = 11.sp) }
            if (w.dueDateMs != null) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().background(if (isPast) WsRed.copy(alpha = 0.1f) else WsGreen.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(14.dp), tint = if (isPast) WsRed else WsGreen)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPast) "Due: ${df.format(Date(w.dueDateMs))} (overdue)" else "Due: ${df.format(Date(w.dueDateMs))}", color = if (isPast) WsRed else WsGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
