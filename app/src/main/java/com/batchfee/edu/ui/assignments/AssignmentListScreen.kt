package com.batchfee.edu.ui.assignments

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.AssignmentEntity
import com.batchfee.edu.domain.SessionManager
import java.text.SimpleDateFormat
import java.util.*

private val AsBg    = Color(0xFF07111F)
private val AsCard  = Color(0xFF0F172A)
private val AsStroke = Color(0xFF1E293B)
private val AsAmber = Color(0xFFF59E0B)
private val AsGreen = Color(0xFF22C55E)
private val AsRed   = Color(0xFFEF4444)
private val AsWhite = Color(0xFFF8FAFC)
private val AsMuted = Color(0xFF94A3B8)
private val AsDim   = Color(0xFF64748B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentListScreen(db: AppDatabase, onBack: () -> Unit, onAddAssignment: () -> Unit) {
    val instId = SessionManager.currentInstituteId.value ?: ""
    var assignments by remember { mutableStateOf<List<AssignmentEntity>>(emptyList()) }
    val df = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    LaunchedEffect(instId) { db.assignmentDao().getAll(instId).collect { assignments = it } }

    val published = assignments.filter { it.status == "published" }
    val drafts = assignments.filter { it.status == "draft" }

    Scaffold(
        containerColor = AsBg,
        topBar = {
            TopAppBar(title = { Text("Assignments", color = AsWhite, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AsMuted) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AsBg))
        },
        floatingActionButton = { FloatingActionButton(onClick = onAddAssignment, containerColor = AsAmber, contentColor = Color.White, shape = CircleShape) { Icon(Icons.Filled.Add, "Add") } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(AsBg)) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = AsCard), border = BorderStroke(1.dp, AsStroke)) {
                Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    statC("${published.size}", "Published", AsGreen)
                    statC("${drafts.size}", "Drafts", AsAmber)
                    statC("${assignments.size}", "Total", AsDim)
                }
            }

            if (assignments.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Filled.Assignment, null, Modifier.size(48.dp), tint = AsDim); Spacer(Modifier.height(12.dp)); Text("No assignments yet", color = AsMuted, fontSize = 16.sp); Spacer(Modifier.height(4.dp)); Text("Tap + to create your first assignment", color = AsDim, fontSize = 13.sp) } }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(assignments) { a ->
                        val past = a.dueDateMs != null && a.dueDateMs < System.currentTimeMillis()
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = AsCard), border = BorderStroke(1.dp, AsStroke)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AsAmber.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Assignment, null, tint = AsAmber, modifier = Modifier.size(20.dp)) }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(a.title, color = AsWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        a.subject?.let { Text(it, color = AsAmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                                    }
                                    Surface(shape = RoundedCornerShape(8.dp), color = when(a.status) { "published" -> AsGreen.copy(alpha = 0.15f); "draft" -> AsAmber.copy(alpha = 0.15f); else -> AsDim.copy(alpha = 0.15f) }) {
                                        Text(a.status.replaceFirstChar { it.uppercase() }, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = when(a.status) { "published" -> AsGreen; "draft" -> AsAmber; else -> AsDim }, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Row(Modifier.padding(start = 52.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    a.totalMarks?.let { Text("${it.toInt()} Marks", color = AsAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                    a.className?.let { Text(it, color = AsDim, fontSize = 11.sp) }
                                    Text(a.gradingMethod.replaceFirstChar { it.uppercase() }, color = AsDim, fontSize = 11.sp)
                                }
                                if (a.instructions.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(a.instructions, color = AsMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp) }
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Created ${df.format(Date(a.createdAtMs))}", color = AsDim, fontSize = 11.sp)
                                    a.dueDateMs?.let { due ->
                                        val overdue = due < System.currentTimeMillis()
                                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Schedule, null, Modifier.size(13.dp), tint = if (overdue) AsRed else AsGreen); Spacer(Modifier.width(4.dp)); Text(if (overdue) "Overdue" else "Due ${df.format(Date(due))}", color = if (overdue) AsRed else AsGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun statC(value: String, label: String, color: Color) = Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp); Text(label, color = AsMuted, fontSize = 11.sp) }
