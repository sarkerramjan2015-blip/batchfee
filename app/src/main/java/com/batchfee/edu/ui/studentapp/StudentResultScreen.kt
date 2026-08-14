package com.batchfee.edu.ui.studentapp

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.domain.StudentSessionManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.*

private val RsBg     = Color(0xFF07111F)
private val RsCard   = Color(0xFF0F172A)
private val RsStroke = Color(0xFF1E293B)
private val RsCyan   = Color(0xFF22D3EE)
private val RsGreen  = Color(0xFF22C55E)
private val RsViolet = Color(0xFF8B5CF6)
private val RsAmber  = Color(0xFFF59E0B)
private val RsWhite  = Color(0xFFF8FAFC)
private val RsMuted  = Color(0xFF94A3B8)
private val RsDim    = Color(0xFF64748B)

data class ResultCardInfo(
    val id: String,
    val examId: String?,
    val examName: String,
    val examDateMs: Long?,
    val subject: String?,
    val obtainedMarks: Double,
    val totalMarks: Double,
    val grade: String?,
    val rank: Int?,
    val totalStudents: Int?
)

private data class StudentExamInfo(
    val examName: String,
    val examDateMs: Long?,
    val subject: String?,
    val totalMarks: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentResultScreen(onBack: () -> Unit) {
    val sid by StudentSessionManager.studentId.collectAsState()
    val iid by StudentSessionManager.instituteId.collectAsState()
    val studentId = sid.orEmpty()
    val instituteId = iid.orEmpty()
    var resultSource by remember(studentId, instituteId) { mutableStateOf<List<ResultCardInfo>>(emptyList()) }
    var examsById by remember(instituteId) { mutableStateOf<Map<String, StudentExamInfo>>(emptyMap()) }
    var loading by remember(studentId, instituteId) { mutableStateOf(true) }
    var syncError by remember(studentId, instituteId) { mutableStateOf<String?>(null) }
    val df = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

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
        val listener = FirebaseFirestore.getInstance()
            .collection("institutes").document(instituteId)
            .collection("results").whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error != null) return@addSnapshotListener
                resultSource = snap?.documents
                    ?.filter { it.getBoolean("published") == true }
                    ?.map { doc ->
                    val obtained = (doc.get("marksObtained") as? Number)?.toDouble()
                        ?: (doc.get("obtainedMarks") as? Number)?.toDouble()
                        ?: 0.0
                    ResultCardInfo(
                        id = doc.id,
                        examId = doc.getString("examId"),
                        examName = doc.getString("examName") ?: "Exam",
                        examDateMs = (doc.get("examDateMs") as? Number)?.toLong(),
                        subject = doc.getString("subject"),
                        obtainedMarks = obtained,
                        totalMarks = (doc.get("totalMarks") as? Number)?.toDouble() ?: 0.0,
                        grade = doc.getString("grade"),
                        rank = (doc.get("position") as? Number)?.toInt()
                            ?: (doc.get("rank") as? Number)?.toInt(),
                        totalStudents = (doc.get("totalStudents") as? Number)?.toInt()
                    )
                }.orEmpty()
                loading = false
            }
        val examListener = FirebaseFirestore.getInstance()
            .collection("institutes").document(instituteId).collection("exams")
            .addSnapshotListener { snap, error ->
                reportListenerError(error)
                if (error == null) {
                    examsById = snap?.documents?.associate { doc ->
                        doc.id to StudentExamInfo(
                            examName = doc.getString("examName") ?: "Exam",
                            examDateMs = (doc.get("examDateMs") as? Number)?.toLong(),
                            subject = doc.getString("subject"),
                            totalMarks = (doc.get("totalMarks") as? Number)?.toDouble() ?: 0.0
                        )
                    }.orEmpty()
                }
            }
        onDispose { listener.remove(); examListener.remove() }
        }
    }

    // Result documents store the marks, while exam documents own the title,
    // date and total marks. Joining both live sources prevents "Exam / 0 of
    // 100" placeholder data after an institute publishes a real result.
    val results = resultSource.map { result ->
        val exam = result.examId?.let(examsById::get)
        result.copy(
            examName = exam?.examName ?: result.examName,
            examDateMs = exam?.examDateMs ?: result.examDateMs,
            subject = exam?.subject ?: result.subject,
            totalMarks = exam?.totalMarks?.takeIf { it > 0.0 } ?: result.totalMarks
        )
    }.sortedByDescending { it.examDateMs ?: 0L }

    Scaffold(
        containerColor = RsBg,
        topBar = { TopAppBar(title = { Text("Results", color = RsWhite, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = RsMuted) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = RsBg)) }
    ) { padding ->
        if (loading) Box(Modifier.fillMaxSize().padding(padding).background(RsBg), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RsCyan) }
        else if (results.isEmpty()) Box(Modifier.fillMaxSize().padding(padding).background(RsBg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                syncError?.let { Text(it, color = RsAmber, fontSize = 12.sp) }
                Text("No published results yet.", color = RsMuted)
            }
        }
        else LazyColumn(Modifier.fillMaxSize().padding(padding).background(RsBg), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            syncError?.let { message ->
                item {
                    Surface(color = RsAmber.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.SyncProblem, null, tint = RsAmber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(message, color = RsAmber, fontSize = 12.sp)
                        }
                    }
                }
            }
            items(results) { r ->
                val pct = if (r.totalMarks > 0) (r.obtainedMarks / r.totalMarks) * 100 else 0.0
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = RsCard), border = BorderStroke(1.dp, RsStroke)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(RsViolet.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.EmojiEvents, null, tint = RsAmber, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.examName, color = RsWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                r.subject?.let { Text(it, color = RsCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                                r.examDateMs?.let { Text(df.format(Date(it)), color = RsMuted, fontSize = 11.sp) }
                            }
                            r.grade?.let {
                                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(RsViolet.copy(alpha = 0.2f)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(it, color = RsViolet, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            RStat("Marks", if (r.totalMarks > 0) "${"%.0f".format(r.obtainedMarks)}/${"%.0f".format(r.totalMarks)}" else "${"%.0f".format(r.obtainedMarks)}")
                            RStat("Percentage", if (r.totalMarks > 0) "${"%.0f".format(pct)}%" else "–")
                            r.rank?.let { RStat("Rank", "#$it of ${r.totalStudents ?: "?"}") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun RStat(label: String, value: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, color = RsWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    Text(label, color = RsDim, fontSize = 10.sp)
}
