package com.batchfee.student.ui.exams

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.student.data.firebase.StudentFirestoreRepository
import com.batchfee.student.data.models.*
import com.batchfee.student.domain.SessionManager
import androidx.compose.ui.graphics.Color
import com.batchfee.student.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamDetailScreen(examId: String, onBack: () -> Unit, onMeritList: (String) -> Unit) {
    val studentId by SessionManager.currentStudentId.collectAsState()
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val repo = remember { StudentFirestoreRepository() }

    var exam by remember { mutableStateOf<Exam?>(null) }
    var myResult by remember { mutableStateOf<Result?>(null) }
    var allResults by remember { mutableStateOf<List<Result>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(examId, studentId, instituteId) {
        val sid = studentId ?: return@LaunchedEffect
        val iid = instituteId ?: return@LaunchedEffect
        try {
            val batches = repo.getStudentBatches(iid, sid)
            for (batch in batches) {
                val exams = repo.getExams(iid, batch.id)
                exam = exams.find { it.id == examId }
                if (exam != null) break
            }
            val results = repo.getResultsForExam(iid, examId)
            allResults = results
            myResult = results.find { it.studentId == sid }
        } catch (_: Exception) { }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exam Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (exam == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Exam not found", color = TextSecondaryLight)
            }
        } else {
            val e = exam!!
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                // Exam info card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = PrimaryBlue)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text(e.examName, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                        if (e.subject != null) {
                            Text(e.subject, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row {
                            InfoChip("Date", SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(e.examDateMs)))
                            Spacer(Modifier.width(8.dp))
                            InfoChip("Marks", "${String.format("%.0f", e.totalMarks)}")
                            Spacer(Modifier.width(8.dp))
                            InfoChip("Pass", "${String.format("%.0f", e.passingMarks)}")
                        }
                        if (e.teacherName != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Person, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Teacher: ${e.teacherName}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // My result
                if (myResult != null) {
                    val r = myResult!!
                    Text(
                        "My Result",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ResultItem("Marks", "${String.format("%.0f", r.marksObtained)}/${String.format("%.0f", e.totalMarks)}", StatusBlue)
                            ResultItem("Grade", r.grade ?: "N/A", StatusGreen)
                            ResultItem("Position", "#${r.position ?: "-"}", PrimaryBlue)
                        }
                    }
                    if (r.remarks != null) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = CardOrangeBg)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Comment, null, tint = StatusOrange, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(r.remarks, fontSize = 13.sp, color = StatusOrange)
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }

                // Merit list button
                if (e.status == "completed") {
                    Button(
                        onClick = { onMeritList(examId) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Filled.EmojiEvents, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("View Merit List", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.2f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun ResultItem(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = color)
        Text(label, color = TextSecondaryLight, fontSize = 12.sp)
    }
}
