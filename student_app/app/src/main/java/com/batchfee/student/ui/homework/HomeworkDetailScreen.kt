package com.batchfee.student.ui.homework

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.student.data.firebase.StudentFirestoreRepository
import com.batchfee.student.data.models.Batch
import com.batchfee.student.data.models.Homework
import com.batchfee.student.domain.SessionManager
import com.batchfee.student.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkDetailScreen(homeworkId: String, onBack: () -> Unit) {
    val studentId by SessionManager.currentStudentId.collectAsState()
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val repo = remember { StudentFirestoreRepository() }

    var homework by remember { mutableStateOf<Homework?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(homeworkId, studentId, instituteId) {
        val sid = studentId ?: return@LaunchedEffect
        val iid = instituteId ?: return@LaunchedEffect
        try {
            val batches = repo.getStudentBatches(iid, sid)
            for (batch in batches) {
                val hwList = repo.getHomework(iid, batch.id)
                homework = hwList.find { it.id == homeworkId }
                if (homework != null) break
            }
        } catch (_: Exception) { }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Homework Details", fontWeight = FontWeight.Bold) },
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
        } else if (homework == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Homework not found", color = TextSecondaryLight)
            }
        } else {
            val hw = homework!!
            val isOverdue = hw.deadlineDateMs < System.currentTimeMillis()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(hw.title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(hw.subject, color = TextSecondaryLight, fontSize = 14.sp)

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Assigned", color = TextSecondaryLight, fontSize = 11.sp)
                                Text(
                                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                                        .format(Date(hw.assignedDateMs)),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Deadline", color = TextSecondaryLight, fontSize = 11.sp)
                                Text(
                                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                                        .format(Date(hw.deadlineDateMs)),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isOverdue) StatusRed else StatusGreen
                                )
                            }
                        }

                        if (isOverdue) {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = CardRedBg
                            ) {
                                Text(
                                    "⚠ Overdue",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    color = StatusRed,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Description
                Text(
                    "Description",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        hw.description.ifEmpty { "No description provided." },
                        modifier = Modifier.padding(16.dp),
                        color = if (hw.description.isEmpty()) TextSecondaryLight
                        else MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                }

                if (hw.attachmentUrl != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Attachment",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedButton(
                        onClick = { /* Open attachment URL */ },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.AttachFile, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("View Attachment", fontWeight = FontWeight.Medium)
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
