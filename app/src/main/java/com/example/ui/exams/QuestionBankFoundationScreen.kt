package com.batchfee.edu.ui.exams

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.batchfee.edu.data.repository.QuestionBankFoundation
import com.batchfee.edu.data.repository.QuestionBankFoundationRepository
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.launch

private val BankBg = Color(0xFF07111F)
private val BankCard = Color(0xFF0F172A)
private val BankBorder = Color(0xFF1E293B)
private val BankText = Color(0xFFF8FAFC)
private val BankMuted = Color(0xFF94A3B8)
private val BankCyan = Color(0xFF22D3EE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionBankFoundationScreen(onBack: () -> Unit) {
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val repository = remember { QuestionBankFoundationRepository() }
    val scope = rememberCoroutineScope()
    var foundation by remember { mutableStateOf<QuestionBankFoundation?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var confirmedRights by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(instituteId) {
        foundation = null
        error = null
        loading = true
        val id = instituteId
        if (id == null) {
            error = "Open this page from an institute account."
        } else {
            try {
                foundation = repository.load(id)
            } catch (failure: Exception) {
                error = failure.message ?: "Could not load question bank settings."
            }
        }
        loading = false
    }

    Scaffold(
        containerColor = BankBg,
        topBar = {
            TopAppBar(
                title = { Text("Question Bank", color = BankText, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BankText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BankBg),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BankSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LibraryBooks, null, tint = BankCyan)
                    Spacer(Modifier.width(10.dp))
                    Text("Private by default", color = BankText, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Questions you create will stay inside your institute. A central bank submission will always require selecting individual questions and a review.",
                    color = BankMuted,
                )
            }

            if (loading) {
                CircularProgressIndicator(color = BankCyan)
            } else {
                error?.let { Text(it, color = Color(0xFFFCA5A5)) }
                foundation?.let { policy ->
                    BankSection {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.VerifiedUser, null, tint = BankCyan)
                            Spacer(Modifier.width(10.dp))
                            Text("Central contribution preference", color = BankText, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (policy.contributionEnabled) "On · You can choose questions to submit later"
                            else "Off · No questions can be submitted",
                            color = BankCyan,
                        )
                        Spacer(Modifier.height(12.dp))
                        policy.terms.forEach { term ->
                            Text("• $term", color = BankMuted)
                            Spacer(Modifier.height(6.dp))
                        }
                        if (!policy.contributionEnabled) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = confirmedRights,
                                    onCheckedChange = { confirmedRights = it },
                                    colors = CheckboxDefaults.colors(checkedColor = BankCyan),
                                )
                                Text(
                                    "I will share only original questions or content I have permission to share.",
                                    color = BankText,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Button(
                            onClick = {
                                val id = instituteId ?: return@Button
                                saving = true
                                error = null
                                scope.launch {
                                    try {
                                        foundation = repository.setContributionPreference(
                                            id, policy.policyVersion, !policy.contributionEnabled,
                                            confirmedRights,
                                        )
                                        confirmedRights = false
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Could not save your preference."
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            enabled = !saving && (policy.contributionEnabled || confirmedRights),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = BankCyan, contentColor = BankBg),
                        ) {
                            Text(if (saving) "Saving..." else if (policy.contributionEnabled) "Turn off contribution" else "Enable contribution choice")
                        }
                    }

                    BankSection {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AutoAwesome, null, tint = BankCyan)
                            Spacer(Modifier.width(10.dp))
                            Text("Question structure", color = BankText, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "MCQ · Short · Creative (CQ)\nClass, subject, chapter, topic, syllabus year, answer and review status will be recorded for every question.",
                            color = BankMuted,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (policy.aiBillingEnabled) "AI billing is available."
                            else "AI generation and wallet charging are not active yet. No credits are being deducted.",
                            color = BankCyan,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun BankSection(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BankCard),
        border = BorderStroke(1.dp, BankBorder),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
