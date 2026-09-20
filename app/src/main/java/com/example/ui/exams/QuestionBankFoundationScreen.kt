package com.batchfee.edu.ui.exams

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.batchfee.edu.data.repository.QuestionBankFoundation
import com.batchfee.edu.data.repository.QuestionBankFoundationRepository
import com.batchfee.edu.data.repository.QuestionGenerationPreview
import com.batchfee.edu.data.repository.QuestionGenerationRepository
import com.batchfee.edu.data.repository.QuestionGenerationSetup
import com.batchfee.edu.data.repository.QuestionFinalizationRepository
import com.batchfee.edu.data.repository.QuestionFinalizationResult
import com.batchfee.edu.data.repository.QuestionReviewPolicy
import com.batchfee.edu.data.repository.ReviewableQuestion
import com.batchfee.edu.data.repository.toReviewable
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.domain.SessionManager
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File
import coil.compose.AsyncImage

private val BankBg = Color(0xFF07111F)
private val BankCard = Color(0xFF0F172A)
private val BankBorder = Color(0xFF243148)
private val BankText = Color(0xFFF8FAFC)
private val BankMuted = Color(0xFF94A3B8)
private val BankCyan = Color(0xFF22D3EE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionBankFoundationScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    initialClassName: String? = null,
    initialSubject: String? = null,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val repository = remember { QuestionBankFoundationRepository() }
    val generationRepository = remember { QuestionGenerationRepository() }
    val finalizationRepository = remember { QuestionFinalizationRepository() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var foundation by remember { mutableStateOf<QuestionBankFoundation?>(null) }
    var loading by remember { mutableStateOf(true) }
    var accepting by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var finalizing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<QuestionGenerationPreview?>(null) }
    var reviewQuestions by remember { mutableStateOf<List<ReviewableQuestion>>(emptyList()) }
    var finalizationResult by remember { mutableStateOf<QuestionFinalizationResult?>(null) }
    var showPaperComposer by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var examName by rememberSaveable { mutableStateOf("") }
    var totalMarks by rememberSaveable { mutableStateOf("") }
    var durationMinutes by rememberSaveable { mutableStateOf("") }
    var questionCount by rememberSaveable { mutableStateOf("5") }
    var className by rememberSaveable(initialClassName) { mutableStateOf(initialClassName.orEmpty()) }
    var subject by rememberSaveable(initialSubject) { mutableStateOf(initialSubject.orEmpty()) }
    var chapter by rememberSaveable { mutableStateOf("") }
    var questionType by rememberSaveable { mutableStateOf("mcq") }
    var sourceMode by rememberSaveable { mutableStateOf("ai") }
    var manualQuestionText by rememberSaveable { mutableStateOf("") }
    var manualStimulus by rememberSaveable { mutableStateOf("") }
    var manualOptionA by rememberSaveable { mutableStateOf("") }
    var manualOptionB by rememberSaveable { mutableStateOf("") }
    var manualOptionC by rememberSaveable { mutableStateOf("") }
    var manualOptionD by rememberSaveable { mutableStateOf("") }
    var manualAnswer by rememberSaveable { mutableStateOf("") }
    var manualExplanation by rememberSaveable { mutableStateOf("") }
    var manualMarks by rememberSaveable { mutableStateOf("1") }
    var manualDifficulty by rememberSaveable { mutableStateOf("medium") }
    var manualImageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var uploadingManualImage by remember { mutableStateOf(false) }
    var manualAddedQuestions by remember { mutableStateOf<List<ReviewableQuestion>>(emptyList()) }
    var reviewSourceMode by remember { mutableStateOf<String?>(null) }
    var scannedPages by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var launchingScanner by remember { mutableStateOf(false) }
    var generationOperationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var finalizationOperationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }

    fun resetGeneration() {
        preview = null
        reviewQuestions = emptyList()
        finalizationResult = null
        error = null
        reviewSourceMode = null
        generationOperationId = UUID.randomUUID().toString()
        finalizationOperationId = UUID.randomUUID().toString()
    }

    fun manualDraftQuestion(imageReference: String? = null): ReviewableQuestion {
        val prompt = manualQuestionText.trim()
        val stimulus = manualStimulus.trim()
        val questionText = if (questionType == "creative" && stimulus.isNotBlank()) {
            "Uddipok:\n$stimulus\n\nQuestion:\n$prompt"
        } else {
            prompt
        }
        return ReviewableQuestion(
            sourceQuestionId = "manual_${UUID.randomUUID().toString().replace('-', '_')}",
            questionText = questionText,
            options = if (questionType == "mcq") {
                listOf(manualOptionA, manualOptionB, manualOptionC, manualOptionD)
            } else {
                emptyList()
            },
            correctAnswer = manualAnswer,
            explanation = manualExplanation,
            difficulty = manualDifficulty,
            marks = manualMarks.toIntOrNull() ?: 0,
            imageReference = imageReference,
        )
    }

    fun clearManualQuestionForm() {
        manualQuestionText = ""
        manualStimulus = ""
        manualOptionA = ""
        manualOptionB = ""
        manualOptionC = ""
        manualOptionD = ""
        manualAnswer = ""
        manualExplanation = ""
        manualMarks = "1"
        manualDifficulty = "medium"
        manualImageUri = null
    }

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
            resetGeneration()
        }
    }

    val manualImageScannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val manualImageScanner = remember(manualImageScannerOptions) {
        GmsDocumentScanning.getClient(manualImageScannerOptions)
    }
    val manualImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        launchingScanner = false
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            manualImageUri = scan?.pages?.firstOrNull()?.imageUri?.toString()
            error = null
        }
    }

    fun addManualQuestion() {
        val validation = QuestionReviewPolicy.validate(questionType, manualDraftQuestion())
        if (!validation.isValid) {
            error = validation.message
            return
        }
        val imageUri = manualImageUri
        uploadingManualImage = imageUri != null
        scope.launch {
            runCatching {
                imageUri?.let {
                    FirebaseStorageImageUploadHelper.uploadQuestionAttachment(context, Uri.parse(it))
                }
            }.onSuccess { imageReference ->
                manualAddedQuestions = manualAddedQuestions + manualDraftQuestion(imageReference)
                clearManualQuestionForm()
                error = null
            }.onFailure { throwable ->
                error = throwable.message ?: "Could not attach the image. Try again."
            }
            uploadingManualImage = false
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

    val coreSetupValid = foundation?.aiTncAccepted == true &&
        examName.isNotBlank() && totalMarks.toIntOrNull()?.let { it in 1..1000 } == true &&
        durationMinutes.toIntOrNull()?.let { it in 1..1440 } == true &&
        className.isNotBlank() && subject.isNotBlank() && chapter.isNotBlank()
    val manualDraftValidation = QuestionReviewPolicy.validate(questionType, manualDraftQuestion())
    val canContinue = !generating && coreSetupValid && if (sourceMode == "manual") {
        manualAddedQuestions.isNotEmpty() && !uploadingManualImage
    } else {
        questionCount.toIntOrNull()?.let { it in 1..30 } == true && scannedPages.isNotEmpty()
    }
    val reviewing = preview != null && reviewQuestions.isNotEmpty()
    val reviewingManualEntry = reviewSourceMode == "manual"
    val selectedQuestions = reviewQuestions.filter { it.selected }
    val selectedValidation = selectedQuestions.map { QuestionReviewPolicy.validate(questionType, it) }
    val firstValidationError = selectedValidation.firstOrNull { !it.isValid }?.message
    val selectedMarks = selectedQuestions.sumOf { it.marks }
    val currentBillingMode = preview?.billingMode ?: if (reviewingManualEntry) "manual" else "wallet"
    val proposedCostPoisha = if (reviewingManualEntry || currentBillingMode == "lifetime_free") 0 else {
        QuestionReviewPolicy.totalPricePoisha(questionType, reviewQuestions)
    }

    Scaffold(
        containerColor = BankBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Create Questions", color = BankText, fontWeight = FontWeight.Bold)
                        Text(if (reviewing) "Review & edit" else "Exam setup", color = BankMuted, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (reviewing) resetGeneration() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = BankText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BankBg),
            )
        },
        bottomBar = {
            if (reviewing) {
                ReviewCostBar(
                    selectedCount = selectedQuestions.size,
                    selectedMarks = selectedMarks,
                    totalMarks = totalMarks.toIntOrNull() ?: 0,
                    costPoisha = proposedCostPoisha,
                    billingMode = currentBillingMode,
                    walletBalancePoisha = foundation?.walletBalancePoisha ?: 0,
                    manualEntry = reviewingManualEntry,
                    finalizing = finalizing,
                    validationError = firstValidationError,
                    onFinalize = {
                        val id = instituteId
                        val currentPreview = preview
                        when {
                            selectedQuestions.isEmpty() -> error = "Select at least one question to finalize."
                            firstValidationError != null -> error = firstValidationError
                            selectedMarks > (totalMarks.toIntOrNull() ?: 0) -> {
                                error = "Selected question marks exceed the exam total."
                            }
                            id == null || currentPreview == null -> error = "Question preview is no longer available. Generate it again."
                            else -> {
                                finalizing = true
                                error = null
                                scope.launch {
                                    runCatching {
                                        finalizationRepository.finalize(
                                            instituteId = id,
                                            generationOperationId = currentPreview.operationId,
                                            questionType = questionType,
                                            questions = reviewQuestions,
                                            operationId = finalizationOperationId,
                                            sourceType = if (reviewingManualEntry) "manual" else "ai_assisted",
                                            manualSetup = if (reviewingManualEntry) {
                                                QuestionGenerationSetup(
                                                    examName = examName.trim(),
                                                    totalMarks = totalMarks.toIntOrNull() ?: 0,
                                                    durationMinutes = durationMinutes.toIntOrNull() ?: 0,
                                                    className = className.trim(),
                                                    subject = subject.trim(),
                                                    chapter = chapter.trim(),
                                                    questionType = questionType,
                                                    questionCount = reviewQuestions.count { it.selected },
                                                )
                                            } else null,
                                        )
                                    }.onSuccess {
                                        finalizationResult = it
                                        foundation = foundation?.copy(walletBalancePoisha = it.remainingBalancePoisha)
                                    }
                                        .onFailure { error = it.message ?: "Could not finalize questions. Try again." }
                                    finalizing = false
                                }
                            }
                        }
                    },
                )
            }
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

            reviewing -> QuestionReviewContent(
                modifier = Modifier.padding(padding),
                questions = reviewQuestions,
                questionType = questionType,
                totalMarks = totalMarks.toIntOrNull() ?: 0,
                model = preview?.model.orEmpty(),
                manualEntry = reviewingManualEntry,
                error = error,
                onUpdate = { sourceId, transform ->
                    reviewQuestions = reviewQuestions.map { question ->
                        if (question.sourceQuestionId == sourceId) transform(question) else question
                    }
                    error = null
                },
                onSelectAll = { selected ->
                    reviewQuestions = reviewQuestions.map { it.copy(selected = selected) }
                    error = null
                },
                onEditSetup = { resetGeneration() },
            )

            else -> ExamSetupContent(
                modifier = Modifier.padding(padding),
                examName = examName,
                onExamNameChange = { examName = it.take(120); resetGeneration() },
                totalMarks = totalMarks,
                onTotalMarksChange = { totalMarks = it.filter(Char::isDigit).take(4); resetGeneration() },
                durationMinutes = durationMinutes,
                onDurationChange = { durationMinutes = it.filter(Char::isDigit).take(4); resetGeneration() },
                questionCount = questionCount,
                onQuestionCountChange = { questionCount = it.filter(Char::isDigit).take(2); resetGeneration() },
                className = className,
                onClassNameChange = {
                    className = it.take(80)
                    chapter = ""
                    resetGeneration()
                },
                subject = subject,
                onSubjectChange = {
                    subject = it.take(120)
                    chapter = ""
                    resetGeneration()
                },
                chapter = chapter,
                onChapterChange = { chapter = it.take(160); resetGeneration() },
                questionType = questionType,
                onQuestionTypeChange = {
                    questionType = it
                    manualAddedQuestions = emptyList()
                    clearManualQuestionForm()
                    resetGeneration()
                },
                sourceMode = sourceMode,
                onSourceModeChange = { sourceMode = it; resetGeneration() },
                manualQuestionText = manualQuestionText,
                onManualQuestionTextChange = { manualQuestionText = it.take(8_000); resetGeneration() },
                manualStimulus = manualStimulus,
                onManualStimulusChange = { manualStimulus = it.take(8_000); resetGeneration() },
                manualOptions = listOf(manualOptionA, manualOptionB, manualOptionC, manualOptionD),
                onManualOptionChange = { index, value ->
                    when (index) {
                        0 -> manualOptionA = value.take(1_000)
                        1 -> manualOptionB = value.take(1_000)
                        2 -> manualOptionC = value.take(1_000)
                        else -> manualOptionD = value.take(1_000)
                    }
                    resetGeneration()
                },
                manualAnswer = manualAnswer,
                onManualAnswerChange = { manualAnswer = it.take(2_000); resetGeneration() },
                manualExplanation = manualExplanation,
                onManualExplanationChange = { manualExplanation = it.take(4_000); resetGeneration() },
                manualMarks = manualMarks,
                onManualMarksChange = { manualMarks = it.filter(Char::isDigit).take(3); resetGeneration() },
                manualDifficulty = manualDifficulty,
                onManualDifficultyChange = { manualDifficulty = it; resetGeneration() },
                manualImageUri = manualImageUri,
                uploadingManualImage = uploadingManualImage,
                addedManualQuestions = manualAddedQuestions,
                onAddManualQuestion = ::addManualQuestion,
                onRemoveManualQuestion = { sourceId ->
                    manualAddedQuestions = manualAddedQuestions.filterNot { it.sourceQuestionId == sourceId }
                    resetGeneration()
                },
                onAddManualImage = {
                    if (activity == null) {
                        error = "Open this screen from the app to add an image."
                    } else {
                        launchingScanner = true
                        manualImageScanner.getStartScanIntent(activity)
                            .addOnSuccessListener { intentSender ->
                                manualImageLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                            }
                            .addOnFailureListener {
                                launchingScanner = false
                                error = "Could not open the image scanner."
                            }
                    }
                },
                onRemoveManualImage = { manualImageUri = null },
                scannedPages = scannedPages,
                launchingScanner = launchingScanner || generating,
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
                onClearScans = { scannedPages = arrayListOf(); resetGeneration() },
                error = error,
                canContinue = canContinue,
                generating = generating,
                preview = preview,
                foundation = foundation,
                onContinue = {
                    val id = instituteId
                    if (sourceMode == "manual" && canContinue) {
                        preview = QuestionGenerationPreview(
                            operationId = generationOperationId,
                            questions = emptyList(),
                            model = "Manual entry",
                        )
                        reviewQuestions = manualAddedQuestions
                        reviewSourceMode = "manual"
                        finalizationOperationId = UUID.randomUUID().toString()
                    } else if (id != null && canContinue) {
                        val requestedOperationId = generationOperationId
                        generating = true
                        error = null
                        scope.launch {
                            runCatching {
                                generationRepository.generate(
                                    context = context,
                                    instituteId = id,
                                    setup = QuestionGenerationSetup(
                                        examName.trim(), totalMarks.toInt(), durationMinutes.toInt(),
                                        className.trim(), subject.trim(), chapter.trim(),
                                        questionType, questionCount.toInt(),
                                    ),
                                    pageUris = scannedPages,
                                    operationId = requestedOperationId,
                                )
                            }.onSuccess { result ->
                                if (generationOperationId == requestedOperationId) {
                                    preview = result
                                    foundation = foundation?.copy(
                                        freeAttemptsUsed = result.attemptNumber.coerceAtMost(
                                            foundation?.freeLifetimeAttemptLimit ?: 5,
                                        ),
                                        freeAttemptsRemaining = result.freeAttemptsRemaining,
                                    )
                                    reviewQuestions = result.questions.mapIndexed { index, question ->
                                        question.toReviewable(index)
                                    }
                                    reviewSourceMode = "ai"
                                    finalizationOperationId = UUID.randomUUID().toString()
                                }
                            }
                                .onFailure {
                                    if (generationOperationId == requestedOperationId) {
                                        error = it.message ?: "Could not generate questions. Try again."
                                    }
                                }
                            generating = false
                        }
                    }
                },
                onNewAttempt = { resetGeneration(); error = null },
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

    finalizationResult?.let { result ->
        FinalizationSuccessDialog(
            result = result,
            onCreatePaper = { showPaperComposer = true },
            onCreateAnother = { resetGeneration() },
            onBackToExams = { resetGeneration(); onBack() },
        )
    }
    if (showPaperComposer) {
        QuestionPaperComposerDialog(
            db = db,
            instituteId = instituteId,
            examName = examName,
            className = className,
            subject = subject,
            chapter = chapter,
            totalMarks = totalMarks.toIntOrNull() ?: 0,
            durationMinutes = durationMinutes.toIntOrNull() ?: 0,
            questions = reviewQuestions.filter { it.selected },
            onDismiss = { showPaperComposer = false },
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
    questionCount: String,
    onQuestionCountChange: (String) -> Unit,
    className: String,
    onClassNameChange: (String) -> Unit,
    subject: String,
    onSubjectChange: (String) -> Unit,
    chapter: String,
    onChapterChange: (String) -> Unit,
    questionType: String,
    onQuestionTypeChange: (String) -> Unit,
    sourceMode: String,
    onSourceModeChange: (String) -> Unit,
    manualQuestionText: String,
    onManualQuestionTextChange: (String) -> Unit,
    manualStimulus: String,
    onManualStimulusChange: (String) -> Unit,
    manualOptions: List<String>,
    onManualOptionChange: (Int, String) -> Unit,
    manualAnswer: String,
    onManualAnswerChange: (String) -> Unit,
    manualExplanation: String,
    onManualExplanationChange: (String) -> Unit,
    manualMarks: String,
    onManualMarksChange: (String) -> Unit,
    manualDifficulty: String,
    onManualDifficultyChange: (String) -> Unit,
    manualImageUri: String?,
    uploadingManualImage: Boolean,
    addedManualQuestions: List<ReviewableQuestion>,
    onAddManualQuestion: () -> Unit,
    onRemoveManualQuestion: (String) -> Unit,
    onAddManualImage: () -> Unit,
    onRemoveManualImage: () -> Unit,
    scannedPages: List<String>,
    launchingScanner: Boolean,
    onScan: () -> Unit,
    onClearScans: () -> Unit,
    error: String?,
    canContinue: Boolean,
    generating: Boolean,
    preview: QuestionGenerationPreview?,
    foundation: QuestionBankFoundation?,
    onContinue: () -> Unit,
    onNewAttempt: () -> Unit,
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
            SectionTitle("AI question wallet")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Wallet balance", color = BankMuted, fontSize = 12.sp)
                    Text(
                        bdtFromPoisha(foundation?.walletBalancePoisha ?: 0),
                        color = BankCyan,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Lifetime free attempts", color = BankMuted, fontSize = 12.sp)
                    Text(
                        "${foundation?.freeAttemptsRemaining ?: 0} remaining",
                        color = if ((foundation?.freeAttemptsRemaining ?: 0) > 0) Color(0xFF34D399) else BankText,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                "After the first ${foundation?.freeLifetimeAttemptLimit ?: 5} AI attempts, only selected questions are charged: MCQ BDT 0.25, Short BDT 0.50, Creative BDT 0.75. Manual entry is free.",
                color = BankMuted,
                fontSize = 12.sp,
            )
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
            BankTextField(questionCount, onQuestionCountChange, "Questions (1–30)", Modifier.fillMaxWidth(), KeyboardType.Number)
        }

        BankSection {
            SectionTitle("Academic information")
            BankTextField(className, onClassNameChange, "Class", Modifier.fillMaxWidth())
            BankTextField(subject, onSubjectChange, "Subject", Modifier.fillMaxWidth())
            ChapterDropdown(
                className = className,
                subject = subject,
                value = chapter,
                onValueChange = onChapterChange,
            )
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
            SectionTitle("Question source")
            Text("Use an image for AI assistance, or write a question yourself.", color = BankMuted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ai" to "Image + AI", "manual" to "Manual entry").forEach { (value, label) ->
                    FilterChip(
                        selected = sourceMode == value,
                        onClick = { onSourceModeChange(value) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BankCyan.copy(alpha = 0.18f),
                            selectedLabelColor = BankCyan,
                            labelColor = BankMuted,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = sourceMode == value,
                            borderColor = BankBorder,
                            selectedBorderColor = BankCyan,
                        ),
                    )
                }
            }
        }

        if (sourceMode == "manual") {
            ManualQuestionEntrySection(
                questionType = questionType,
                questionText = manualQuestionText,
                onQuestionTextChange = onManualQuestionTextChange,
                stimulus = manualStimulus,
                onStimulusChange = onManualStimulusChange,
                options = manualOptions,
                onOptionChange = onManualOptionChange,
                answer = manualAnswer,
                onAnswerChange = onManualAnswerChange,
                explanation = manualExplanation,
                onExplanationChange = onManualExplanationChange,
                marks = manualMarks,
                onMarksChange = onManualMarksChange,
                difficulty = manualDifficulty,
                onDifficultyChange = onManualDifficultyChange,
                imageUri = manualImageUri,
                uploadingImage = uploadingManualImage,
                addedQuestionCount = addedManualQuestions.size,
                onAddQuestion = onAddManualQuestion,
                onAddImage = onAddManualImage,
                onRemoveImage = onRemoveManualImage,
            )
            if (addedManualQuestions.isNotEmpty()) {
                ManualQuestionQueue(
                    questions = addedManualQuestions,
                    onRemove = onRemoveManualQuestion,
                )
            }
        } else BankSection {
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
        if (error != null && !generating) {
            TextButton(onClick = onNewAttempt) { Text("Start a new attempt", color = BankCyan) }
        }
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
        ) {
            if (generating) {
                CircularProgressIndicator(Modifier.size(20.dp), color = BankBg, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (generating) "Generating questions..."
                else if (sourceMode == "manual") "Review ${addedManualQuestions.size} manual question${if (addedManualQuestions.size == 1) "" else "s"}"
                else "Generate questions",
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            if (sourceMode == "manual") {
                if (addedManualQuestions.isEmpty()) {
                    "Complete the fields, then tap + Add question. You can add up to 30 questions before review."
                } else {
                    "${addedManualQuestions.size} question${if (addedManualQuestions.size == 1) "" else "s"} ready. You can add more, remove any item, then review before saving."
                }
            } else {
                if ((foundation?.freeAttemptsRemaining ?: 0) > 0) {
                    "This AI attempt is covered by your lifetime free quota. Review every answer before finalizing."
                } else {
                    "Generate, review and select questions. The exact selected-question cost is debited only when you finalize."
                }
            },
            color = BankMuted,
            fontSize = 12.sp,
        )
        preview?.let { result ->
            BankSection {
                Text("Generated preview · ${result.questions.size} questions", color = BankText, fontWeight = FontWeight.Bold)
                Text("Review every question carefully. You can edit, select and finalize below.", color = BankMuted, fontSize = 12.sp)
            }
            result.questions.forEachIndexed { index, question ->
                BankSection {
                    Text("${index + 1}. ${question.questionText}", color = BankText, fontWeight = FontWeight.SemiBold)
                    question.options.forEach { option -> Text("• $option", color = BankMuted) }
                    Text("Answer: ${question.correctAnswer}", color = BankCyan)
                    if (question.explanation.isNotBlank()) Text(question.explanation, color = BankMuted, fontSize = 13.sp)
                    Text("${question.difficulty} · ${question.marks} mark(s)", color = BankMuted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChapterDropdown(
    className: String,
    subject: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var customChapter by remember(value) { mutableStateOf(value.isNotBlank() && value !in chapterOptionsFor(className, subject)) }
    val options = chapterOptionsFor(className, subject)
    if (customChapter) {
        BankTextField(
            value = value,
            onValueChange = onValueChange,
            label = "Chapter name",
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { customChapter = false; onValueChange("") }) {
            Text("Choose from chapter list", color = BankCyan)
        }
    } else {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                label = { Text("Chapter") },
                placeholder = { Text("Select chapter") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = BankText,
                    unfocusedTextColor = BankText,
                    focusedBorderColor = BankCyan,
                    unfocusedBorderColor = BankBorder,
                    focusedLabelColor = BankCyan,
                    unfocusedLabelColor = BankMuted,
                ),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .exposedDropdownSize()
                    .heightIn(max = 330.dp),
                shape = RoundedCornerShape(16.dp),
                containerColor = BankCard,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, BankBorder),
            ) {
                Text(
                    text = "AVAILABLE CHAPTERS  •  ${options.size}",
                    color = BankCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp),
                )
                options.forEach { chapter ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                chapter,
                                color = if (chapter == value) BankCyan else BankText,
                                fontWeight = if (chapter == value) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        leadingIcon = if (chapter == value) {
                            { Icon(Icons.Filled.CheckCircle, null, tint = BankCyan, modifier = Modifier.size(18.dp)) }
                        } else null,
                        modifier = if (chapter == value) {
                            Modifier
                                .padding(horizontal = 6.dp)
                                .background(BankCyan.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        } else Modifier.padding(horizontal = 6.dp),
                        onClick = { onValueChange(chapter); expanded = false },
                    )
                }
                HorizontalDivider(color = BankBorder, modifier = Modifier.padding(vertical = 6.dp))
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Add custom chapter", color = BankCyan, fontWeight = FontWeight.SemiBold)
                            Text("Use when your syllabus differs", color = BankMuted, fontSize = 11.sp)
                        }
                    },
                    leadingIcon = { Icon(Icons.Filled.MenuBook, null, tint = BankCyan) },
                    onClick = { customChapter = true; expanded = false; onValueChange("") },
                )
            }
        }
        Text("Chapter list follows the selected subject. Use custom only when your syllabus differs.", color = BankMuted, fontSize = 11.sp)
    }
}

@Composable
private fun ManualQuestionEntrySection(
    questionType: String,
    questionText: String,
    onQuestionTextChange: (String) -> Unit,
    stimulus: String,
    onStimulusChange: (String) -> Unit,
    options: List<String>,
    onOptionChange: (Int, String) -> Unit,
    answer: String,
    onAnswerChange: (String) -> Unit,
    explanation: String,
    onExplanationChange: (String) -> Unit,
    marks: String,
    onMarksChange: (String) -> Unit,
    difficulty: String,
    onDifficultyChange: (String) -> Unit,
    imageUri: String?,
    uploadingImage: Boolean,
    addedQuestionCount: Int,
    onAddQuestion: () -> Unit,
    onAddImage: () -> Unit,
    onRemoveImage: () -> Unit,
) {
    BankSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MenuBook, null, tint = BankCyan)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Write question manually", color = BankText, fontWeight = FontWeight.Bold)
                Text("Add an optional image, then type the question and answer below.", color = BankMuted, fontSize = 12.sp)
            }
        }
        if (imageUri == null) {
            OutlinedButton(
                onClick = onAddImage,
                enabled = !uploadingImage,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, BankCyan.copy(alpha = 0.72f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BankCyan),
            ) {
                Icon(Icons.Filled.AddPhotoAlternate, null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add optional question image")
            }
        } else {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = BankBg,
                border = BorderStroke(1.dp, BankBorder),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = "Attached question image",
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Question image attached", color = BankText, fontWeight = FontWeight.SemiBold)
                        Text("It will be securely saved with this question.", color = BankMuted, fontSize = 11.sp)
                    }
                    IconButton(onClick = onRemoveImage, enabled = !uploadingImage) {
                        Icon(Icons.Filled.DeleteOutline, "Remove image", tint = Color(0xFFFCA5A5))
                    }
                }
            }
        }
        if (questionType == "creative") {
            BankMultilineField(
                value = stimulus,
                onValueChange = onStimulusChange,
                label = "Uddipok / stimulus",
            )
        }
        BankMultilineField(
            value = questionText,
            onValueChange = onQuestionTextChange,
            label = when (questionType) {
                "mcq" -> "MCQ question"
                "creative" -> "Creative question / instruction"
                else -> "Short question"
            },
        )
        if (questionType == "mcq") {
            options.take(4).forEachIndexed { index, option ->
                BankTextField(
                    value = option,
                    onValueChange = { onOptionChange(index, it) },
                    label = "Option ${('A'.code + index).toChar()}",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        BankMultilineField(
            value = answer,
            onValueChange = onAnswerChange,
            label = if (questionType == "mcq") "Correct option (copy the exact option text)" else "Model answer",
        )
        BankMultilineField(
            value = explanation,
            onValueChange = onExplanationChange,
            label = "Explanation (optional)",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            BankTextField(
                value = marks,
                onValueChange = onMarksChange,
                label = "Marks",
                modifier = Modifier.weight(0.34f),
                keyboardType = KeyboardType.Number,
            )
            Column(Modifier.weight(0.66f)) {
                Text("Difficulty", color = BankMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("easy", "medium", "hard").forEach { item ->
                        FilterChip(
                            selected = difficulty == item,
                            onClick = { onDifficultyChange(item) },
                            label = { Text(item.replaceFirstChar(Char::uppercase), fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BankCyan.copy(alpha = 0.18f),
                                selectedLabelColor = BankCyan,
                                labelColor = BankMuted,
                            ),
                        )
                    }
                }
            }
        }
        Button(
            onClick = onAddQuestion,
            enabled = !uploadingImage && addedQuestionCount < 30,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BankCyan.copy(alpha = 0.18f),
                contentColor = BankCyan,
                disabledContainerColor = BankBorder,
                disabledContentColor = BankMuted,
            ),
            border = BorderStroke(1.dp, BankCyan.copy(alpha = 0.7f)),
        ) {
            if (uploadingImage) {
                CircularProgressIndicator(Modifier.size(18.dp), color = BankCyan, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Saving image...")
            } else {
                Icon(Icons.Filled.AddCircleOutline, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (addedQuestionCount == 0) "Add question" else "Add another question")
            }
        }
    }
}

@Composable
private fun ManualQuestionQueue(
    questions: List<ReviewableQuestion>,
    onRemove: (String) -> Unit,
) {
    BankSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Questions added", color = BankText, fontWeight = FontWeight.Bold)
                Text("${questions.size} ready for review", color = BankMuted, fontSize = 12.sp)
            }
            Surface(shape = RoundedCornerShape(99.dp), color = BankCyan.copy(alpha = 0.16f)) {
                Text("${questions.size}/30", color = BankCyan, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        questions.forEachIndexed { index, question ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BankBg,
                border = BorderStroke(1.dp, BankBorder),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${index + 1}", color = BankCyan, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(question.questionText.replace('\n', ' ').take(72), color = BankText, maxLines = 1)
                        Text(
                            listOfNotNull(
                                "${question.marks} mark${if (question.marks == 1) "" else "s"}",
                                if (question.imageReference != null) "image attached" else null,
                            ).joinToString(" • "),
                            color = BankMuted,
                            fontSize = 11.sp,
                        )
                    }
                    IconButton(onClick = { onRemove(question.sourceQuestionId) }) {
                        Icon(Icons.Filled.DeleteOutline, "Remove question", tint = Color(0xFFFCA5A5))
                    }
                }
            }
        }
    }
}

