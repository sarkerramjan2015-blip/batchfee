package com.batchfee.edu.ui.studentapp

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.domain.StudentSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await

private val StuBg     = Color(0xFF07111F)
private val StuCard   = Color(0xFF0F172A)
private val StuStroke = Color(0xFF1E293B)
private val StuCyan   = Color(0xFF22D3EE)
private val StuBlue   = Color(0xFF3B82F6)
private val StuViolet = Color(0xFF8B5CF6)
private val StuGreen  = Color(0xFF22C55E)
private val StuRed    = Color(0xFFEF4444)
private val StuAmber  = Color(0xFFF59E0B)
private val StuWhite  = Color(0xFFF8FAFC)
private val StuMuted  = Color(0xFF94A3B8)
private val StuDim    = Color(0xFF64748B)

@Composable
fun StudentDashboardScreen() {
    val sid = StudentSessionManager.studentId.value ?: ""
    val iid = StudentSessionManager.instituteId.value ?: ""
    val studentName = StudentSessionManager.studentName.value ?: "Student"
    val instCode = StudentSessionManager.instituteCode.value ?: ""

    var instituteName by remember { mutableStateOf("") }
    var className by remember { mutableStateOf("") }
    var totalFee by remember { mutableStateOf(0.0) }
    var totalPaid by remember { mutableStateOf(0.0) }
    var totalDue by remember { mutableStateOf(0.0) }
    var attendancePct by remember { mutableStateOf(0.0) }
    var presentCount by remember { mutableStateOf(0) }
    var totalAttCount by remember { mutableStateOf(0) }
    var latestGrade by remember { mutableStateOf("-") }
    var latestExam by remember { mutableStateOf("") }
    var hwCount by remember { mutableStateOf(0) }
    var assignCount by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }

    DisposableEffect(iid, sid) {
        val fs = FirebaseFirestore.getInstance()
        val listeners = mutableListOf<ListenerRegistration>()

        // Student profile snapshot
        listeners += fs.collection("institutes").document(iid).collection("students").document(sid)
            .addSnapshotListener { snap, _ ->
                snap?.let {
                    className = it.getString("className") ?: ""
                }
            }

        // Institute name
        listeners += fs.collection("institutes").document(iid)
            .addSnapshotListener { snap, _ ->
                snap?.let {
                    instituteName = it.getString("name") ?: it.getString("instituteName") ?: ""
                }
            }

        // Fees snapshot
        listeners += fs.collection("institutes").document(iid).collection("fees")
            .whereEqualTo("studentId", sid)
            .addSnapshotListener { snap, _ ->
                var tf = 0.0; var tp = 0.0
                snap?.documents?.forEach { doc ->
                    tf += doc.getDouble("totalAmount") ?: 0.0
                    tp += doc.getDouble("paidAmount") ?: 0.0
                }
                totalFee = tf; totalPaid = tp; totalDue = tf - tp
                loading = false
            }

        // Attendance snapshot
        listeners += fs.collection("institutes").document(iid).collection("attendance")
            .whereEqualTo("studentId", sid)
            .addSnapshotListener { snap, _ ->
                val docs = snap?.documents ?: emptyList()
                val p = docs.count { it.getString("status") == "present" }
                presentCount = p
                totalAttCount = docs.size
                attendancePct = if (docs.isNotEmpty()) (p.toDouble() / docs.size) * 100 else 0.0
            }

        // Latest result
        listeners += fs.collection("institutes").document(iid).collection("results")
            .whereEqualTo("studentId", sid)
            .addSnapshotListener { snap, _ ->
                val docs = snap?.documents?.sortedByDescending { (it.get("examDateMs") as? Number)?.toLong() ?: 0L } ?: emptyList()
                if (docs.isNotEmpty()) {
                    latestGrade = docs[0].getString("grade") ?: "-"
                    latestExam = docs[0].getString("examName") ?: ""
                }
            }

        // Homework count
        listeners += fs.collection("institutes").document(iid).collection("homework")
            .whereEqualTo("status", "active")
            .addSnapshotListener { snap, _ -> hwCount = snap?.size() ?: 0 }

        // Assignment count
        listeners += fs.collection("institutes").document(iid).collection("assignments")
            .whereEqualTo("status", "published")
            .addSnapshotListener { snap, _ -> assignCount = snap?.size() ?: 0 }

        onDispose { listeners.forEach { it.remove() } }
    }

    Column(
        Modifier.fillMaxSize().background(StuBg).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(StuCyan, StuBlue))), contentAlignment = Alignment.Center) {
                Text(studentName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Hello, ${studentName.split(" ").firstOrNull() ?: studentName}!", color = StuWhite, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                if (className.isNotBlank()) Text(className, color = StuCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (instituteName.isNotBlank()) Text(instituteName, color = StuMuted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(24.dp))

        // Fee summary card
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = StuCard), border = BorderStroke(1.dp, StuStroke)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Fee Summary", color = StuWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(if (totalDue > 0) "৳ ${"%,.0f".format(totalDue)} due" else "All paid ✓", color = if (totalDue > 0) StuRed else StuGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DashStat("Total", "৳${"%,.0f".format(totalFee)}", StuCyan)
                    DashStat("Paid", "৳${"%,.0f".format(totalPaid)}", StuGreen)
                    DashStat("Due", "৳${"%,.0f".format(totalDue)}", if (totalDue > 0) StuRed else StuGreen)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Stats grid
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DashCard("Homework", "$hwCount", Icons.Filled.Home, StuBlue, Modifier.weight(1f))
            DashCard("Assignments", "$assignCount", Icons.Filled.Assignment, StuAmber, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))

        // Attendance + Results row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = StuCard), border = BorderStroke(1.dp, StuStroke)) {
                Column(Modifier.padding(14.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = StuViolet, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("${"%.0f".format(attendancePct)}%", color = StuWhite, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    Text("Attendance", color = StuMuted, fontSize = 11.sp)
                    Text("$presentCount/$totalAttCount present", color = StuDim, fontSize = 10.sp)
                }
            }
            Card(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = StuCard), border = BorderStroke(1.dp, StuStroke)) {
                Column(Modifier.padding(14.dp)) {
                    Icon(Icons.Filled.Grade, null, tint = StuAmber, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(latestGrade, color = StuWhite, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    Text("Latest Grade", color = StuMuted, fontSize = 11.sp)
                    if (latestExam.isNotBlank()) Text(latestExam, color = StuDim, fontSize = 10.sp, maxLines = 1)
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = StuCyan, strokeWidth = 2.dp)
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun DashStat(label: String, value: String, color: Color) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
    Text(label, color = StuMuted, fontSize = 11.sp)
}

@Composable
private fun DashCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier) =
    Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = StuCard), border = BorderStroke(1.dp, StuStroke)) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            Text(value, color = StuWhite, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text(label, color = StuMuted, fontSize = 11.sp)
        }
    }
