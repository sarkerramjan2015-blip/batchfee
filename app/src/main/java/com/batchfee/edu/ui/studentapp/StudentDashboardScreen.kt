package com.batchfee.edu.ui.studentapp

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import com.batchfee.edu.domain.StudentSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration

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
    val sid by StudentSessionManager.studentId.collectAsState()
    val iid by StudentSessionManager.instituteId.collectAsState()
    val sessionStudentName by StudentSessionManager.studentName.collectAsState()
    val studentId = sid.orEmpty()
    val instituteId = iid.orEmpty()

    var displayName by remember(studentId) { mutableStateOf(sessionStudentName ?: "Student") }
    var photoUri by remember(studentId) { mutableStateOf<String?>(null) }
    var instituteName by remember(instituteId) { mutableStateOf("") }
    var className by remember(studentId) { mutableStateOf("") }
    var totalFee by remember(studentId) { mutableStateOf(0.0) }
    var totalPaid by remember(studentId) { mutableStateOf(0.0) }
    var totalDue by remember(studentId) { mutableStateOf(0.0) }
    var attendancePct by remember(studentId) { mutableStateOf(0.0) }
    var presentCount by remember(studentId) { mutableStateOf(0) }
    var totalAttCount by remember(studentId) { mutableStateOf(0) }
    var latestGrade by remember(studentId) { mutableStateOf("-") }
    var latestExam by remember(studentId) { mutableStateOf("") }
    var homeworkBatchIds by remember(instituteId) { mutableStateOf<List<String?>>(emptyList()) }
    var assignmentBatchIds by remember(instituteId) { mutableStateOf<List<String?>>(emptyList()) }
    var activeBatchIds by remember(studentId, instituteId) { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember(studentId, instituteId) { mutableStateOf(true) }
    var syncError by remember(studentId, instituteId) { mutableStateOf<String?>(null) }

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
        val fs = FirebaseFirestore.getInstance()
        val listeners = mutableListOf<ListenerRegistration>()

        // Student profile snapshot
        listeners += fs.collection("institutes").document(instituteId).collection("students").document(studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                snap?.let {
                    displayName = it.getString("fullName") ?: displayName
                    photoUri = it.getString("photoUri")
                    className = it.getString("className") ?: ""
                }
                if (error == null) loading = false
            }

        // Work items are batch-scoped. Watch enrollment separately so a batch
        // transfer refreshes the dashboard counts without reopening the app.
        listeners += fs.collection("institutes").document(instituteId).collection("batch_students")
            .whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error == null) {
                    activeBatchIds = snap?.documents
                        ?.filter { it.getString("status") == "active" }
                        ?.mapNotNull { it.getString("batchId") }
                        ?.toSet()
                        .orEmpty()
                }
            }

        // Institute name
        listeners += fs.collection("institutes").document(instituteId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                snap?.let {
                    instituteName = it.getString("name") ?: it.getString("instituteName") ?: ""
                }
                if (error == null) loading = false
            }

        // Fees snapshot
        listeners += fs.collection("institutes").document(instituteId).collection("fees")
            .whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error != null) return@addSnapshotListener
                var tf = 0.0; var tp = 0.0; var td = 0.0
                snap?.documents?.forEach { doc ->
                    val amount = doc.getDouble("totalAmount") ?: 0.0
                    val paid = doc.getDouble("paidAmount") ?: 0.0
                    // dueAmount is the ledger's final value after discounts,
                    // adjustments, cancellations and payments.
                    val due = doc.getDouble("dueAmount")
                        ?: if (doc.getString("status") == "cancelled") 0.0 else (amount - paid).coerceAtLeast(0.0)
                    tf += amount
                    tp += paid
                    td += due.coerceAtLeast(0.0)
                }
                totalFee = tf; totalPaid = tp; totalDue = td
                loading = false
            }

        // Attendance snapshot
        listeners += fs.collection("institutes").document(instituteId).collection("attendance")
            .whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error != null) return@addSnapshotListener
                val docs = snap?.documents ?: emptyList()
                val p = docs.count { it.getString("status") == "present" }
                presentCount = p
                totalAttCount = docs.size
                attendancePct = if (docs.isNotEmpty()) (p.toDouble() / docs.size) * 100 else 0.0
            }

        // Latest result
        listeners += fs.collection("institutes").document(instituteId).collection("results")
            .whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error != null) return@addSnapshotListener
                val docs = snap?.documents
                    ?.filter { it.getBoolean("published") == true }
                    ?.sortedByDescending { (it.get("updatedAtMs") as? Number)?.toLong() ?: 0L }
                    .orEmpty()
                if (docs.isNotEmpty()) {
                    latestGrade = docs[0].getString("grade") ?: "-"
                    latestExam = docs[0].getString("examName") ?: "Latest result"
                } else {
                    latestGrade = "-"
                    latestExam = ""
                }
            }

        // Homework count
        listeners += fs.collection("institutes").document(instituteId).collection("homework")
            .whereEqualTo("status", "active")
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error == null) homeworkBatchIds = snap?.documents
                    ?.map { it.getString("batchId") }
                    .orEmpty()
            }

        // Assignment count
        listeners += fs.collection("institutes").document(instituteId).collection("assignments")
            .whereEqualTo("status", "published")
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error == null) assignmentBatchIds = snap?.documents
                    ?.map { it.getString("batchId") }
                    .orEmpty()
            }

        onDispose { listeners.forEach { it.remove() } }
        }
    }

    val hwCount = homeworkBatchIds.count { it.isNullOrBlank() || it in activeBatchIds }
    val assignCount = assignmentBatchIds.count { it.isNullOrBlank() || it in activeBatchIds }

    val context = LocalContext.current
    val glowTransition = rememberInfiniteTransition(label = "studentDashboardGlow")
    val glow by glowTransition.animateFloat(
        initialValue = 0.03f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "feeSummaryGlow"
    )
    val shineOffset by glowTransition.animateFloat(
        initialValue = -320f,
        targetValue = 920f,
        animationSpec = infiniteRepeatable(tween(3600), RepeatMode.Restart),
        label = "feeSummaryShine"
    )
    Column(
        Modifier.fillMaxSize().background(StuBg).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(StuCyan, StuBlue))), contentAlignment = Alignment.Center) {
                if (photoUri.isNullOrBlank()) {
                    Text(displayName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                } else {
                    AsyncImage(
                        model = FirebaseStorageImageUploadHelper.displaySource(context, photoUri),
                        contentDescription = "$displayName photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Hello, ${displayName.split(" ").firstOrNull() ?: displayName}!", color = StuWhite, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                if (className.isNotBlank()) Text(className, color = StuCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (instituteName.isNotBlank()) Text(instituteName, color = StuMuted, fontSize = 12.sp)
            }
        }

        syncError?.let { message ->
            Spacer(Modifier.height(12.dp))
            Surface(
                color = StuAmber.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SyncProblem, null, tint = StuAmber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(message, color = StuAmber, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Fee summary card
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent), border = BorderStroke(1.dp, StuBlue.copy(alpha = 0.34f))) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(StuCard, StuBlue.copy(alpha = glow), StuCard), Offset.Zero, Offset(700f, 310f)))
            ) {
                // A gentle highlight makes the live financial card feel active
                // while keeping every value readable.
                Box(
                    Modifier.matchParentSize().background(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.075f), Color.Transparent),
                            start = Offset(shineOffset - 230f, 0f),
                            end = Offset(shineOffset + 120f, 260f)
                        )
                    )
                )
                Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Fee Summary", color = StuWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Live institute balance", color = StuMuted, fontSize = 10.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(99.dp)).background(if (totalDue > 0) StuRed else StuGreen))
                        Spacer(Modifier.width(6.dp))
                        Text(if (totalDue > 0) "৳ ${"%,.0f".format(totalDue)} due" else "All paid ✓", color = if (totalDue > 0) StuRed else StuGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DashStat("Total", "৳${"%,.0f".format(totalFee)}", StuCyan)
                    DashStat("Paid", "৳${"%,.0f".format(totalPaid)}", StuGreen)
                    DashStat("Due", "৳${"%,.0f".format(totalDue)}", if (totalDue > 0) StuRed else StuGreen)
                }
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
private fun DashStat(label: String, value: String, color: Color) = Column(
    modifier = Modifier.width(84.dp).padding(horizontal = 3.dp),
    horizontalAlignment = Alignment.CenterHorizontally
) {
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