private fun chapterOptionsFor(className: String, subject: String): List<String> {
    val normalized = subject.trim().lowercase()
    val known = when {
        normalized.contains("physics") -> listOf(
            "Physical quantities and measurement", "Motion", "Force", "Work, power and energy",
            "States of matter and pressure", "Heat", "Waves and sound", "Light", "Electricity",
            "Atomic structure", "Astronomy",
        )
        normalized.contains("chemistry") -> listOf(
            "Matter and its properties", "Structure of matter", "Periodic table", "Chemical bonding",
            "Mole concept and chemical calculations", "Chemical reactions", "Acids, bases and salts",
            "Metals and non-metals", "Organic chemistry",
        )
        normalized.contains("biology") || normalized.contains("life science") -> listOf(
            "Cell and its structure", "Cell division", "Plant tissues", "Animal tissues", "Genetics",
            "Reproduction", "Environment and ecosystem", "Human health and disease", "Evolution",
        )
        normalized.contains("mathematics") || normalized == "mathematics" -> listOf(
            "Sets and functions", "Algebra", "Geometry", "Trigonometry", "Coordinate geometry",
            "Statistics", "Probability", "Mensuration",
        )
        normalized.contains("bangla") -> listOf(
            "Prose", "Poetry", "Supplementary reading", "Grammar", "Composition",
        )
        normalized.contains("english") -> listOf(
            "Reading", "Vocabulary", "Grammar", "Writing", "Listening and speaking",
        )
        normalized.contains("information") || normalized == "ict" -> listOf(
            "Information and communication technology", "Communication systems and networking",
            "Number systems and digital devices", "Web design", "Programming", "Database and spreadsheet",
        )
        normalized.contains("accounting") -> listOf(
            "Introduction to accounting", "Journal", "Ledger", "Cash book", "Trial balance",
            "Financial statements", "Depreciation", "Partnership accounts",
        )
        normalized.contains("economics") -> listOf(
            "Basic concepts of economics", "Demand and supply", "Production", "Market", "National income",
            "Money and banking", "International trade", "Development economics",
        )
        normalized.contains("history") -> listOf(
            "Historical background", "Ancient Bengal", "Medieval Bengal", "British period",
            "Language movement", "Liberation war", "Bangladesh after independence",
        )
        normalized.contains("geography") -> listOf(
            "Earth and environment", "Map reading", "Climate", "Population", "Natural resources",
            "Bangladesh geography", "Disaster management",
        )
        normalized.contains("science") -> listOf(
            "Living world", "Matter", "Force and energy", "Earth and universe", "Environment", "Health and safety",
        )
        else -> emptyList()
    }
    return if (known.isNotEmpty()) known else (1..20).map { "Chapter $it" }
}

