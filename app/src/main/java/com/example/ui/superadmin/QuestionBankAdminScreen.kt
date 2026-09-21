package com.batchfee.edu.ui.superadmin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.repository.AdminBankQuestion
import com.batchfee.edu.data.repository.QuestionBankAdminRepository
import com.batchfee.edu.data.repository.QuestionBankAdminSettings
import kotlinx.coroutines.launch

private val AdminBg = Color(0xFF07111F)
private val AdminCard = Color(0xFF101B30)
private val AdminBorder = Color(0xFF263851)
private val AdminText = Color(0xFFF8FAFC)
private val AdminMuted = Color(0xFF9AAAC2)
private val AdminCyan = Color(0xFF22D3EE)
private val AdminGreen = Color(0xFF34D399)
private val AdminRed = Color(0xFFFB7185)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionBankAdminScreen(
    onBack: () -> Unit,
    onOpenModeration: () -> Unit,
) {
    val repository = remember { QuestionBankAdminRepository() }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var changingId by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(QuestionBankAdminSettings()) }
    var questions by remember { mutableStateOf<List<AdminBankQuestion>>(emptyList()) }
    var status by remember { mutableStateOf("curated") }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var walletInstituteId by remember { mutableStateOf("") }
    var walletAmountBdt by remember { mutableStateOf("") }
    var walletReason by remember { mutableStateOf("") }
    var creditingWallet by remember { mutableStateOf(false) }

    fun refresh() {
        loading = true
        error = null
        scope.launch {
            runCatching { repository.settings() to repository.questions(status) }
                .onSuccess { (loadedSettings, loadedQuestions) ->
                    settings = loadedSettings
                    questions = loadedQuestions
                }
                .onFailure { error = it.message ?: "Could not load question bank controls." }
            loading = false
        }
    }
    LaunchedEffect(status) { refresh() }

    fun save() {
        saving = true
        error = null
        notice = null
        scope.launch {
            runCatching { repository.updateSettings(settings) }
                .onSuccess { settings = it; notice = "Platform AI controls saved." }
                .onFailure { error = it.message ?: "Could not save controls." }
            saving = false
        }
    }
    fun changeStatus(question: AdminBankQuestion) {
        changingId = question.id
        error = null
        scope.launch {
            val action = if (question.status == "curated") "retire_question" else "restore_question"
            runCatching { repository.changeQuestionStatus(question.id, action) }
                .onSuccess {
                    questions = questions.filterNot { it.id == question.id }
                    notice = if (action == "retire_question") "Question retired from the institute library." else "Question restored to the institute library."
                }
                .onFailure { error = it.message ?: "Could not update question status." }
            changingId = null
        }
    }
    fun creditWallet() {
        val amountPoisha = bdtTextToPoisha(walletAmountBdt)
        if (walletInstituteId.isBlank() || amountPoisha == null || amountPoisha <= 0) {
            error = "Enter a valid institute ID and a positive BDT amount."
            return
        }
        creditingWallet = true
        error = null
        notice = null
        scope.launch {
            runCatching {
                repository.creditInstituteWallet(walletInstituteId, amountPoisha, walletReason)
            }.onSuccess { result ->
                notice = "Question wallet credited by ${formatPoisha(result.amountPoisha)}. New balance: ${formatPoisha(result.balancePoisha)}."
                walletAmountBdt = ""
                walletReason = ""
            }.onFailure { error = it.message ?: "Could not credit the question wallet." }
            creditingWallet = false
        }
    }

    Scaffold(
        containerColor = AdminBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Question Bank Controls", color = AdminText, fontWeight = FontWeight.Bold)
                        Text("Super Admin only · server-authoritative", color = AdminMuted, fontSize = 12.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AdminText) } },
                actions = { IconButton(onClick = ::refresh, enabled = !loading && !saving) { Icon(Icons.Filled.Refresh, "Refresh", tint = AdminCyan) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AdminBg),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ControlCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AdminPanelSettings, null, tint = AdminCyan)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("AI generation access", color = AdminText, fontWeight = FontWeight.Bold)
                            Text("Stops new Gemini requests immediately; existing completed papers stay available.", color = AdminMuted, fontSize = 12.sp)
                        }
                        Switch(checked = settings.generationEnabled, onCheckedChange = { settings = settings.copy(generationEnabled = it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, null, tint = AdminGreen)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Anonymous contribution", color = AdminText, fontWeight = FontWeight.Bold)
                            Text("Controls new teacher contributions to the moderation queue.", color = AdminMuted, fontSize = 12.sp)
                        }
                        Switch(checked = settings.contributionEnabled, onCheckedChange = { settings = settings.copy(contributionEnabled = it) })
                    }
                }
            }
            item {
                ControlCard {
                    Text("Institute question wallet", color = AdminText, fontWeight = FontWeight.Bold)
                    Text(
                        "Credit a verified institute payment. Wallet changes are server-authoritative and permanently audited.",
                        color = AdminMuted,
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(
                        value = walletInstituteId,
                        onValueChange = { walletInstituteId = it.trim().take(128) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Institute ID") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = walletAmountBdt,
                        onValueChange = { input ->
                            walletAmountBdt = input.filter { it.isDigit() || it == '.' }.take(12)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Credit amount (BDT)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = walletReason,
                        onValueChange = { walletReason = it.take(240) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Payment reference or note") },
                        singleLine = true,
                    )
                    Button(
                        onClick = ::creditWallet,
                        enabled = !creditingWallet,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AdminGreen, contentColor = AdminBg),
                    ) {
                        if (creditingWallet) {
                            CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp, color = AdminBg)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (creditingWallet) "Crediting..." else "Credit question wallet")
                    }
                }
            }
            item {
                ControlCard {
                    Text("Daily safety limits", color = AdminText, fontWeight = FontWeight.Bold)
                    LimitField("Per teacher previews", settings.actorDailyPreviewLimit) { settings = settings.copy(actorDailyPreviewLimit = it) }
                    LimitField("Per institute previews", settings.instituteDailyPreviewLimit) { settings = settings.copy(instituteDailyPreviewLimit = it) }
                    LimitField("Platform previews", settings.platformDailyPreviewLimit) { settings = settings.copy(platformDailyPreviewLimit = it) }
                    LimitField("Maximum questions / request", settings.maxQuestionsPerRequest) { settings = settings.copy(maxQuestionsPerRequest = it) }
                    Button(
                        onClick = ::save,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AdminCyan, contentColor = AdminBg),
                    ) {
                        if (saving) { CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp, color = AdminBg); Spacer(Modifier.width(8.dp)) }
                        Text(if (saving) "Saving..." else "Save platform controls")
                    }
                }
            }
            item {
                ControlCard {
                    Text("Anonymous moderation", color = AdminText, fontWeight = FontWeight.Bold)
                    Text("Approve or reject contributed questions without viewing institute or teacher identity.", color = AdminMuted, fontSize = 12.sp)
                    OutlinedButton(onClick = onOpenModeration, modifier = Modifier.fillMaxWidth()) { Text("Open moderation queue", color = AdminCyan) }
                }
            }
            item {
                ControlCard {
                    Text("Approved library lifecycle", color = AdminText, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = status == "curated", onClick = { status = "curated" }, label = { Text("Active") })
                        FilterChip(selected = status == "retired", onClick = { status = "retired" }, label = { Text("Retired") })
                    }
                }
            }
            error?.let { item { Text(it, color = AdminRed, fontSize = 13.sp) } }
            notice?.let { item { Text(it, color = AdminGreen, fontSize = 13.sp) } }
            if (loading) {
                item { Row(Modifier.fillMaxWidth().padding(28.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = AdminCyan) } }
            } else if (questions.isEmpty()) {
                item { Text("No ${if (status == "curated") "active" else "retired"} questions in this page.", color = AdminMuted) }
            } else {
                items(questions, key = AdminBankQuestion::id) { question ->
                    AdminQuestionCard(question, changing = changingId == question.id, onChangeStatus = { changeStatus(question) })
                }
            }
        }
    }
}

