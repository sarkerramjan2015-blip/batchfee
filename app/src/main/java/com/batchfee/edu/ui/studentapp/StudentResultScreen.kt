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
import androidx.compose.ui.text.style.TextOverflow
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

data class FinalSubjectMarks(
    val name: String,
    val obtained: Double,
    val fullMarks: Double,
    val passed: Boolean
)

data class FinalResultInfo(
    val id: String,
    val examId: String?,
    val examName: String,
    val examDateMs: Long?,
    val totalMarks: Double,
    val fullMarks: Double,
    val percentage: Double,
    val gpa: Double,
    val grade: String?,
    val passed: Boolean,
    val rank: Int?,
    val totalStudents: Int?,
    val subjects: List<FinalSubjectMarks>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentResultScreen(onBack: () -> Unit, onOpenDocuments: () -> Unit) {
    val sid by StudentSessionManager.studentId.collectAsState()
    val iid by StudentSessionManager.instituteId.collectAsState()
    val studentId = sid.orEmpty()
    val instituteId = iid.orEmpty()
    var resultSource by remember(studentId, instituteId) { mutableStateOf<List<ResultCardInfo>>(emptyList()) }
    var finalSource by remember(studentId, instituteId) { mutableStateOf<List<FinalResultInfo>>(emptyList()) }
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
        val finalListener = FirebaseFirestore.getInstance()
            .collection("institutes").document(instituteId)
            .collection("final_results").whereEqualTo("studentId", studentId)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    // Permission denied on this collection only hides final-exam cards;
                    // the regular results screen must keep working.
                    if (error.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) loading = false
                    return@addSnapshotListener
                }
                finalSource = snap?.documents
                    ?.filter { it.getBoolean("published") == true }
                    ?.mapNotNull { doc ->
                        val subjectList = (doc.get("subjectMarks") as? List<*>).orEmpty().mapNotNull { raw ->
                            val map = raw as? Map<*, *> ?: return@mapNotNull null
                            FinalSubjectMarks(
                                name = map["name"] as? String ?: "Subject",
                                obtained = (map["totalMarks"] as? Number)?.toDouble() ?: 0.0,
                                fullMarks = (map["fullMarks"] as? Number)?.toDouble() ?: 0.0,
                                passed = map["passed"] as? Boolean ?: false
                            )
                        }
                        FinalResultInfo(
                            id = doc.id,
                            examId = doc.getString("finalExamId"),
                            examName = doc.getString("examName") ?: "Final Exam",
                            examDateMs = (doc.get("publishedAtMs") as? Number)?.toLong(),
                            totalMarks = (doc.get("totalMarks") as? Number)?.toDouble() ?: 0.0,
                            fullMarks = (doc.get("fullMarks") as? Number)?.toDouble() ?: 0.0,
                            percentage = (doc.get("percentage") as? Number)?.toDouble() ?: 0.0,
                            gpa = (doc.get("gpa") as? Number)?.toDouble() ?: 0.0,
                            grade = doc.getString("grade"),
                            passed = doc.getBoolean("passed") == true,
                            rank = (doc.get("meritPosition") as? Number)?.toInt(),
                            totalStudents = (doc.get("totalStudents") as? Number)?.toInt(),
                            subjects = subjectList
                        )
                    }.orEmpty()
                loading = false
            }
        onDispose { listener.remove(); examListener.remove(); finalListener.remove() }
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

    val finalResults = finalSource.sortedByDescending { it.examDateMs ?: 0L }

    Scaffold(
        containerColor = RsBg,
        topBar = {
            TopAppBar(
                title = { Text("Results", color = RsWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = RsMuted) } },
                actions = { IconButton(onOpenDocuments) { Icon(Icons.Filled.PictureAsPdf, "Open result card", tint = RsCyan) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = RsBg)
            )
        }
    ) { padding ->
        if (loading) Box(Modifier.fillMaxSize().padding(padding).background(RsBg), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RsCyan) }
        else if (results.isEmpty() && finalResults.isEmpty()) Box(Modifier.fillMaxSize().padding(padding).background(RsBg), contentAlignment = Alignment.Center) {
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
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = onOpenDocuments,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, RsCyan.copy(alpha = 0.55f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RsCyan)
                        ) {
                            Icon(Icons.Filled.PictureAsPdf, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open Result Card", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (finalResults.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WorkspacePremium, null, tint = RsAmber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Final Exams", color = RsWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
                items(finalResults) { r ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = RsCard), border = BorderStroke(1.dp, RsStroke)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(RsCyan.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.School, null, tint = RsCyan, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.examName, color = RsWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    r.examDateMs?.let { Text(df.format(Date(it)), color = RsMuted, fontSize = 11.sp) }
                                }
                                r.grade?.let {
                                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(RsCyan.copy(alpha = 0.2f)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                                        Text(it, color = RsCyan, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                RStat("Marks", "${"%.0f".format(r.totalMarks)}/${"%.0f".format(r.fullMarks)}")
                                RStat("GPA", "%.2f".format(r.gpa))
                                RStat("Result", if (r.passed) "Pass" else "Fail")
                                r.rank?.let { RStat("Rank", "#$it of ${r.totalStudents ?: "?"}") }
                            }
                            Spacer(Modifier.height(14.dp))
                            r.subjects.forEach { subject ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(RsCard.copy(alpha = 0.4f)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(subject.name, color = RsWhite, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${"%.0f".format(subject.obtained)}/${"%.0f".format(subject.fullMarks)}",
                                        color = if (subject.passed) RsGreen else RsAmber,
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                            }
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