@Composable
private fun QuestionReviewContent(
    modifier: Modifier,
    questions: List<ReviewableQuestion>,
    questionType: String,
    totalMarks: Int,
    model: String,
    manualEntry: Boolean,
    error: String?,
    onUpdate: (String, (ReviewableQuestion) -> ReviewableQuestion) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onEditSetup: () -> Unit,
) {
    val selectedCount = questions.count { it.selected }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            BankSection {
                Text("Review before finalizing", color = BankText, fontWeight = FontWeight.Bold)
                Text(
                    if (manualEntry) "Review your manual question, then save it to the private question bank."
                    else "Edit anything the AI misunderstood, then keep only the questions you want to use.",
                    color = BankMuted,
                    fontSize = 13.sp,
                )
                Text(
                    "$selectedCount of ${questions.size} selected · ${questionTypeLabel(questionType)} · ${if (model.isBlank()) "AI" else model}",
                    color = BankCyan,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSelectAll(true) }, modifier = Modifier.weight(1f)) {
                        Text("Select all")
                    }
                    OutlinedButton(onClick = { onSelectAll(false) }, modifier = Modifier.weight(1f)) {
                        Text("Clear all")
                    }
                }
                TextButton(onClick = onEditSetup, modifier = Modifier.align(Alignment.End)) {
                    Text("Discard and edit setup", color = BankCyan)
                }
            }
        }
        error?.let { message ->
            item {
                Text(message, color = Color(0xFFFCA5A5), fontSize = 13.sp)
            }
        }
        if (totalMarks > 0) {
            item {
                Text(
                    "Selected marks must not exceed the exam total of $totalMarks.",
                    color = BankMuted,
                    fontSize = 12.sp,
                )
            }
        }
        itemsIndexed(questions, key = { _, question -> question.sourceQuestionId }) { index, question ->
            ReviewQuestionCard(
                index = index + 1,
                question = question,
                questionType = questionType,
                onUpdate = { transform -> onUpdate(question.sourceQuestionId, transform) },
            )
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun ReviewQuestionCard(
    index: Int,
    question: ReviewableQuestion,
    questionType: String,
    onUpdate: ((ReviewableQuestion) -> ReviewableQuestion) -> Unit,
) {
    val validation = QuestionReviewPolicy.validate(questionType, question)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (question.selected) BankCard else BankCard.copy(alpha = 0.62f),
        ),
        border = BorderStroke(1.dp, if (question.selected) BankCyan.copy(alpha = 0.55f) else BankBorder),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = question.selected,
                    onCheckedChange = { selected -> onUpdate { it.copy(selected = selected) } },
                    colors = CheckboxDefaults.colors(checkedColor = BankCyan, checkmarkColor = BankBg),
                )
                Column(Modifier.weight(1f)) {
                    Text("Question $index", color = BankText, fontWeight = FontWeight.Bold)
                    Text(questionTypeLabel(questionType), color = BankMuted, fontSize = 12.sp)
                }
                Text("${question.marks} mark${if (question.marks == 1) "" else "s"}", color = BankCyan, fontSize = 12.sp)
            }
            question.imageReference?.let { reference ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BankBg,
                    border = BorderStroke(1.dp, BankBorder),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Attached question image", color = BankMuted, fontSize = 12.sp)
                        AsyncImage(
                            model = FirebaseStorageImageUploadHelper.displaySource(LocalContext.current, reference),
                            contentDescription = "Attached question image for question $index",
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }
                }
            }
            BankMultilineField(
                value = question.questionText,
                onValueChange = { value -> onUpdate { it.copy(questionText = value.take(8_000)) } },
                label = "Question text",
                enabled = question.selected,
            )
            if (questionType == "mcq") {
                question.options.forEachIndexed { optionIndex, option ->
                    BankTextField(
                        value = option,
                        onValueChange = { value ->
                            onUpdate {
                                val next = it.options.toMutableList()
                                if (optionIndex in next.indices) next[optionIndex] = value.take(1_000)
                                it.copy(options = next)
                            }
                        },
                        label = "Option ${('A'.code + optionIndex).toChar()}",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = question.selected,
                    )
                }
            }
            BankMultilineField(
                value = question.correctAnswer,
                onValueChange = { value -> onUpdate { it.copy(correctAnswer = value.take(2_000)) } },
                label = if (questionType == "mcq") "Correct option (must match exactly)" else "Model answer",
                enabled = question.selected,
            )
            BankMultilineField(
                value = question.explanation,
                onValueChange = { value -> onUpdate { it.copy(explanation = value.take(4_000)) } },
                label = "Explanation (optional)",
                enabled = question.selected,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BankTextField(
                    value = question.marks.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { marks -> onUpdate { it.copy(marks = marks.coerceIn(1, 100)) } }
                    },
                    label = "Marks",
                    modifier = Modifier.weight(0.34f),
                    keyboardType = KeyboardType.Number,
                    enabled = question.selected,
                )
                Column(Modifier.weight(0.66f)) {
                    Text("Difficulty", color = BankMuted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("easy", "medium", "hard").forEach { difficulty ->
                            FilterChip(
                                selected = question.difficulty == difficulty,
                                onClick = { onUpdate { it.copy(difficulty = difficulty) } },
                                enabled = question.selected,
                                label = { Text(difficulty.replaceFirstChar(Char::uppercase), fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BankCyan.copy(alpha = 0.18f),
                                    selectedLabelColor = BankCyan,
                                    labelColor = BankMuted,
                                ),
                            )
                        }
                    }
                }
            }
            if (question.selected && !validation.isValid) {
                Text(validation.message.orEmpty(), color = Color(0xFFFCA5A5), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ReviewCostBar(
    selectedCount: Int,
    selectedMarks: Int,
    totalMarks: Int,
    costPoisha: Int,
    billingMode: String,
    walletBalancePoisha: Int,
    manualEntry: Boolean,
    finalizing: Boolean,
    validationError: String?,
    onFinalize: () -> Unit,
) {
    val isFree = manualEntry || billingMode == "lifetime_free"
    val hasEnoughBalance = isFree || walletBalancePoisha >= costPoisha
    Surface(color = BankCard, tonalElevation = 8.dp, shadowElevation = 12.dp) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            manualEntry -> "Manual entry - no AI charge"
                            billingMode == "lifetime_free" -> "Lifetime free attempt - no charge"
                            else -> "Final charge: ${bdtFromPoisha(costPoisha)}"
                        },
                        color = BankText,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "$selectedCount selected · $selectedMarks/${totalMarks.coerceAtLeast(0)} marks",
                        color = BankMuted,
                        fontSize = 12.sp,
                    )
                }
                Button(
                    onClick = onFinalize,
                    enabled = !finalizing && selectedCount > 0 && validationError == null &&
                        (totalMarks <= 0 || selectedMarks <= totalMarks) && hasEnoughBalance,
                    colors = ButtonDefaults.buttonColors(containerColor = BankCyan, contentColor = BankBg),
                ) {
                    if (finalizing) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = BankBg, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (finalizing) "Saving..." else "Finalize")
                }
            }
            Text(
                when {
                    manualEntry -> "Manual questions use the same reviewed and secure question-bank save flow."
                    billingMode == "lifetime_free" -> "This entire AI attempt is free, including every selected question."
                    hasEnoughBalance -> "Wallet ${bdtFromPoisha(walletBalancePoisha)} · The server debits once when finalization succeeds."
                    else -> "Insufficient wallet balance. Available ${bdtFromPoisha(walletBalancePoisha)}; required ${bdtFromPoisha(costPoisha)}."
                },
                color = if (hasEnoughBalance) BankMuted else Color(0xFFFBBF24),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun FinalizationSuccessDialog(
    result: QuestionFinalizationResult,
    onCreatePaper: () -> Unit,
    onCreateAnother: () -> Unit,
    onBackToExams: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = BankCard,
        title = { Text("Questions finalized", color = BankText, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${result.questionCount} reviewed question(s) are now in your private question bank.", color = BankText)
                Text(
                    when (result.billingStatus) {
                        "manual_no_ai_charge" -> "Manual entry · No AI charge"
                        "lifetime_free" -> "Lifetime free attempt · No charge"
                        else -> "Wallet charged: ${bdtFromPoisha(result.chargedCostPoisha)}"
                    },
                    color = BankCyan,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (result.billingStatus == "wallet_debited") {
                        "Remaining question wallet balance: ${bdtFromPoisha(result.remainingBalancePoisha)}. Consented content is queued anonymously for moderation."
                    } else {
                        "No wallet debit was made. Consented content is queued anonymously for Super Admin moderation."
                    },
                    color = BankMuted,
                    fontSize = 13.sp,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCreateAnother) { Text("Create another") } },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBackToExams) { Text("Back to exams") }
                Button(
                    onClick = onCreatePaper,
                    colors = ButtonDefaults.buttonColors(containerColor = BankCyan, contentColor = BankBg),
                ) { Text("Create PDF") }
            }
        },
    )
}

