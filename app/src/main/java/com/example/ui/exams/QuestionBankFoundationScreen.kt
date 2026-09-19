package com.batchfee.edu.ui.exams

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.repository.QuestionBankFoundation
import com.batchfee.edu.data.repository.QuestionBankFoundationRepository
import com.batchfee.edu.domain.SessionManager
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch

private val BankBg = Color(0xFF07111F)
private val BankCard = Color(0xFF0F172A)
private val BankBorder = Color(0xFF243148)
private val BankText = Color(0xFFF8FAFC)
private val BankMuted = Color(0xFF94A3B8)
private val BankCyan = Color(0xFF22D3EE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionBankFoundationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val repository = remember { QuestionBankFoundationRepository() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var foundation by remember { mutableStateOf<QuestionBankFoundation?>(null) }
    var loading by remember { mutableStateOf(true) }
    var accepting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var examName by rememberSaveable { mutableStateOf("") }
    var totalMarks by rememberSaveable { mutableStateOf("") }
    var durationMinutes by rememberSaveable { mutableStateOf("") }
    var className by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var chapter by rememberSaveable { mutableStateOf("") }
    var questionType by rememberSaveable { mutableStateOf("mcq") }
    var scannedPages by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var launchingScanner by remember { mutableStateOf(false) }

    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(2)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scanner = remember(scannerOptions) { GmsDocumentScanning.getClient(scannerOptions) }
    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        launchingScanner = false
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scannedPages = ArrayList(
                scan?.pages.orEmpty().take(2).map { page -> page.imageUri.toString() }
            )
        }
    }

    LaunchedEffect(instituteId, reloadKey) {
        loading = true
        error = null
        val id = instituteId
        if (id == null) {
            error = "Open this feature from an institute account."
        } else {
            runCatching { repository.load(id) }
                .onSuccess { foundation = it }
                .onFailure { error = it.message ?: "Could not load AI terms." }
        }
        loading = false
    }

    val canContinue = examName.isNotBlank() && totalMarks.toIntOrNull()?.let { it > 0 } == true &&
        durationMinutes.toIntOrNull()?.let { it > 0 } == true && className.isNotBlank() &&
        subject.isNotBlank() && chapter.isNotBlank() && scannedPages.isNotEmpty()

    Scaffold(
        containerColor = BankBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Question Generator", color = BankText, fontWeight = FontWeight.Bold)
                        Text("Exam setup", color = BankMuted, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BankText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BankBg),
            )
        },
    ) { padding ->
        when {
            loading -> Box(
                Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = BankCyan) }

            error != null && foundation == null -> Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = Color(0xFFFCA5A5))
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { reloadKey++ }) { Text("Retry") }
                }
            }

            else -> ExamSetupContent(
                modifier = Modifier.padding(padding),
                examName = examName,
                onExamNameChange = { examName = it.take(120) },
                totalMarks = totalMarks,
                onTotalMarksChange = { totalMarks = it.filter(Char::isDigit).take(4) },
                durationMinutes = durationMinutes,
                onDurationChange = { durationMinutes = it.filter(Char::isDigit).take(4) },
                className = className,
                onClassNameChange = { className = it.take(80) },
                subject = subject,
                onSubjectChange = { subject = it.take(120) },
                chapter = chapter,
                onChapterChange = { chapter = it.take(160) },
                questionType = questionType,
                onQuestionTypeChange = { questionType = it },
                scannedPages = scannedPages,
                launchingScanner = launchingScanner,
                onScan = {
                    val host = activity
                    if (host == null) {
                        error = "Document scanner could not open on this device."
                    } else {
                        launchingScanner = true
                        scanner.getStartScanIntent(host)
                            .addOnSuccessListener { sender ->
                                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
                            }
                            .addOnFailureListener { failure ->
                                launchingScanner = false
                                error = failure.message ?: "Document scanner is unavailable."
                            }
                    }
                },
                onClearScans = { scannedPages = arrayListOf() },
                error = error,
                canContinue = canContinue,
                onContinue = {
                    scope.launch {
                        snackbar.showSnackbar("Setup ready. Secure AI generation will be connected in Phase 2.")
                    }
                },
            )
        }
    }

    val policy = foundation
    if (policy != null && !policy.aiTncAccepted) {
        AiTermsDialog(
            terms = policy.terms,
            saving = accepting,
            error = error,
            onDecline = onBack,
            onAgree = {
                val id = instituteId
                if (id != null) {
                    accepting = true
                    error = null
                    scope.launch {
                        runCatching { repository.acceptAiTerms(id, policy.policyVersion) }
                            .onSuccess { foundation = it }
                            .onFailure { error = it.message ?: "Could not save your consent." }
                        accepting = false
                    }
                }
            },
        )
    }
}

