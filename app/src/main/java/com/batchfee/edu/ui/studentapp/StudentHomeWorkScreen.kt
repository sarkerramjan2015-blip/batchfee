package com.batchfee.edu.ui.studentapp

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.models.HomeworkEntity
import com.batchfee.edu.data.models.AssignmentEntity
import com.batchfee.edu.domain.StudentSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun StudentHomeworkScreen(onBack: () -> Unit) = HomeworkView(onBack)
@Composable
fun StudentAssignmentsScreen(onBack: () -> Unit) = AssignmentView(onBack)

@Composable
private fun HomeworkView(onBack: () -> Unit) {
    val iid = StudentSessionManager.instituteId.value ?: ""
    var homeworks by remember { mutableStateOf<List<HomeworkEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val df = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    LaunchedEffect(iid) {
        try {
            val snap = FirebaseFirestore.getInstance().collection("institutes").document(iid).collection("homework").whereEqualTo("status", "active").get().await()
            homeworks = snap.documents.mapNotNull { doc ->
                HomeworkEntity(id = doc.id, instituteId = iid, batchId = null, title = doc.getString("title") ?: return@mapNotNull null, subject = doc.getString("subject"), className = doc.getString("className"), instructions = doc.getString("instructions") ?: "", bookPage = doc.getString("bookPage"), startDateMs = (doc.get("startDateMs") as? Number)?.toLong() ?: 0L, dueDateMs = (doc.get("dueDateMs") as? Number)?.toLong(), attachmentUri = null, requiresSubmission = doc.getBoolean("requiresSubmission") ?: false, status = doc.getString("status") ?: "active", createdAtMs = (doc.get("createdAtMs") as? Number)?.toLong() ?: 0L, updatedAtMs = 0L, archivedAtMs = null)
            }.sortedByDescending { it.createdAtMs }
        } catch (_: Exception) {}
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Homework", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold))
        Spacer(Modifier.height(16.dp))
        if (loading) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (homeworks.isEmpty()) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text("No homework assigned.", color = Color.Gray) }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(homeworks) { hw ->
                val past = hw.dueDateMs != null && hw.dueDateMs < System.currentTimeMillis()
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF3B82F6).copy(alpha = 0.06f))) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).background(Color(0xFF3B82F6).copy(alpha = 0.12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Home, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(20.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) { Text(hw.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis); hw.subject?.let { Text(it, color = Color(0xFF3B82F6), fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
                        }
                        hw.className?.let { Row { Spacer(Modifier.width(52.dp)); Text(it, color = Color.Gray, fontSize = 11.sp) }; Spacer(Modifier.height(6.dp)) }
                        if (hw.instructions.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(hw.instructions, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp), color = Color.White.copy(alpha = 0.8f), maxLines = 6, overflow = TextOverflow.Ellipsis) }
                        hw.bookPage?.let { Spacer(Modifier.height(4.dp)); Text("📖 $it", color = Color.Gray, fontSize = 12.sp) }
                        hw.dueDateMs?.let { due ->
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth().background(if (past) Color(0xFFEF4444).copy(alpha = 0.1f) else Color(0xFF34D399).copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Schedule, null, Modifier.size(14.dp), tint = if (past) Color(0xFFEF4444) else Color(0xFF34D399))
                                Spacer(Modifier.width(6.dp))
                                Text(if (past) "Due: ${df.format(Date(due))} (overdue)" else "Due: ${df.format(Date(due))}", color = if (past) Color(0xFFEF4444) else Color(0xFF34D399), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (hw.requiresSubmission) { Spacer(Modifier.height(8.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Text("✉ Submission Required", color = Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssignmentView(onBack: () -> Unit) {
    val iid = StudentSessionManager.instituteId.value ?: ""
    var assignments by remember { mutableStateOf<List<AssignmentEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val df = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    LaunchedEffect(iid) {
        try {
            val snap = FirebaseFirestore.getInstance().collection("institutes").document(iid).collection("assignments").whereEqualTo("status", "published").get().await()
            assignments = snap.documents.mapNotNull { doc ->
                AssignmentEntity(id = doc.id, instituteId = iid, batchId = null, title = doc.getString("title") ?: return@mapNotNull null, subject = doc.getString("subject"), className = doc.getString("className"), assignmentType = doc.getString("assignmentType") ?: "individual", instructions = doc.getString("instructions") ?: "", learningObjective = doc.getString("learningObjective"), totalMarks = (doc.get("totalMarks") as? Number)?.toDouble(), passingMarks = null, gradingMethod = doc.getString("gradingMethod") ?: "marks", rubricJson = null, startDateMs = (doc.get("startDateMs") as? Number)?.toLong() ?: 0L, dueDateMs = (doc.get("dueDateMs") as? Number)?.toLong(), allowLateSubmission = doc.getBoolean("allowLateSubmission") ?: false, latePenalty = null, submissionFormat = doc.getString("submissionFormat") ?: "any", maxFileSizeKb = null, referenceMaterials = null, status = "published", publishDateMs = null, createdAtMs = 0L, updatedAtMs = 0L, archivedAtMs = null)
            }.sortedByDescending { it.startDateMs }
        } catch (_: Exception) {}
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Assignments", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold))
        Spacer(Modifier.height(16.dp))
        if (loading) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (assignments.isEmpty()) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Text("No assignments yet.", color = Color.Gray) }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(assignments) { a ->
                val past = a.dueDateMs != null && a.dueDateMs < System.currentTimeMillis()
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF59E0B).copy(alpha = 0.06f))) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).background(Color(0xFFF59E0B).copy(alpha = 0.12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Assignment, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) { Text(a.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis); a.subject?.let { Text(it, color = Color(0xFFF59E0B), fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
                        }
                        if (a.instructions.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(a.instructions, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp), maxLines = 5, overflow = TextOverflow.Ellipsis) }
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            a.totalMarks?.let { Text("${it.toInt()} Marks", color = Color(0xFFF59E0B), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            a.className?.let { Text(it, color = Color.Gray, fontSize = 11.sp) }
                        }
                        a.dueDateMs?.let { due ->
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth().background(if (past) Color(0xFFEF4444).copy(alpha = 0.1f) else Color(0xFF34D399).copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Schedule, null, Modifier.size(14.dp), tint = if (past) Color(0xFFEF4444) else Color(0xFF34D399))
                                Spacer(Modifier.width(6.dp))
                                Text(if (past) "Due: ${df.format(Date(due))} (overdue)" else "Due: ${df.format(Date(due))}", color = if (past) Color(0xFFEF4444) else Color(0xFF34D399), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