private fun bdtTextToPoisha(value: String): Int? {
    val normalized = value.trim()
    if (!normalized.matches(Regex("\\d{1,7}(\\.\\d{0,2})?"))) return null
    val parts = normalized.split('.', limit = 2)
    val whole = parts[0].toLongOrNull() ?: return null
    val fraction = parts.getOrNull(1).orEmpty().padEnd(2, '0').take(2).toIntOrNull() ?: 0
    val result = whole * 100L + fraction
    return result.takeIf { it in 1..100_000_000L }?.toInt()
}

private fun formatPoisha(value: Int): String = "BDT ${value / 100}.${(value % 100).toString().padStart(2, '0')}"

@Composable
private fun ControlCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AdminCard), border = BorderStroke(1.dp, AdminBorder)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun LimitField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { it.toIntOrNull()?.let(onChange) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
    )
}

@Composable
private fun AdminQuestionCard(question: AdminBankQuestion, changing: Boolean, onChangeStatus: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AdminCard), border = BorderStroke(1.dp, AdminBorder)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(listOf(question.className, question.subject, question.chapter).filter(String::isNotBlank).joinToString(" · "), color = AdminCyan, fontSize = 12.sp)
            if (question.chapterName.isNotBlank()) Text("Chapter title: ${question.chapterName}", color = AdminMuted, fontSize = 12.sp)
            Text(question.questionText, color = AdminText, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("${question.type.uppercase()} · ${question.difficulty} · ${question.marks} mark(s)", color = AdminMuted, fontSize = 12.sp)
            OutlinedButton(
                onClick = onChangeStatus,
                enabled = !changing,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, if (question.status == "curated") AdminRed else AdminGreen),
            ) {
                if (changing) { CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp, color = AdminCyan); Spacer(Modifier.width(8.dp)) }
                Text(if (changing) "Updating..." else if (question.status == "curated") "Retire from library" else "Restore to library", color = if (question.status == "curated") AdminRed else AdminGreen)
            }
        }
    }
}