@Composable
private fun AiTermsDialog(
    terms: List<String>,
    saving: Boolean,
    error: String?,
    onDecline: () -> Unit,
    onAgree: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = BankCard,
        icon = { Icon(Icons.Filled.PrivacyTip, null, tint = BankCyan, modifier = Modifier.size(34.dp)) },
        title = { Text("Before you continue", color = BankText, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "To improve our AI services and platform quality, questions generated via this feature may be used anonymously for BatchFee's global research and database.",
                    color = BankText,
                )
                Spacer(Modifier.height(12.dp))
                terms.drop(1).forEach { item ->
                    Text("• $item", color = BankMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(7.dp))
                }
                Text(
                    "By agreeing, you confirm that uploaded content is original or that you have permission to use it.",
                    color = BankCyan,
                    fontSize = 13.sp,
                )
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Color(0xFFFCA5A5), fontSize = 13.sp)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDecline, enabled = !saving) { Text("Not now") } },
        confirmButton = {
            Button(
                onClick = onAgree,
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(containerColor = BankCyan, contentColor = BankBg),
            ) { Text(if (saving) "Saving..." else "I Agree") }
        },
    )
}

@Composable
private fun ExamSetupContent(
    modifier: Modifier,
    examName: String,
    onExamNameChange: (String) -> Unit,
    totalMarks: String,
    onTotalMarksChange: (String) -> Unit,
    durationMinutes: String,
    onDurationChange: (String) -> Unit,
    className: String,
    onClassNameChange: (String) -> Unit,
    subject: String,
    onSubjectChange: (String) -> Unit,
    chapter: String,
    onChapterChange: (String) -> Unit,
    questionType: String,
    onQuestionTypeChange: (String) -> Unit,
    scannedPages: List<String>,
    launchingScanner: Boolean,
    onScan: () -> Unit,
    onClearScans: () -> Unit,
    error: String?,
    canContinue: Boolean,
    onContinue: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BankSection {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = BankCyan)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Create a board-ready question set", color = BankText, fontWeight = FontWeight.Bold)
                    Text("Step 1 of 5 · Setup and source", color = BankMuted, fontSize = 12.sp)
                }
            }
        }

        BankSection {
            SectionTitle("Exam details")
            BankTextField(examName, onExamNameChange, "Exam name", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BankTextField(
                    totalMarks, onTotalMarksChange, "Total marks", Modifier.weight(1f), KeyboardType.Number
                )
                BankTextField(
                    durationMinutes, onDurationChange, "Time (minutes)", Modifier.weight(1f), KeyboardType.Number
                )
            }
        }

        BankSection {
            SectionTitle("Academic information")
            BankTextField(className, onClassNameChange, "Class", Modifier.fillMaxWidth())
            BankTextField(subject, onSubjectChange, "Subject", Modifier.fillMaxWidth())
            BankTextField(chapter, onChapterChange, "Chapter", Modifier.fillMaxWidth())
            Text("Question type", color = BankMuted, fontSize = 13.sp)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("mcq" to "MCQ", "short" to "Short", "creative" to "Creative (CQ)")
                    .forEach { (value, label) ->
                        FilterChip(
                            selected = questionType == value,
                            onClick = { onQuestionTypeChange(value) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BankCyan.copy(alpha = 0.18f),
                                selectedLabelColor = BankCyan,
                                labelColor = BankMuted,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = questionType == value,
                                borderColor = BankBorder,
                                selectedBorderColor = BankCyan,
                            ),
                        )
                    }
            }
        }

        BankSection {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DocumentScanner, null, tint = BankCyan)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Source pages", color = BankText, fontWeight = FontWeight.Bold)
                    Text("Scan or import up to 2 pages", color = BankMuted, fontSize = 12.sp)
                }
            }
            Text(
                "ML Kit will automatically crop, rotate, enhance and clean the document on-device.",
                color = BankMuted,
                fontSize = 13.sp,
            )
            if (scannedPages.isNotEmpty()) {
                scannedPages.forEachIndexed { index, _ ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            null,
                            tint = Color(0xFF22C55E),
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Page ${index + 1} ready", color = BankText, modifier = Modifier.weight(1f))
                    }
                }
            }
            Button(
                onClick = onScan,
                enabled = !launchingScanner,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BankCyan, contentColor = BankBg),
            ) {
                Text(
                    if (launchingScanner) "Opening scanner..."
                    else if (scannedPages.isEmpty()) "Scan or import pages" else "Replace pages"
                )
            }
            if (scannedPages.isNotEmpty()) {
                TextButton(onClick = onClearScans, modifier = Modifier.align(Alignment.End)) {
                    Text("Remove pages")
                }
            }
        }

        error?.let { Text(it, color = Color(0xFFFCA5A5), fontSize = 13.sp) }
        Button(
            onClick = onContinue,
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BankCyan,
                contentColor = BankBg,
                disabledContainerColor = BankBorder,
                disabledContentColor = BankMuted,
            ),
        ) { Text("Continue", fontWeight = FontWeight.Bold) }
        Text(
            "Scanned pages remain on this device in Phase 1. Secure upload and AI processing will be connected in Phase 2.",
            color = BankMuted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun BankTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = BankText,
            unfocusedTextColor = BankText,
            focusedBorderColor = BankCyan,
            unfocusedBorderColor = BankBorder,
            focusedLabelColor = BankCyan,
            unfocusedLabelColor = BankMuted,
            cursorColor = BankCyan,
        ),
    )
}

@Composable
private fun SectionTitle(value: String) {
    Text(value, color = BankText, fontWeight = FontWeight.Bold)
}

@Composable
private fun BankSection(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = BankCard),
        border = BorderStroke(1.dp, BankBorder),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
