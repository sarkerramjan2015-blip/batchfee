package com.batchfee.student.ui.notice

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
import com.batchfee.student.data.models.Notice
import com.batchfee.student.domain.SessionManager
import androidx.compose.ui.graphics.Color
import com.batchfee.student.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeScreen(onBack: () -> Unit) {
    val studentId by SessionManager.currentStudentId.collectAsState()
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val repo = remember { StudentFirestoreRepository() }

    var notices by remember { mutableStateOf<List<Notice>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var expandedNoticeId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(studentId, instituteId) {
        val sid = studentId ?: return@LaunchedEffect
        val iid = instituteId ?: return@LaunchedEffect
        try {
            val batches = repo.getStudentBatches(iid, sid)
            val batchIds = batches.map { it.id }
            notices = repo.getNotices(iid, batchIds)
        } catch (_: Exception) { }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notices", fontWeight = FontWeight.Bold) },
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
        } else if (notices.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Campaign, null, modifier = Modifier.size(48.dp), tint = TextSecondaryLight)
                    Spacer(Modifier.height(8.dp))
                    Text("No notices yet", color = TextSecondaryLight)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(notices) { notice ->
                    val isExpanded = expandedNoticeId == notice.id
                    val isEmergency = notice.priority == "emergency"

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isEmergency) CardRedBg.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surface
                        ),
                        onClick = {
                            expandedNoticeId = if (isExpanded) null else notice.id
                        }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isEmergency) Icons.Filled.Error else Icons.Filled.Campaign,
                                    null,
                                    tint = if (isEmergency) StatusRed else PrimaryBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        notice.title,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                                            .format(Date(notice.createdAtMs)),
                                        color = TextSecondaryLight,
                                        fontSize = 11.sp
                                    )
                                }
                                if (isEmergency) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = StatusRed
                                    ) {
                                        Text(
                                            "URGENT",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                            }

                            if (isExpanded && notice.body.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    notice.body,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (!isExpanded && notice.body.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    notice.body,
                                    fontSize = 12.sp,
                                    color = TextSecondaryLight,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
