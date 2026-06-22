package com.batchfee.student.ui.homework

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
import androidx.compose.ui.text.style.TextOverflow
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
fun HomeworkScreen(
    onBack: () -> Unit,
    onHomeworkDetail: (String) -> Unit
) {
    val studentId by SessionManager.currentStudentId.collectAsState()
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val repo = remember { StudentFirestoreRepository() }

    var batches by remember { mutableStateOf<List<Batch>>(emptyList()) }
    var homeworkList by remember { mutableStateOf<List<Homework>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(studentId, instituteId) {
        val sid = studentId ?: return@LaunchedEffect
        val iid = instituteId ?: return@LaunchedEffect
        try {
            batches = repo.getStudentBatches(iid, sid)
            val allHw = mutableListOf<Homework>()
            for (batch in batches) {
                allHw.addAll(repo.getHomework(iid, batch.id))
            }
            homeworkList = allHw.sortedByDescending { it.deadlineDateMs }
        } catch (_: Exception) { }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Homework", fontWeight = FontWeight.Bold) },
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
        } else if (homeworkList.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.MenuBook, null, modifier = Modifier.size(48.dp), tint = TextSecondaryLight)
                    Spacer(Modifier.height(8.dp))
                    Text("No homework assigned yet", color = TextSecondaryLight)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(homeworkList) { hw ->
                    val isOverdue = hw.deadlineDateMs < System.currentTimeMillis()
                    HomeworkCard(
                        homework = hw,
                        isOverdue = isOverdue,
                        onClick = { onHomeworkDetail(hw.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeworkCard(
    homework: Homework,
    isOverdue: Boolean,
    onClick: () -> Unit
) {
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
                color = if (isOverdue) CardRedBg else CardBlueBg
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isOverdue) Icons.Filled.Warning else Icons.Filled.MenuBook,
                        null,
                        tint = if (isOverdue) StatusRed else StatusBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    homework.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    homework.subject,
                    color = TextSecondaryLight,
                    fontSize = 12.sp
                )
                Text(
                    "Deadline: ${
                        SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                            .format(Date(homework.deadlineDateMs))
                    }",
                    color = if (isOverdue) StatusRed else TextSecondaryLight,
                    fontSize = 11.sp
                )
            }
            Icon(
                Icons.Filled.ChevronRight, null,
                tint = TextSecondaryLight, modifier = Modifier.size(20.dp)
            )
        }
    }
}