private fun questionTypeLabel(questionType: String): String = when (questionType) {
    "mcq" -> "MCQ"
    "short" -> "Short question"
    "creative" -> "Creative question"
    else -> questionType
}

private fun bdtFromPoisha(value: Int): String {
    val whole = value / 100
    val fraction = (value % 100).toString().padStart(2, '0')
    return "BDT $whole.$fraction"
}

@Composable
private fun BankMultilineField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 8,
        enabled = enabled,
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
private fun BankTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        enabled = enabled,
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

@Composable
internal fun QuestionPaperComposerDialog(
    db: AppDatabase,
    instituteId: String?,
    examName: String,
    className: String,
    subject: String,
    chapter: String,
    totalMarks: Int,
    durationMinutes: Int,
    questions: List<ReviewableQuestion>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var institute by remember { mutableStateOf<com.batchfee.edu.data.models.InstituteEntity?>(null) }
    var loadingInstitute by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var generatedFile by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var paperSize by rememberSaveable { mutableStateOf(QuestionPaperSize.A4.name) }
    var margin by rememberSaveable { mutableStateOf(QuestionPaperMargin.STANDARD.name) }
    var fontSize by rememberSaveable { mutableStateOf(QuestionPaperFontSize.STANDARD.name) }
    var includeAnswerKey by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(db, instituteId) {
        loadingInstitute = true
        institute = instituteId?.let { id -> withContext(Dispatchers.IO) { db.instituteDao().getInstitute(id) } }
        loadingInstitute = false
    }

    fun currentSetup() = QuestionPaperSetup(
        examName = examName.trim(),
        className = className.trim(),
        subject = subject.trim(),
        chapter = chapter.trim(),
        totalMarks = totalMarks,
        durationMinutes = durationMinutes,
        paperSize = QuestionPaperSize.valueOf(paperSize),
        margin = QuestionPaperMargin.valueOf(margin),
        fontSize = QuestionPaperFontSize.valueOf(fontSize),
        includeAnswerKey = includeAnswerKey,
    )

    fun generatePreview() {
        val owner = institute
        if (owner == null) {
            error = "Your institute profile could not be loaded. Try again after it finishes syncing."
            return
        }
        working = true
        error = null
        notice = null
        scope.launch {
            runCatching { generateQuestionPaperPdf(context, owner, currentSetup(), questions) }
                .onSuccess {
                    generatedFile?.takeIf { old -> old != it && old.exists() }?.delete()
                    generatedFile = it
                    notice = "PDF preview is ready. Review the layout, then download or print it."
                }
                .onFailure { error = it.message ?: "Could not create the question paper PDF." }
            working = false
        }
    }

    Dialog(onDismissRequest = { if (!working) onDismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = BankBg,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PictureAsPdf, null, tint = BankCyan, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Page setup & preview", color = BankText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Institute-branded question paper", color = BankMuted, fontSize = 12.sp)
                    }
                    TextButton(onClick = onDismiss, enabled = !working) { Text("Close") }
                }

                BankSection {
                    Text(examName.ifBlank { "Question Paper" }, color = BankText, fontWeight = FontWeight.Bold)
                    Text(
                        "$className  •  $subject  •  ${questions.size} selected question${if (questions.size == 1) "" else "s"}",
                        color = BankMuted,
                        fontSize = 12.sp,
                    )
                    Text(
                        "The PDF contains your institute's name, logo and a subtle institute-name watermark. BatchFee branding is never added.",
                        color = BankCyan,
                        fontSize = 12.sp,
                    )
                }

                Text("Paper size", color = BankText, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuestionPaperSize.entries.forEach { option ->
                        FilterChip(
                            selected = paperSize == option.name,
                            onClick = { paperSize = option.name; generatedFile = null },
                            label = { Text(option.label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BankCyan.copy(alpha = .18f), selectedLabelColor = BankCyan, labelColor = BankMuted),
                        )
                    }
                }
                Text("Margins", color = BankText, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuestionPaperMargin.entries.forEach { option ->
                        FilterChip(
                            selected = margin == option.name,
                            onClick = { margin = option.name; generatedFile = null },
                            label = { Text(option.label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BankCyan.copy(alpha = .18f), selectedLabelColor = BankCyan, labelColor = BankMuted),
                        )
                    }
                }
                Text("Text size", color = BankText, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuestionPaperFontSize.entries.forEach { option ->
                        FilterChip(
                            selected = fontSize == option.name,
                            onClick = { fontSize = option.name; generatedFile = null },
                            label = { Text(option.label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BankCyan.copy(alpha = .18f), selectedLabelColor = BankCyan, labelColor = BankMuted),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Include answer key", color = BankText, fontWeight = FontWeight.SemiBold)
                        Text("Adds a separate teacher-only answer-key page.", color = BankMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = includeAnswerKey,
                        onCheckedChange = { includeAnswerKey = it; generatedFile = null },
                    )
                }

                BankSection {
                    Text("Visual preview", color = BankText, fontWeight = FontWeight.Bold)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF8FAFC),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(institute?.name ?: "Institute", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
                            Text(examName.ifBlank { "Question Paper" }, color = Color(0xFF0369A1), fontWeight = FontWeight.SemiBold)
                            Text("$className  |  $subject  |  Marks: $totalMarks  |  Time: $durationMinutes min", color = Color(0xFF475569), fontSize = 10.sp)
                            questions.take(2).forEachIndexed { index, question ->
                                Text("${index + 1}. ${question.questionText}", color = Color(0xFF0F172A), fontSize = 11.sp, maxLines = 3)
                                question.options.take(4).forEachIndexed { optionIndex, option ->
                                    Text("   ${('A'.code + optionIndex).toChar()}. $option", color = Color(0xFF475569), fontSize = 10.sp, maxLines = 1)
                                }
                            }
                            if (questions.size > 2) Text("+ ${questions.size - 2} more question(s)", color = Color(0xFF0369A1), fontSize = 10.sp)
                            Text(institute?.name ?: "Institute", color = Color(0xFFCBD5E1), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("Preview shows the final hierarchy. The generated PDF handles page breaks automatically.", color = BankMuted, fontSize = 11.sp)
                }

                if (loadingInstitute) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = BankCyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading institute branding…", color = BankMuted, fontSize = 12.sp)
                    }
                }
                error?.let { Text(it, color = Color(0xFFFCA5A5), fontSize = 12.sp) }
                notice?.let { Text(it, color = Color(0xFF86EFAC), fontSize = 12.sp) }

                Button(
                    onClick = ::generatePreview,
                    enabled = !working && !loadingInstitute && institute != null && questions.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BankCyan, contentColor = BankBg),
                ) {
                    if (working) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = BankBg, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (working) "Creating PDF…" else if (generatedFile == null) "Generate PDF preview" else "Regenerate PDF", fontWeight = FontWeight.Bold)
                }
                generatedFile?.let { file ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                runCatching {
                                    downloadQuestionPaperPdf(context, file, examName.ifBlank { "question_paper" })
                                }.onSuccess { notice = "PDF saved to Downloads/Question Papers." }
                                    .onFailure { error = it.message ?: "Could not download PDF." }
                            },
                        ) {
                            Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download")
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (!printQuestionPaperPdf(context, file, examName.ifBlank { "Question Paper" })) {
                                    error = "Printing is not available on this device."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        ) {
                            Icon(Icons.Filled.Print, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Direct print")
                        }
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
