package com.batchfee.student.ui.exams

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.student.data.firebase.StudentFirestoreRepository
import com.batchfee.student.data.models.*
import com.batchfee.student.domain.SessionManager
import com.batchfee.student.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamsScreen(
    onBack: () -> Unit,
    onExamDetail: (String) -> Unit
) {
    val studentId by SessionManager.currentStudentId.collectAsState()
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val repo = remember { StudentFirestoreRepository() }

    var batches by remember { mutableStateOf<List<Batch>>(emptyList()) }
    var exams by remember { mutableStateOf<List<Exam>>(emptyList()) }
    var results by remember { mutableStateOf<List<Result>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(studentId, instituteId) {
        val sid = studentId ?: return@LaunchedEffect
        val iid = instituteId ?: return@LaunchedEffect
        try {
            batches = repo.getStudentBatches(iid, sid)
            val allExams = mutableListOf<Exam>()
            val allResults = repo.getResults(iid, sid)
            results = allResults

            for (batch in batches) {
                allExams.addAll(repo.getExams(iid, batch.id))
            }
            exams = allExams.sortedByDescending { it.examDateMs }
        } catch (_: Exception) { }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exams & Results", fontWeight = FontWeight.Bold) },
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
        } else if (exams.isEmpty() && results.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No exams or results found", color = TextSecondaryLight)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Results section
                if (results.isNotEmpty()) {
                    item {
                        Text(
                            "My Results",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(results) { result ->
                        val exam = exams.find { it.id == result.examId }
                        ResultCard(
                            result = result,
                            exam = exam,
                            onClick = { onExamDetail(result.examId) }
                        )
                    }
                }

                // Exams section
                if (exams.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "All Exams",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(exams) { exam ->
                        ExamCard(
                            exam = exam,
                            onClick = { onExamDetail(exam.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: Result, exam: Exam?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val ratio = if ((exam?.totalMarks ?: 100.0) > 0) result.marksObtained / (exam?.totalMarks ?: 100.0) else 0.0
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = when {
                    ratio >= 0.8 -> CardGreenBg
                    ratio >= 0.5 -> CardOrangeBg
                    else -> CardRedBg
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        result.grade ?: "N/A",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = when {
                            ratio >= 0.8 -> StatusGreen
                            ratio >= 0.5 -> StatusOrange
                            else -> StatusRed
                        }
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    exam?.examName ?: "Exam",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    "${exam?.subject ?: "N/A"} · ${String.format("%.0f", result.marksObtained)}/${String.format("%.0f", exam?.totalMarks ?: 100)}",
                    color = TextSecondaryLight,
                    fontSize = 12.sp
                )
            }
            Text(
                "#${result.position ?: "-"}",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = PrimaryBlue
            )
        }
    }
}

@Composable
private fun ExamCard(exam: Exam, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (exam.status == "completed") CardGreenBg else CardBlueBg
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (exam.status == "completed") Icons.Filled.CheckCircle else Icons.Filled.Schedule,
                        null,
                        tint = if (exam.status == "completed") StatusGreen else StatusBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(exam.examName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    exam.subject ?: "",
                    color = TextSecondaryLight,
                    fontSize = 12.sp
                )
                Text(
                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(exam.examDateMs)),
                    color = TextSecondaryLight,
                    fontSize = 12.sp
                )
            }
            Text(
                exam.status.replaceFirstChar { it.uppercase() },
                color = if (exam.status == "completed") StatusGreen else StatusBlue,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            )
        }
    }
}
