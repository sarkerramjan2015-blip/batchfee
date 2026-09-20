package com.batchfee.edu.ui.superadmin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Report
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.repository.CurationQuestion
import com.batchfee.edu.data.repository.QuestionCurationRepository
import kotlinx.coroutines.launch

private val CurationBg = Color(0xFF07111F)
private val CurationCard = Color(0xFF101B30)
private val CurationBorder = Color(0xFF263851)
private val CurationText = Color(0xFFF8FAFC)
private val CurationMuted = Color(0xFF9AAAC2)
private val CurationCyan = Color(0xFF22D3EE)
private val CurationGreen = Color(0xFF34D399)
private val CurationRed = Color(0xFFFB7185)

/**
 * Super-admin-only moderation screen. Question data is anonymous by contract:
 * this screen never asks for, receives or renders institute/teacher identity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionCurationScreen(onBack: () -> Unit) {
    val repository = remember { QuestionCurationRepository() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var reloadKey by remember { mutableIntStateOf(0) }
    var questions by remember { mutableStateOf<List<CurationQuestion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var approvalTarget by remember { mutableStateOf<CurationQuestion?>(null) }
    var rejectionTarget by remember { mutableStateOf<CurationQuestion?>(null) }
    var rejectionReason by remember { mutableStateOf("") }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        runCatching { repository.pending() }
            .onSuccess { questions = it }
            .onFailure { error = it.message ?: "Could not load pending questions." }
        loading = false
    }

    fun approve(question: CurationQuestion) {
        approvalTarget = null
        busyId = question.id
        scope.launch {
            runCatching { repository.approve(question.id) }
                .onSuccess {
                    questions = questions.filterNot { it.id == question.id }
                    snackbar.showSnackbar("Question approved and added to the global bank.")
                }
                .onFailure { snackbar.showSnackbar(it.message ?: "Approval failed.") }
            busyId = null
        }
    }

    fun reject(question: CurationQuestion, reason: String) {
        rejectionTarget = null
        busyId = question.id
        scope.launch {
            runCatching { repository.reject(question.id, reason) }
                .onSuccess {
                    questions = questions.filterNot { it.id == question.id }
                    snackbar.showSnackbar("Question rejected. The reason was saved in the audit record.")
                }
                .onFailure { snackbar.showSnackbar(it.message ?: "Rejection failed.") }
            busyId = null
        }
    }

    approvalTarget?.let { question ->
        AlertDialog(
            onDismissRequest = { approvalTarget = null },
            icon = { Icon(Icons.Filled.CheckCircle, null, tint = CurationGreen) },
            title = { Text("Approve this question?") },
            text = { Text("It will be published to the anonymous global question bank and cannot be edited from this review item.") },
            confirmButton = { Button(onClick = { approve(question) }) { Text("Approve") } },
            dismissButton = { OutlinedButton(onClick = { approvalTarget = null }) { Text("Cancel") } },
        )
    }
    rejectionTarget?.let { question ->
        AlertDialog(
            onDismissRequest = { rejectionTarget = null },
            icon = { Icon(Icons.Filled.Report, null, tint = CurationRed) },
            title = { Text("Reject this question") },
            text = {
                OutlinedTextField(
                    value = rejectionReason,
                    onValueChange = { rejectionReason = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reason for audit") },
                    supportingText = { Text("This is not sent to an institute or teacher.") },
                    minLines = 2,
                )
            },
            confirmButton = {
                Button(
                    onClick = { reject(question, rejectionReason.trim()) },
                    enabled = rejectionReason.isNotBlank(),
                ) { Text("Reject") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    rejectionTarget = null
                    rejectionReason = ""
                }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        containerColor = CurationBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Question moderation", color = CurationText, fontWeight = FontWeight.Bold)
                        Text("Anonymous review queue", color = CurationMuted, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = CurationText)
                    }
                },
                actions = {
                    IconButton(onClick = { reloadKey += 1 }, enabled = !loading && busyId == null) {
                        Icon(Icons.Filled.Refresh, "Refresh", tint = CurationCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CurationBg),
            )
        },
    ) { padding ->
        when {
            loading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = CurationCyan)
                Spacer(Modifier.height(14.dp))
                Text("Loading anonymous review queue…", color = CurationMuted)
            }
            error != null -> Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Report, null, tint = CurationRed)
                Spacer(Modifier.height(10.dp))
                Text(error ?: "Could not load questions.", color = CurationText)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { reloadKey += 1 }) { Text("Try again") }
            }
            questions.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Quiz, null, tint = CurationCyan)
                Spacer(Modifier.height(12.dp))
                Text("No pending questions", color = CurationText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("New finalized questions will appear here after anonymous sync.", color = CurationMuted, fontSize = 13.sp)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("${questions.size} awaiting review", color = CurationMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(2.dp))
                }
                items(questions, key = { it.id }) { question ->
                    CurationQuestionCard(
                        question = question,
                        busy = busyId == question.id,
                        onApprove = { approvalTarget = question },
                        onReject = {
                            rejectionReason = ""
                            rejectionTarget = question
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CurationQuestionCard(
    question: CurationQuestion,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = CurationCard),
        border = BorderStroke(1.dp, CurationBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                listOf(question.className, question.subject, question.chapter).filter { it.isNotBlank() }.joinToString("  •  "),
                color = CurationCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (question.topic.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(question.topic, color = CurationMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(question.questionText, color = CurationText, fontWeight = FontWeight.SemiBold)
            if (question.options.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                question.options.forEachIndexed { index, option ->
                    Text(
                        "${('A' + index)}. $option",
                        color = if (option == question.correctAnswer) CurationGreen else CurationMuted,
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Answer: ${question.correctAnswer}", color = CurationGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (question.explanation.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Explanation: ${question.explanation}", color = CurationMuted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "${question.type.uppercase()}  •  ${question.difficulty}  •  ${question.marks} mark${if (question.marks == 1) "" else "s"}",
                color = CurationMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    border = BorderStroke(1.dp, CurationRed.copy(alpha = 0.75f)),
                ) {
                    Text("Reject", color = CurationRed)
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text("Approve")
                    }
                }
            }
        }
    }
}
