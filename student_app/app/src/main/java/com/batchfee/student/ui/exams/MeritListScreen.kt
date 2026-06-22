package com.batchfee.student.ui.exams

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeritListScreen(examId: String, onBack: () -> Unit) {
    val studentId by SessionManager.currentStudentId.collectAsState()
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val repo = remember { StudentFirestoreRepository() }

    var meritList by remember { mutableStateOf<List<MeritEntry>>(emptyList()) }
    var exam by remember { mutableStateOf<Exam?>(null) }
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
            // Get student names
            val entries = mutableListOf<MeritEntry>()
            results.forEachIndexed { index, result ->
                val name = if (result.studentId == sid) {
                    SessionManager.studentName.value ?: "You"
                } else {
                    try {
                        val student = repo.getStudent(iid, result.studentId)
                        student?.fullName ?: "Student"
                    } catch (_: Exception) { "Student" }
                }
                entries.add(
                    MeritEntry(
                        position = index + 1,
                        studentId = result.studentId,
                        studentName = name,
                        totalMarks = result.marksObtained,
                        grade = result.grade ?: "",
                        isSelf = result.studentId == sid
                    )
                )
            }
            meritList = entries
        } catch (_: Exception) { }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Merit List", fontWeight = FontWeight.Bold) },
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
        } else if (meritList.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No results published yet", color = TextSecondaryLight)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (exam != null) {
                    item {
                        Text(
                            exam!!.examName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                }

                // Top 3 podium
                val top3 = meritList.take(3)
                if (top3.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Second
                            if (top3.size > 1) {
                                PodiumCard(
                                    modifier = Modifier.weight(1f),
                                    entry = top3[1],
                                    medal = "\uD83E\uDD48",
                                    bgColor = CardBlueBg
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            // First
                            PodiumCard(
                                modifier = Modifier.weight(1.2f),
                                entry = top3[0],
                                medal = "\uD83E\uDD47",
                                bgColor = CardGreenBg
                            )
                            // Third
                            if (top3.size > 2) {
                                PodiumCard(
                                    modifier = Modifier.weight(1f),
                                    entry = top3[2],
                                    medal = "\uD83E\uDD49",
                                    bgColor = CardOrangeBg
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // Full list
                itemsIndexed(meritList) { index, entry ->
                    val isSelf = entry.isSelf
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelf) PrimaryBlue.copy(alpha = 0.1f)
                            else MaterialTheme.colorScheme.surface
                        ),
                        border = if (isSelf) CardDefaults.outlinedCardBorder().copy(
                            width = 1.dp
                        ) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "#${entry.position}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = when (entry.position) {
                                    1 -> Color(0xFFFFD700)
                                    2 -> Color(0xFFC0C0C0)
                                    3 -> Color(0xFFCD7F32)
                                    else -> TextSecondaryLight
                                },
                                modifier = Modifier.width(40.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.studentName,
                                    fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "Grade: ${entry.grade}",
                                    fontSize = 12.sp,
                                    color = TextSecondaryLight
                                )
                            }
                            Text(
                                "${String.format("%.0f", entry.totalMarks)}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = PrimaryBlue
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodiumCard(
    modifier: Modifier = Modifier,
    entry: MeritEntry,
    medal: String,
    bgColor: androidx.compose.ui.graphics.Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(medal, fontSize = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                entry.studentName.take(10),
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1
            )
            Text(
                "${String.format("%.0f", entry.totalMarks)} marks",
                fontSize = 11.sp,
                color = TextSecondaryLight
            )
        }
    }
}
