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

data class ResultCardInfo(val id: String, val examName: String, val examDateMs: Long?, val subject: String?, val obtainedMarks: Double, val totalMarks: Double, val grade: String?, val rank: Int?, val totalStudents: Int?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentResultScreen(onBack: () -> Unit) {
    val sid = StudentSessionManager.studentId.value ?: ""
    val iid = StudentSessionManager.instituteId.value ?: ""
    var results by remember { mutableStateOf<List<ResultCardInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val df = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    DisposableEffect(iid, sid) {
        val listener = FirebaseFirestore.getInstance()
            .collection("institutes").document(iid)
            .collection("results").whereEqualTo("studentId", sid)
            .addSnapshotListener { snap, _ ->
                results = snap?.documents?.map { doc ->
                    val obt = doc.getDouble("obtainedMarks") ?: 0.0
                    val tot = doc.getDouble("totalMarks") ?: 100.0
                    ResultCardInfo(
                        id = doc.id, examName = doc.getString("examName") ?: "Exam",
                        examDateMs = (doc.get("examDateMs") as? Number)?.toLong(), subject = doc.getString("subject"),
                        obtainedMarks = obt, totalMarks = tot, grade = doc.getString("grade"),
                        rank = (doc.get("rank") as? Number)?.toInt(), totalStudents = (doc.get("totalStudents") as? Number)?.toInt()
                    )
                }?.sortedByDescending { it.examDateMs ?: 0L } ?: emptyList()
                loading = false
            }
        onDispose { listener.remove() }
    }

    Scaffold(
        containerColor = RsBg,
        topBar = { TopAppBar(title = { Text("Results", color = RsWhite, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = RsMuted) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = RsBg)) }
    ) { padding ->
        if (loading) Box(Modifier.fillMaxSize().padding(padding).background(RsBg), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RsCyan) }
        else if (results.isEmpty()) Box(Modifier.fillMaxSize().padding(padding).background(RsBg), contentAlignment = Alignment.Center) { Text("No results yet.", color = RsMuted) }
        else LazyColumn(Modifier.fillMaxSize().padding(padding).background(RsBg), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            RStat("Marks", "${"%.0f".format(r.obtainedMarks)}/${"%.0f".format(r.totalMarks)}")
                            RStat("Percentage", "${"%.0f".format(pct)}%")
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
