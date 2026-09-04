package com.batchfee.edu.ui.exams

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import com.batchfee.edu.data.models.ExamEntity
import com.batchfee.edu.domain.SessionManager
import com.example.domain.BulkMessageController
import com.example.ui.components.BulkMessageDialog
import com.example.ui.components.BulkSendProgressPanel
import com.example.ui.components.SelectionBadge
import com.batchfee.edu.ui.components.buildWhatsAppUrl
import coil.compose.AsyncImage
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val CardBgAlt = Color(0xFF111827)
private val CardHi = Color(0xFF132033)
private val BorderSub = Color(0xFF1E293B)
private val Cyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val AccentGreen = Color(0xFF10B981)
private val AccentRed = Color(0xFFEF4444)
private val AccentAmber = Color(0xFFF59E0B)
private val AccentViolet = Color(0xFF8B5CF6)
private val WAGreen = Color(0xFF25D366)

// ═══════════════════════════════════════════════════════════
//  ExamListScreen
// ═══════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamListScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onAddExam: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToPricing: () -> Unit,
    onOpenFinalExams: () -> Unit = {},
    onCreateFinalExam: () -> Unit = {}
) {
    val viewModel: ExamViewModel = viewModel(factory = ExamViewModelFactory(db))
    val exams by viewModel.exams.collectAsState()
    val batches by viewModel.batches.collectAsState()
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    var showCreateChoice by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Exams & Results", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateChoice = true },
                containerColor = Color.Transparent, contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                    .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
            ) { Icon(Icons.Default.Add, "Add", tint = Color.White) }
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
        ) {
            // Final Exam module entry
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
                    .shadow(3.dp, RoundedCornerShape(16.dp), spotColor = AccentViolet.copy(alpha = 0.25f))
                    .clickable { onOpenFinalExams() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.5.dp, AccentViolet.copy(alpha = 0.6f))
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(13.dp))
                            .background(Brush.linearGradient(listOf(AccentViolet, Cyan))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.School, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Final Exams", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text("Multi-subject exams with approval workflow", color = TextMuted, fontSize = 12.sp)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = AccentViolet)
                }
            }

            if (exams.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.School, null, tint = TextMuted.copy(alpha = 0.4f), modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No exams yet.", color = TextMuted, fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap + to create your first exam.", color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text("${exams.size} exam${if (exams.size != 1) "s" else ""}", color = TextMuted, fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    items(exams, key = { it.id }) { exam ->
                        val batch = batches.find { it.id == exam.batchId }
                        val statusColor = when (exam.status) {
                            "completed" -> AccentGreen
                            "scheduled" -> Cyan
                            else -> TextMuted
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth()
                                .shadow(2.dp, RoundedCornerShape(14.dp), spotColor = statusColor.copy(alpha = 0.12f))
                                .clickable { onNavigateToDetail(exam.id) },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            border = BorderStroke(1.dp, BorderSub)
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                                        .background(statusColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(exam.examName.take(1).uppercase(), color = statusColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(exam.examName, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${batch?.name ?: "Batch"} · ${dateFormat.format(Date(exam.examDateMs))}",
                                        color = TextMuted, fontSize = 12.sp
                                    )
                                    if (exam.subject != null) Text(exam.subject, color = TextMuted.copy(alpha = 0.7f), fontSize = 11.sp)
                                    if (exam.examFeeAmount > 0.0) {
                                        Text(
                                            "Exam fee: BDT ${formatNum(exam.examFeeAmount)} per student",
                                            color = AccentAmber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(statusColor.copy(alpha = 0.12f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(exam.status.replaceFirstChar { it.uppercase() }, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showCreateChoice) {
        AlertDialog(
            onDismissRequest = { showCreateChoice = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(18.dp),
            title = { Text("Create Exam", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(CardBgAlt).border(1.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable {
                                showCreateChoice = false
                                onAddExam()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Brush.linearGradient(listOf(ElectricBlue, Cyan))),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.School, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Regular Exam", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("Single-subject exam with results & fee", color = TextMuted, fontSize = 11.sp)
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = TextMuted)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(CardBgAlt).border(1.dp, AccentViolet.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .clickable {
                                showCreateChoice = false
                                onCreateFinalExam()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Brush.linearGradient(listOf(AccentViolet, Cyan))),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.School, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Final Exam", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("Multi-subject with approval workflow", color = TextMuted, fontSize = 11.sp)
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = TextMuted)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCreateChoice = false }) { Text("Cancel", color = TextMuted) }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  CreateExamScreen
// ═══════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditExamScreen(db: AppDatabase, examId: String? = null, onBack: () -> Unit) {
    val viewModel: ExamViewModel = viewModel(factory = ExamViewModelFactory(db))
    val batches by viewModel.batches.collectAsState()
    val selectedExam by viewModel.selectedExam.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isEditMode = examId != null
    val instituteId = SessionManager.currentInstituteId.collectAsState().value

    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var examName by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var teacherName by remember { mutableStateOf("") }
    var examNote by remember { mutableStateOf("") }
    var totalMarks by remember { mutableStateOf("100") }
    var passingMarks by remember { mutableStateOf("40") }
    var examFeeAmount by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    val pendingExamId = remember { UUID.randomUUID().toString() }
    val pendingExamFeeOperationId = remember { UUID.randomUUID().toString() }
    var selectedDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var teacherTemplates by remember { mutableStateOf<List<String>>(emptyList()) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    var formInitialized by remember(examId) { mutableStateOf(false) }
    val hasGeneratedExamFees = isEditMode && (selectedExam?.examFeeAmount ?: 0.0) > 0.0

    LaunchedEffect(examId) {
        if (examId != null) {
            viewModel.loadExamDetails(examId)
        }
    }

    LaunchedEffect(instituteId) {
        if (instituteId == null) {
            teacherTemplates = emptyList()
        } else {
            db.staffDao().getActiveStaff(instituteId).collect { staff ->
                teacherTemplates = staff.map { it.fullName.trim() }.filter { it.isNotBlank() }.distinct()
            }
        }
    }

    LaunchedEffect(selectedExam?.id, examId) {
        val exam = selectedExam
        if (isEditMode && exam != null && exam.id == examId && !formInitialized) {
            selectedBatchId = exam.batchId
            examName = exam.examName
            subject = exam.subject.orEmpty()
            teacherName = exam.teacherName.orEmpty()
            examNote = exam.note.orEmpty()
            totalMarks = formatNum(exam.totalMarks)
            passingMarks = formatNum(exam.passingMarks)
            examFeeAmount = if (exam.examFeeAmount > 0.0) formatNum(exam.examFeeAmount) else ""
            selectedDateMs = exam.examDateMs
            formInitialized = true
        }
    }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Edit Exam" else "Create Exam", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Batch selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Select Batch", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (batches.isEmpty()) {
                        Text("No batches available", color = TextMuted, fontSize = 13.sp)
                    } else {
                        LazyRow { items(batches) { b ->
                            val sel = selectedBatchId == b.id
                            FilterChip(
                                selected = sel, onClick = { if (!hasGeneratedExamFees) selectedBatchId = if (sel) null else b.id },
                                enabled = !hasGeneratedExamFees,
                                label = { Text(b.name, fontSize = 12.sp) },
                                modifier = Modifier.padding(end = 6.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricBlue.copy(alpha = 0.25f),
                                    selectedLabelColor = Cyan, containerColor = CardBgAlt, labelColor = TextMuted
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = BorderSub, selectedBorderColor = Cyan, enabled = true, selected = sel
                                )
                            )
                        }}
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            ExamTemplateRow(
                label = "Quick exam templates",
                options = listOf("Class Test", "Weekly Test", "Monthly Test", "Unit Test", "Midterm", "Final Exam", "Model Test")
            ) { examName = it }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = examName, onValueChange = { examName = it }, enabled = !hasGeneratedExamFees,
                label = { Text("Exam Name *") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            ExamTemplateRow(
                label = "Popular subjects",
                options = listOf("Bangla", "English", "Mathematics", "Science", "Physics", "Chemistry", "Biology", "ICT", "Accounting")
            ) { subject = it }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = subject, onValueChange = { subject = it },
                label = { Text("Subject (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            if (teacherTemplates.isNotEmpty()) {
                ExamTemplateRow(label = "Select a teacher", options = teacherTemplates) { teacherName = it }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = teacherName, onValueChange = { teacherName = it },
                label = { Text("Teacher Name (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = examNote, onValueChange = { examNote = it },
                label = { Text("Note (optional)") }, maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))

            // Date picker
            OutlinedTextField(
                value = dateFormat.format(Date(selectedDateMs)),
                onValueChange = {}, readOnly = true, enabled = false,
                label = { Text("Exam Date") },
                trailingIcon = { IconButton(onClick = { if (!hasGeneratedExamFees) showDatePicker = true }) { Icon(Icons.Filled.CalendarToday, null, tint = Cyan) } },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(), shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = totalMarks, onValueChange = { totalMarks = it },
                    label = { Text("Total Marks *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.weight(1f),
                    colors = fieldColors(), shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = passingMarks, onValueChange = { passingMarks = it },
                    label = { Text("Pass Marks *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.weight(1f),
                    colors = fieldColors(), shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            if (!isEditMode) {
                OutlinedTextField(
                    value = examFeeAmount,
                    onValueChange = { examFeeAmount = it },
                    label = { Text("Exam Fee (optional)") },
                    placeholder = { Text("e.g. 200") },
                    supportingText = {
                        Text(
                            "This amount will be added as an exam fee for every enrolled student.",
                            color = TextMuted, fontSize = 11.sp
                        )
                    },
                    trailingIcon = { Text("BDT", color = Cyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(), shape = RoundedCornerShape(12.dp)
                )
            } else if ((selectedExam?.examFeeAmount ?: 0.0) > 0.0) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(AccentAmber.copy(alpha = 0.10f))
                        .border(1.dp, AccentAmber.copy(alpha = 0.34f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Exam fee", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Already created for enrolled students", color = TextMuted, fontSize = 11.sp)
                    }
                    Text("BDT ${formatNum(selectedExam!!.examFeeAmount)}", color = AccentAmber, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(20.dp))
            val parsedExamFee = examFeeAmount.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() }
            val canSaveExam = selectedBatchId != null && examName.isNotBlank() &&
                    (totalMarks.toDoubleOrNull() ?: 0.0) > 0 &&
                    (passingMarks.toDoubleOrNull() ?: 0.0) <= (totalMarks.toDoubleOrNull() ?: 100.0) &&
                    parsedExamFee != null && parsedExamFee >= 0.0 && !isSaving
            Box(
                modifier = Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
                    .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.3f))
                    .let { m -> if (canSaveExam) m.background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))) else m.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(14.dp)) },
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = {
                        if (isSaving) return@TextButton
                        isSaving = true
                        val onError: (String) -> Unit = { message ->
                            isSaving = false
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        }
                        if (isEditMode) {
                            viewModel.updateExam(
                                examId = examId!!,
                                batchId = selectedBatchId!!,
                                examName = examName,
                                subject = subject,
                                totalMarks = totalMarks.toDoubleOrNull() ?: 100.0,
                                passingMarks = passingMarks.toDoubleOrNull() ?: 40.0,
                                examDateMs = selectedDateMs,
                                teacherName = teacherName.ifBlank { null },
                                note = examNote.ifBlank { null },
                                onSuccess = onBack,
                                onError = onError
                            )
                        } else {
                            viewModel.createExam(
                                batchId = selectedBatchId!!, examName = examName, subject = subject,
                                totalMarks = totalMarks.toDoubleOrNull() ?: 100.0,
                                passingMarks = passingMarks.toDoubleOrNull() ?: 40.0,
                                examDateMs = selectedDateMs,
                                teacherName = teacherName.ifBlank { null },
                                note = examNote.ifBlank { null },
                                examFeeAmount = parsedExamFee ?: 0.0,
                                examId = pendingExamId,
                                operationId = pendingExamFeeOperationId,
                                onSuccess = onBack,
                                onError = onError
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(), enabled = canSaveExam,
                    colors = ButtonDefaults.textButtonColors(contentColor = if (canSaveExam) Color.White else TextMuted)
                ) {
                    Text(
                        if (isSaving) "Saving..." else if (isEditMode) "Update Exam" else "Create Exam",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDateMs)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { selectedDateMs = it }
                    showDatePicker = false
                }) { Text("OK", color = Cyan) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel", color = TextMuted) } }
        ) { DatePicker(state = datePickerState) }
    }
}

// ═══════════════════════════════════════════════════════════
//  ExamDetailScreen  (mark entry + merit list + share)
// ═══════════════════════════════════════════════════════════
@Composable
private fun ExamTemplateRow(label: String, options: List<String>, onSelected: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options, key = { it }) { option ->
                SuggestionChip(
                    onClick = { onSelected(option) },
                    label = { Text(option, fontSize = 11.sp, maxLines = 1) },
                    shape = RoundedCornerShape(9.dp),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = CardBgAlt,
                        labelColor = Cyan
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        borderColor = BorderSub,
                        enabled = true
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamDetailScreen(db: AppDatabase, examId: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val viewModel: ExamViewModel = viewModel(factory = ExamViewModelFactory(db))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val selectedExam by viewModel.selectedExam.collectAsState()
    val studentResults by viewModel.studentResults.collectAsState()
    val batchStudents by viewModel.batchStudents.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val batches by viewModel.batches.collectAsState()
    val institute by viewModel.institute.collectAsState()

    var marksMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showMeritDialog by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var showStudentMessageDialog by remember { mutableStateOf<StudentResultItem?>(null) }
    var showExamMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isSavingResults by remember { mutableStateOf(false) }
    var showResultPicker by remember { mutableStateOf(false) }
    var resultBulkChannel by remember { mutableStateOf("sms") }
    var resultPickerIds by remember { mutableStateOf(setOf<String>()) }
    var showResultComposer by remember { mutableStateOf(false) }
    var resultBulkText by remember { mutableStateOf("") }
    val bulkState by viewModel.bulkSender.state.collectAsState()

    fun startResultBulkSend(channel: String, delayMs: Long, recipientIds: Set<String>, exam: ExamEntity) {
        val customText = resultBulkText.trim()
        val targets = studentResults
            .filter { it.result != null && it.student.id in recipientIds }
            .map { item ->
                BulkMessageController.BulkTarget(
                    key = item.student.id,
                    name = item.student.fullName,
                    phone = item.student.phone
                )
            }
        if (targets.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("Select at least one student.") }
            return
        }
        val started = viewModel.bulkSender.start(
            targets = targets,
            channel = channel,
            delayMs = delayMs,
            messageBuilder = { target ->
                val item = studentResults.firstOrNull { it.student.id == target.key }
                val base = if (item != null) viewModel.buildStudentMessage(item, exam) else ""
                if (customText.isBlank()) base else "$customText\n\n$base"
            },
            launcher = { target, body ->
                if (channel == "whatsapp") {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(buildWhatsAppUrl(target.phone, body)))
                        )
                    }.isSuccess
                } else {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${target.phone?.filter(Char::isDigit).orEmpty()}"))
                                .apply { putExtra("sms_body", body) }
                        )
                    }.isSuccess
                }
            }
        )
        if (!started) {
            scope.launch { snackbarHostState.showSnackbar("Sending is already in progress.") }
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewModel.bulkSender.onPaused()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewModel.bulkSender.onResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(examId) { viewModel.loadExamDetails(examId) }

    // Initialize marks
    LaunchedEffect(studentResults) {
        if (marksMap.isEmpty() && studentResults.isNotEmpty()) {
            marksMap = studentResults.associate { it.student.id to it.marksText }
        }
    }

    val batchName = batches.find { it.id == selectedExam?.batchId }?.name ?: "Batch"
    val hasResults = studentResults.any { it.result != null }
    val canPublish = hasResults && selectedExam?.status == "completed"
    val instituteName = institute?.name?.trim().takeUnless { it.isNullOrBlank() } ?: "BatchFee"
    val instituteLogoSource = FirebaseStorageImageUploadHelper.displaySource(context, institute?.profilePhotoUri)

    Scaffold(
        containerColor = BgColor, snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(selectedExam?.examName ?: "Exam Detail", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                actions = {
                    if (hasResults) {
                        IconButton(onClick = { showShareSheet = true }) { Icon(Icons.Filled.Share, null, tint = Cyan) }
                    }
                    Box {
                        IconButton(onClick = { showExamMenu = true }) {
                            Icon(Icons.Filled.MoreVert, null, tint = TextWhite)
                        }
                        DropdownMenu(
                            expanded = showExamMenu,
                            onDismissRequest = { showExamMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit Exam") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    showExamMenu = false
                                    onEdit()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Exam", color = AccentRed) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = AccentRed) },
                                onClick = {
                                    showExamMenu = false
                                    showDeleteConfirm = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cyan, strokeWidth = 3.dp)
            }
        } else {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // ── Exam info card ────────────────────
                val exam = selectedExam
                if (exam != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .shadow(3.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, BorderSub)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(exam.examName, color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text(batchName, color = Cyan, fontSize = 13.sp)
                                    if (exam.subject != null) Text(exam.subject, color = TextMuted, fontSize = 12.sp)
                                }
                                Box(
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                        .background(if (exam.status == "completed") AccentGreen.copy(alpha = 0.15f) else Cyan.copy(alpha = 0.15f))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text(exam.status.replaceFirstChar { it.uppercase() }, color = if (exam.status == "completed") AccentGreen else Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                StatLabel("Total", formatNum(exam.totalMarks), Cyan)
                                StatLabel("Pass", formatNum(exam.passingMarks), Cyan)
                                StatLabel("Students", "${batchStudents.size}", ElectricBlue)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // ── Results / Mark Entry ────────────────
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (hasResults) "Results" else "Mark Entry", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (canPublish) {
                        Button(
                            onClick = {
                                viewModel.publishResults(examId,
                                    onSuccess = { scope.launch { snackbarHostState.showSnackbar("Results published!") } },
                                    onError = { scope.launch { snackbarHostState.showSnackbar(it) } }
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text("Publish", fontSize = 12.sp, color = Color.White) }
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (studentResults.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text("No students in this batch.", color = TextMuted, fontSize = 13.sp)
                    }
                } else {
                    studentResults.forEachIndexed { idx, item ->
                        val currentMarks = marksMap[item.student.id] ?: ""
                        ResultEntryRow(
                            position = idx + 1,
                            student = item.student,
                            marksText = currentMarks,
                            totalMarks = selectedExam?.totalMarks ?: 100.0,
                            result = item.result,
                            onMarksChange = { marksMap = marksMap + (item.student.id to it) },
                            onTap = {
                                if (item.result != null) showStudentMessageDialog = item
                            }
                        )
                        if (idx < studentResults.size - 1) Spacer(Modifier.height(6.dp))
                    }
                }

                // ── Save button ────────────────────────
                if (!hasResults && studentResults.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    val canSave = !isSavingResults && marksMap.values.any { (it.toDoubleOrNull() ?: -1.0) >= 0 }
                    Box(
                        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                            .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.3f))
                            .let { m -> if (canSave) m.background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))) else m.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(14.dp)) },
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(
                            onClick = {
                                if (isSavingResults) return@TextButton
                                isSavingResults = true
                                val marksList = marksMap.map { it.key to (it.value.toDoubleOrNull() ?: 0.0) }.filter { it.second > 0 }
                                viewModel.saveResults(examId, selectedExam?.batchId ?: "", marksList,
                                    onSuccess = {
                                        isSavingResults = false
                                        scope.launch { snackbarHostState.showSnackbar("Results saved!") }
                                    },
                                    onError = {
                                        isSavingResults = false
                                        scope.launch { snackbarHostState.showSnackbar(it) }
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxSize(), enabled = canSave,
                            colors = ButtonDefaults.textButtonColors(contentColor = if (canSave) Color.White else TextMuted)
                        ) {
                            if (isSavingResults) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (isSavingResults) "Saving..." else "Save All Marks", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // ── Merit list dialog ───────────────────────────
    if (showMeritDialog && selectedExam != null) {
        val msg = viewModel.buildMeritMessage(selectedExam!!)
        Dialog(onDismissRequest = { showMeritDialog = false }) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = CardBg), border = BorderStroke(1.dp, BorderSub)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Merit List", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text(msg, color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { showMeritDialog = false; shareText(context, msg, "Merit List") }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, WAGreen)) { Text("Share", color = WAGreen, fontSize = 13.sp) }
                        TextButton(onClick = { showMeritDialog = false }, modifier = Modifier.weight(1f)) { Text("Close", color = TextMuted) }
                    }
                }
            }
        }
    }

    // ── Share sheet ─────────────────────────────────
    if (showShareSheet && selectedExam != null) {
        val exam = selectedExam!!
        Dialog(onDismissRequest = { showShareSheet = false }) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = CardBg), border = BorderStroke(1.dp, BorderSub)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Share Results", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(exam.examName, color = TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    ShareOption("All Results (PDF)", Icons.Filled.PictureAsPdf, WAGreen) {
                        showShareSheet = false
                        val items = studentResults.filter { it.result != null }
                        if (items.isEmpty()) {
                            scope.launch { snackbarHostState.showSnackbar("No results to share yet.") }
                        } else {
                            shareResultsPdf(context, items, exam, batchName, instituteName, institute?.profilePhotoUri)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ShareOption("Top 10 (PDF)", Icons.Filled.PictureAsPdf, AccentViolet) {
                        showShareSheet = false
                        val items = studentResults.filter { it.result != null }.take(10)
                        if (items.isEmpty()) {
                            scope.launch { snackbarHostState.showSnackbar("No results to share yet.") }
                        } else {
                            shareResultsPdf(context, items, exam, batchName, instituteName, institute?.profilePhotoUri)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ShareOption("Message All (SMS)", Icons.Filled.Sms, ElectricBlue) {
                        showShareSheet = false
                        resultBulkChannel = "sms"
                        resultPickerIds = studentResults.filter { it.result != null }.map { it.student.id }.toSet()
                        showResultPicker = true
                    }
                    Spacer(Modifier.height(8.dp))
                    ShareOption("Message All (WhatsApp)", Icons.Filled.Whatsapp, WAGreen) {
                        showShareSheet = false
                        resultBulkChannel = "whatsapp"
                        resultPickerIds = studentResults.filter { it.result != null }.map { it.student.id }.toSet()
                        showResultPicker = true
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { showShareSheet = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = TextMuted) }
                }
            }
        }
    }

    // ── Result recipient picker ───────────────────
    if (showResultPicker && selectedExam != null) {
        val exam = selectedExam!!
        val withResults = studentResults.filter { it.result != null }
        Dialog(onDismissRequest = { showResultPicker = false }) {
            Card(
                modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.84f),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (resultBulkChannel == "whatsapp") Icons.Filled.Whatsapp else Icons.Filled.Sms,
                            contentDescription = null,
                            tint = if (resultBulkChannel == "whatsapp") WAGreen else ElectricBlue,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Send Results", color = Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("${resultPickerIds.size} of ${withResults.size} selected", color = TextMuted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { showResultPicker = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = AccentRed, modifier = Modifier.size(28.dp))
                        }
                    }
                    HorizontalDivider(color = BorderSub)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { resultPickerIds = withResults.map { it.student.id }.toSet() },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Cyan.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                        ) { Text("Select All", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = { resultPickerIds = emptySet() },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                        ) { Text("Clear", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(withResults, key = { it.student.id }) { item ->
                            val selected = item.student.id in resultPickerIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) CardHi else CardBgAlt)
                                    .border(1.dp, if (selected) Cyan.copy(alpha = 0.6f) else BorderSub, RoundedCornerShape(14.dp))
                                    .clickable {
                                        resultPickerIds = if (item.student.id in resultPickerIds) resultPickerIds - item.student.id else resultPickerIds + item.student.id
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SelectionBadge(selected = selected)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.student.fullName, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.student.phone ?: "No phone", color = if (item.student.phone.isNullOrBlank()) AccentRed else TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("${item.result?.grade ?: "-"} · #${item.position}", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    HorizontalDivider(color = BorderSub)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                            .clickable {
                                if (resultPickerIds.isEmpty()) {
                                    scope.launch { snackbarHostState.showSnackbar("Select at least one student.") }
                                } else {
                                    showResultPicker = false
                                    showResultComposer = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Continue with ${resultPickerIds.size} student${if (resultPickerIds.size == 1) "" else "s"}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // ── Result composer ────────────────────────────
    if (showResultComposer && selectedExam != null) {
        val exam = selectedExam!!
        BulkMessageDialog(
            title = "Send Exam Results",
            recipientCount = resultPickerIds.size,
            messageText = resultBulkText,
            onMessageChange = { resultBulkText = it },
            initialDelaySeconds = 3,
            onStartWhatsApp = { delayMs ->
                startResultBulkSend("whatsapp", delayMs, resultPickerIds, exam)
                showResultComposer = false
            },
            onStartSms = { delayMs ->
                startResultBulkSend("sms", delayMs, resultPickerIds, exam)
                showResultComposer = false
            },
            onDismiss = { showResultComposer = false },
            broadcastMode = false
        )
    }

    // ── Bulk send progress ─────────────────────────
    if (bulkState.active) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            BulkSendProgressPanel(
                state = bulkState,
                onRetryFailed = { viewModel.bulkSender.retryFailed() },
                onStop = { viewModel.bulkSender.cancel() },
                onClose = { viewModel.bulkSender.reset() }
            )
        }
    }

    // ── Single student result card ─────────────
    if (showStudentMessageDialog != null && selectedExam != null) {
        val item = showStudentMessageDialog!!
        val exam = selectedExam!!
        val gradeColor = when (item.result?.grade) {
            "A+", "A", "A-" -> AccentGreen; "B", "C" -> AccentAmber; "F" -> AccentRed; else -> TextMuted
        }
        val passFail = if ((item.result?.marksObtained ?: 0.0) >= exam.passingMarks) "PASSED" else "FAILED"
        val passColor = if (passFail == "PASSED") AccentGreen else AccentRed
        Dialog(onDismissRequest = { showStudentMessageDialog = null }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column {
                    // Exam header
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                            .background(Brush.linearGradient(listOf(ElectricBlue, AccentViolet)))
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("EXAM RESULT", color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                                Spacer(Modifier.height(5.dp))
                                Text(exam.examName, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!exam.subject.isNullOrBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(exam.subject, color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(batchName, modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.width(12.dp))
                                    Text(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(exam.examDateMs)), color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            ResultCardInstituteLogo(instituteLogoSource, instituteName)
                        }
                    }

                    // Student info
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(46.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(Cyan.copy(0.3f), AccentViolet.copy(0.2f)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(item.student.fullName.firstOrNull()?.uppercase() ?: "?", color = Cyan, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.student.fullName, color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(1.dp))
                                Text("Student ID  ·  ${item.student.studentCode.ifBlank { "Not assigned" }}", color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = BorderSub)
                        Spacer(Modifier.height(14.dp))

                        // Marks + Grade row
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard(
                                label = "Marks",
                                value = "${item.result?.marksObtained?.let { formatNum(it) } ?: "-"} / ${formatNum(exam.totalMarks)}",
                                color = Cyan,
                                modifier = Modifier.weight(1f)
                            )
                            StatCard("Grade", item.result?.grade ?: "-", gradeColor, Modifier.weight(1f))
                            StatCard("Position", "#${item.position}", AccentViolet, Modifier.weight(1f))
                        }

                        Spacer(Modifier.height(12.dp))

                        // Pass/Fail badge
                        Box(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(passColor.copy(alpha = 0.12f)).border(1.dp, passColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (passFail == "PASSED") Icons.Filled.CheckCircle else Icons.Filled.Cancel, null, tint = passColor, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(passFail, color = passColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Progress bar
                        val pct = if (exam.totalMarks > 0) ((item.result?.marksObtained ?: 0.0) / exam.totalMarks).coerceIn(0.0, 1.0) else 0.0
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Score percentage", color = TextMuted, fontSize = 11.sp)
                            Text("${"%.0f".format(pct * 100)}%", color = gradeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { pct.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                            color = gradeColor,
                            trackColor = CardBgAlt,
                        )

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = BorderSub)
                        Spacer(Modifier.height(10.dp))

                        // Actions
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ResultActionButton(
                                label = "WhatsApp",
                                icon = Icons.Filled.Chat,
                                color = WAGreen,
                                onClick = {
                                    showStudentMessageDialog = null
                                    val msg = viewModel.buildStudentMessage(item, exam)
                                    sendWhatsApp(context, item.student.phone, msg)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ResultActionButton(
                                label = "SMS",
                                icon = Icons.Filled.Sms,
                                color = ElectricBlue,
                                onClick = {
                                    showStudentMessageDialog = null
                                    val msg = viewModel.buildStudentMessage(item, exam)
                                    sendSMS(context, item.student.phone, msg)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ResultActionButton(
                                label = "Share Card",
                                icon = Icons.Filled.Share,
                                color = Cyan,
                                onClick = {
                                    showStudentMessageDialog = null
                                    shareResultImage(context, item, exam, batchName, gradeColor, passColor, passFail, instituteName, institute?.profilePhotoUri)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ResultActionButton(
                                label = "Print",
                                icon = Icons.Filled.Print,
                                color = AccentViolet,
                                onClick = {
                                    showStudentMessageDialog = null
                                    printResultCard(context, item, exam, batchName, gradeColor, passColor, passFail, instituteName, institute?.profilePhotoUri)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { showStudentMessageDialog = null },
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        ) { Text("Close", color = TextMuted, fontSize = 13.sp) }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = CardBg,
            title = { Text("Delete exam?", color = TextWhite) },
            text = { Text("This will remove the exam from the Exams & Results list.", color = TextMuted) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.archiveExam(
                            examId = examId,
                            onSuccess = {
                                showDeleteConfirm = false
                                onBack()
                            },
                            onError = { message ->
                                showDeleteConfirm = false
                                scope.launch { snackbarHostState.showSnackbar(message) }
                            }
                        )
                    }
                ) { Text("Delete", color = AccentRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════
//  Helper Composables
// ═══════════════════════════════════════════════════════

@Composable
private fun ResultEntryRow(
    position: Int, student: com.batchfee.edu.data.models.StudentEntity,
    marksText: String, totalMarks: Double,
    result: com.batchfee.edu.data.models.ResultEntity?,
    onMarksChange: (String) -> Unit,
    onTap: () -> Unit
) {
    val hasResult = result != null
    val grade = result?.grade
    val gradeColor = when (grade) {
        "A+", "A", "A-" -> AccentGreen; "B", "C" -> AccentAmber; "F" -> AccentRed; else -> TextMuted
    }
    Card(
        modifier = Modifier.fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(10.dp), spotColor = if (hasResult) AccentGreen.copy(alpha = 0.08f) else Cyan.copy(alpha = 0.06f))
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardBgAlt),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$position.", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(student.fullName, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (student.studentCode.isNotBlank()) Text(student.studentCode, color = TextMuted.copy(alpha = 0.6f), fontSize = 11.sp)
            }
            if (hasResult) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(result!!.marksObtained.let { if (it == it.toLong().toDouble()) it.toLong().toString() else "%.1f".format(it) }, color = gradeColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(grade ?: "-", color = gradeColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedTextField(
                    value = marksText, onValueChange = onMarksChange,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.width(72.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                        focusedBorderColor = Cyan, unfocusedBorderColor = BorderSub,
                        focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
                        cursorColor = Cyan
                    ),
                    shape = RoundedCornerShape(8.dp),
                    placeholder = { Text(formatNum(totalMarks), color = TextMuted.copy(alpha = 0.3f), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                )
            }
        }
    }
}

@Composable
private fun StatLabel(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBgAlt),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = color, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(label, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ResultActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(58.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 5.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ResultCardInstituteLogo(logoSource: String?, instituteName: String) {
    Box(
        modifier = Modifier.size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .border(1.dp, Color.White.copy(alpha = 0.38f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (!logoSource.isNullOrBlank()) {
            AsyncImage(
                model = logoSource,
                contentDescription = "$instituteName logo",
                modifier = Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(11.dp)),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                instituteName.trim().take(2).uppercase().ifBlank { "IN" },
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ShareOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
    focusedBorderColor = ElectricBlue, unfocusedBorderColor = BorderSub,
    focusedContainerColor = CardBgAlt, unfocusedContainerColor = CardBgAlt,
    cursorColor = Cyan, focusedLabelColor = Cyan, unfocusedLabelColor = TextMuted
)

// ── Helpers ─────────────────────────────────────────
private fun formatNum(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)

private fun shareText(context: android.content.Context, text: String, title: String) {
    context.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, title
    ))
}

/**
 * Generates a compact result-sheet PDF: one table with every student on it,
 * roughly 20-25 rows per page, same header (logo, institute, exam info) on
 * every page. The Top 10 variant simply receives the first 10 rows.
 */
private fun shareResultsPdf(
    context: android.content.Context,
    items: List<StudentResultItem>,
    exam: ExamEntity,
    batchName: String,
    instituteName: String,
    instituteLogoUri: String?,
) {
    val document = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val rowsPerPage = 22
    val pages = items.chunked(rowsPerPage)
    val safeInstituteName = instituteName.trim().takeIf { it.isNotBlank() } ?: "BatchFee"
    val dateLabel = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(exam.examDateMs))

    // Colors
    val navy = android.graphics.Color.parseColor("#0B1F3A")
    val navyMid = android.graphics.Color.parseColor("#123C6A")
    val cyan = android.graphics.Color.parseColor("#22D3EE")
    val ink = android.graphics.Color.parseColor("#10233F")
    val muted = android.graphics.Color.parseColor("#64748B")
    val white = android.graphics.Color.WHITE
    val paleBlue = android.graphics.Color.parseColor("#E0F2FE")
    val paleRow = android.graphics.Color.parseColor("#F1F6FC")
    val line = android.graphics.Color.parseColor("#DCE6F2")
    val green = android.graphics.Color.parseColor("#16A34A")
    val red = android.graphics.Color.parseColor("#DC2626")

    try {
        pages.forEachIndexed { pageIndex, pageItems ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create())
            val canvas = page.canvas
            canvas.drawColor(white)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

            // ── Header band ──────────────────────────────
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(0f, 0f, pageWidth.toFloat(), 0f, navy, navyMid, android.graphics.Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), 150f, headerPaint)
            fill.color = android.graphics.Color.argb(24, 255, 255, 255)
            canvas.drawCircle(560f, 20f, 90f, fill)

            // Logo / initials
            val logoRect = RectF(24f, 28f, 110f, 114f)
            val logoBitmap = loadResultCardLogo(context, instituteLogoUri)
            canvas.save()
            canvas.clipPath(android.graphics.Path().apply { addCircle(67f, 71f, 43f, android.graphics.Path.Direction.CW) })
            fill.color = if (logoBitmap == null) cyan else white
            canvas.drawRect(logoRect, fill)
            if (logoBitmap != null) {
                canvas.drawBitmap(logoBitmap, null, logoRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            } else {
                val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy; textSize = 24f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
                canvas.drawText(safeInstituteName.take(2).uppercase(), 67f, 80f, brandPaint)
            }
            canvas.restore()
            logoBitmap?.recycle()

            val headerInst = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(210, 255, 255, 255); textSize = 17f; isFakeBoldText = true }
            canvas.drawText(fitResultCardText(safeInstituteName.uppercase(), headerInst, 300f), 128f, 48f, headerInst)
            val headerTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 27f; isFakeBoldText = true }
            canvas.drawText(fitResultCardText(exam.examName, headerTitle, 400f), 128f, 88f, headerTitle)
            val headerMeta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(180, 255, 255, 255); textSize = 15f }
            canvas.drawText(
                fitResultCardText("$batchName  •  ${exam.subject ?: "General"}  •  $dateLabel", headerMeta, 420f),
                128f, 118f, headerMeta
            )
            val headerLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textSize = 14f; isFakeBoldText = true; letterSpacing = 0.14f }
            canvas.drawText("RESULT SHEET", 128f, 141f, headerLabel)

            // ── Summary strip ───────────────────────────
            val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 14f }
            canvas.drawText(
                "Total: ${items.size} students   •   Exam marks: ${formatNum(exam.totalMarks)}   •   Pass marks: ${formatNum(exam.passingMarks)}   •   Page ${pageIndex + 1}/${pages.size}",
                24f, 180f, summaryPaint
            )

            // ── Table header ────────────────────────────
            val tableTop = 196f
            val colSl = 24f
            val colName = 66f
            val colId = 226f
            val colMarks = 320f
            val colGrade = 404f
            val colGpa = 452f
            val colPos = 502f
            val colStatus = 550f
            val rowHeight = 28f

            val theadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(0f, tableTop, pageWidth.toFloat(), tableTop + rowHeight, navy, navyMid, android.graphics.Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, tableTop, pageWidth.toFloat(), tableTop + rowHeight, theadPaint)
            val thText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 13f; isFakeBoldText = true }
            canvas.drawText("SL", colSl + 2f, tableTop + 19f, thText)
            canvas.drawText("STUDENT NAME", colName, tableTop + 19f, thText)
            canvas.drawText("STUDENT ID", colId, tableTop + 19f, thText)
            canvas.drawText("MARKS", colMarks, tableTop + 19f, thText)
            canvas.drawText("GRADE", colGrade, tableTop + 19f, thText)
            canvas.drawText("GPA", colGpa, tableTop + 19f, thText)
            canvas.drawText("POS", colPos, tableTop + 19f, thText)
            canvas.drawText("STATUS", colStatus, tableTop + 19f, thText)

            // ── Table rows ──────────────────────────────
            val rowText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 13f }
            val rowTextBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 13f; isFakeBoldText = true }
            val rowTextMuted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 11f }
            pageItems.forEachIndexed { rowIndex, item ->
                val y = tableTop + (rowIndex + 1) * rowHeight
                if (rowIndex % 2 == 1) {
                    fill.color = paleRow
                    canvas.drawRect(0f, y, pageWidth.toFloat(), y + rowHeight, fill)
                }
                val globalIndex = pageIndex * rowsPerPage + rowIndex
                val marks = item.result?.marksObtained ?: 0.0
                val passed = marks >= exam.passingMarks
                val grade = item.result?.grade ?: "-"
                val gpa = gradeToGpa(grade)
                val statusColor = if (passed) green else red

                canvas.drawText("${globalIndex + 1}", colSl + 2f, y + 19f, rowText)
                canvas.drawText(fitResultCardText(item.student.fullName, rowTextBold, 150f), colName, y + 19f, rowTextBold)
                canvas.drawText(item.student.studentCode.ifBlank { "N/A" }, colId, y + 19f, rowTextMuted)
                canvas.drawText("${formatNum(marks)}/${formatNum(exam.totalMarks)}", colMarks, y + 19f, rowText)
                canvas.drawText(grade, colGrade, y + 19f, rowTextBold)
                canvas.drawText(gpa, colGpa, y + 19f, rowText)
                canvas.drawText(if (item.position > 0) "#${item.position}" else "—", colPos, y + 19f, rowText)
                canvas.drawText(if (passed) "PASS" else "FAIL", colStatus, y + 19f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = statusColor; textSize = 13f; isFakeBoldText = true })
            }

            // ── Footer ──────────────────────────────────
            val footerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; strokeWidth = 1.5f }
            canvas.drawLine(24f, pageHeight - 46f, pageWidth - 24f, pageHeight - 46f, footerLine)
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 12f; textAlign = Paint.Align.CENTER }
            canvas.drawText("Generated by BatchFee  •  ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())}", pageWidth / 2f, pageHeight - 24f, footerPaint)

            document.finishPage(page)
        }

        val safeName = exam.examName.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "Exam" }
        val file = java.io.File(context.cacheDir, "results_${safeName}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share Results PDF"
            )
        )
    } finally {
        document.close()
    }
}

/** Maps a letter grade to the standard Bangladeshi 5.00 GPA scale. */
private fun gradeToGpa(grade: String): String = when (grade.trim().uppercase()) {
    "A+" -> "5.00"
    "A" -> "4.00"
    "A-" -> "3.50"
    "B" -> "3.00"
    "C" -> "2.00"
    "D" -> "1.00"
    "F" -> "0.00"
    else -> "—"
}

private fun sendWhatsApp(context: android.content.Context, phone: String?, msg: String) {
    try {
        val jid = phone?.replace(Regex("[+\\s-]"), "")?.let { "$it@s.whatsapp.net" }
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, msg)
            `package` = "com.whatsapp"
            if (jid != null) putExtra("jid", jid)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.let { context.startActivity(it) }
    } catch (_: Exception) {
        shareText(context, msg, "Share Result")
    }
}

private fun sendSMS(context: android.content.Context, phone: String?, msg: String) {
    try {
        val uri = Uri.parse("smsto:${phone ?: ""}")
        context.startActivity(Intent(Intent.ACTION_SENDTO, uri).apply { putExtra("sms_body", msg) })
    } catch (_: Exception) {
        shareText(context, msg, "Share via SMS")
    }
}

private fun createResultCardBitmap(
    context: android.content.Context,
    item: StudentResultItem,
    exam: com.batchfee.edu.data.models.ExamEntity,
    batchName: String,
    gradeColor: androidx.compose.ui.graphics.Color,
    passColor: androidx.compose.ui.graphics.Color,
    passFail: String,
    instituteName: String,
    instituteLogoUri: String?,
): Bitmap {
    val width = 1080
    val height = 1350
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val ink = android.graphics.Color.parseColor("#0F172A")
    val slate = android.graphics.Color.parseColor("#334155")
    val muted = android.graphics.Color.parseColor("#64748B")
    val white = android.graphics.Color.WHITE
    val softBg = android.graphics.Color.parseColor("#F8FAFC")
    val line = android.graphics.Color.parseColor("#E2E8F0")
    val cyan = android.graphics.Color.parseColor("#0891B2")
    val cyanSoft = android.graphics.Color.parseColor("#ECFEFF")
    val grade = gradeColor.toArgb()
    val pass = passColor.toArgb()
    val resultMarks = item.result?.marksObtained ?: 0.0
    val percentage = if (exam.totalMarks > 0.0) ((resultMarks / exam.totalMarks) * 100).coerceIn(0.0, 100.0) else 0.0

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    canvas.drawColor(white)

    // ── Top accent strip ──────────────────────────────────────────
    val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), 0f,
            android.graphics.Color.parseColor("#0EA5E9"), cyan, android.graphics.Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, width.toFloat(), 10f, accent)

    // ── Header (white, clean) ─────────────────────────────────────
    val safeInstituteName = instituteName.trim().takeIf { it.isNotBlank() } ?: "BatchFee"
    val logoRect = RectF(48f, 40f, 132f, 124f)
    val logoBitmap = loadResultCardLogo(context, instituteLogoUri)
    canvas.save()
    canvas.clipPath(android.graphics.Path().apply { addCircle(90f, 82f, 42f, android.graphics.Path.Direction.CW) })
    fill.color = if (logoBitmap == null) cyan else white
    canvas.drawRect(logoRect, fill)
    if (logoBitmap != null) {
        canvas.drawBitmap(logoBitmap, null, logoRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    } else {
        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 26f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        canvas.drawText(safeInstituteName.take(2).uppercase(), 90f, 93f, brandPaint)
    }
    canvas.restore()
    logoBitmap?.recycle()

    val instPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 26f; isFakeBoldText = true }
    canvas.drawText(fitResultCardText(safeInstituteName.uppercase(), instPaint, 620f), 156f, 76f, instPaint)
    val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyanSoft }
    val badgeRect = RectF(156f, 92f, 404f, 134f)
    canvas.drawRoundRect(badgeRect, 21f, 21f, badgeBg)
    val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(90, 8, 145, 178); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    canvas.drawRoundRect(badgeRect, 21f, 21f, badgeStroke)
    val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textSize = 16f; isFakeBoldText = true; letterSpacing = 0.14f }
    canvas.drawText("RESULT STATEMENT", 176f, 120f, badgePaint)

    // Exam name + meta (right aligned block)
    val examPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 40f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
    canvas.drawText(fitResultCardText(exam.examName, examPaint, 700f), width - 48f, 78f, examPaint)
    val examMeta = listOf(
        batchName,
        exam.subject?.takeIf { it.isNotBlank() } ?: "General",
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(exam.examDateMs))
    ).filter { it.isNotBlank() }.joinToString("   •   ")
    val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 20f; textAlign = Paint.Align.RIGHT }
    canvas.drawText(fitResultCardText(examMeta, metaPaint, 700f), width - 48f, 116f, metaPaint)

    // ── Divider ───────────────────────────────────────────────────
    val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; strokeWidth = 2f }
    canvas.drawLine(48f, 168f, width - 48f, 168f, divider)

    // ── Student identity ──────────────────────────────────────────
    fill.color = ink
    canvas.drawCircle(96f, 240f, 52f, fill)
    val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 46f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    canvas.drawText(item.student.fullName.trim().take(1).uppercase(), 96f, 257f, initialPaint)
    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 38f; isFakeBoldText = true }
    canvas.drawText(fitResultCardText(item.student.fullName, namePaint, 560f), 172f, 224f, namePaint)
    val idPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 21f }
    canvas.drawText("Student ID: ${item.student.studentCode.ifBlank { "N/A" }}", 172f, 262f, idPaint)

    // Status pill (right)
    val statusRect = RectF(820f, 198f, 1032f, 282f)
    fill.color = pass
    canvas.drawRoundRect(statusRect, 42f, 42f, fill)
    val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 24f; isFakeBoldText = true; textAlign = Paint.Align.CENTER; letterSpacing = 0.08f }
    canvas.drawText(passFail, statusRect.centerX(), 252f, statusPaint)

    // ── Marks + Grade (two big cards) ─────────────────────────────
    // Marks card
    val marksRect = RectF(48f, 320f, 660f, 620f)
    fill.color = softBg
    canvas.drawRoundRect(marksRect, 28f, 28f, fill)
    val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; style = Paint.Style.STROKE; strokeWidth = 2f }
    canvas.drawRoundRect(marksRect, 28f, 28f, cardStroke)
    val marksLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 20f; isFakeBoldText = true; letterSpacing = 0.12f }
    canvas.drawText("OBTAINED MARKS", 90f, 382f, marksLabel)
    val marksBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 120f; isFakeBoldText = true }
    canvas.drawText(formatNum(resultMarks), 86f, 516f, marksBig)
    val marksOut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 26f }
    canvas.drawText("out of ${formatNum(exam.totalMarks)}", 92f, 562f, marksOut)

    // Progress bar inside marks card
    val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line }
    canvas.drawRoundRect(92f, 580f, 600f, 592f, 6f, 6f, barBg)
    val barWidth = (508f * (percentage / 100.0)).toFloat()
    val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan }
    canvas.drawRoundRect(92f, 580f, 92f + barWidth.coerceAtLeast(8f), 592f, 6f, 6f, barFill)
    val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textSize = 24f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
    canvas.drawText("${"%.0f".format(percentage)}%", 600f, 606f, pctPaint)

    // Grade card
    val gradeRect = RectF(700f, 320f, 1032f, 620f)
    fill.color = grade
    canvas.drawRoundRect(gradeRect, 28f, 28f, fill)
    val gradeLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(200, 255, 255, 255); textSize = 20f; isFakeBoldText = true; letterSpacing = 0.12f }
    canvas.drawText("GRADE", 742f, 382f, gradeLabel)
    val gradeBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 120f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    canvas.drawText(item.result?.grade ?: "-", 866f, 516f, gradeBig)
    val gpaText = gradeToGpa(item.result?.grade ?: "-")
    val gpaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(210, 255, 255, 255); textSize = 24f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    canvas.drawText("GPA $gpaText", 866f, 578f, gpaPaint)

    // ── Info row ──────────────────────────────────────────────────
    val infoLabels = listOf(
        "MERIT POSITION" to if (item.position > 0) "#${item.position}" else "—",
        "PASS MARK" to formatNum(exam.passingMarks),
        "SUBJECT" to (exam.subject ?: "General"),
    )
    infoLabels.forEachIndexed { index, (label, value) ->
        val left = 48f + index * 336f
        val infoRect = RectF(left, 660f, left + 316f, 780f)
        fill.color = softBg
        canvas.drawRoundRect(infoRect, 20f, 20f, fill)
        canvas.drawRoundRect(infoRect, 20f, 20f, cardStroke)
        val lPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 15f; isFakeBoldText = true; letterSpacing = 0.10f }
        val vPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = if (label == "SUBJECT") 22f else 30f; isFakeBoldText = true }
        canvas.drawText(label, left + 24f, 700f, lPaint)
        canvas.drawText(fitResultCardText(value, vPaint, 264f), left + 24f, 752f, vPaint)
    }

    // ── Message ───────────────────────────────────────────────────
    val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (passFail == "PASSED") android.graphics.Color.parseColor("#15803D") else android.graphics.Color.parseColor("#B91C1C")
        textSize = 24f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    val message = if (passFail == "PASSED") "Congratulations on your achievement!" else "Keep learning — your next result can be stronger."
    canvas.drawText(message, width / 2f, 852f, messagePaint)

    // ── Comment box ───────────────────────────────────────────────
    val commentRect = RectF(48f, 890f, width - 48f, 1010f)
    fill.color = softBg
    canvas.drawRoundRect(commentRect, 20f, 20f, fill)
    canvas.drawRoundRect(commentRect, 20f, 20f, cardStroke)
    val commentLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textSize = 18f; isFakeBoldText = true; letterSpacing = 0.12f }
    canvas.drawText("COMMENT", 76f, 928f, commentLabel)
    val commentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = slate; textSize = 22f }
    val commentText = when {
        passFail == "PASSED" && item.position == 1 -> "Outstanding performance — top of the class!"
        passFail == "PASSED" && (item.result?.grade in listOf("A+", "A")) -> "Excellent result. Keep up the great work!"
        passFail == "PASSED" -> "Good result. A little more effort can make it even better."
        else -> "Needs improvement. Regular practice will bring better results."
    }
    canvas.drawText(fitResultCardText(commentText, commentPaint, 900f), 76f, 968f, commentPaint)

    // ── Footer ────────────────────────────────────────────────────
    val footLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; strokeWidth = 2f }
    canvas.drawLine(48f, 1060f, width - 48f, 1060f, footLine)
    val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 17f; textAlign = Paint.Align.CENTER }
    canvas.drawText("Generated by BatchFee  •  ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())}", width / 2f, 1096f, footPaint)

    return bitmap
}

private fun shareResultImage(
    context: android.content.Context,
    item: StudentResultItem,
    exam: com.batchfee.edu.data.models.ExamEntity,
    batchName: String,
    gradeColor: androidx.compose.ui.graphics.Color,
    passColor: androidx.compose.ui.graphics.Color,
    passFail: String,
    instituteName: String,
    instituteLogoUri: String?,
) {
    val bitmap = createResultCardBitmap(context, item, exam, batchName, gradeColor, passColor, passFail, instituteName, instituteLogoUri)
    try {
        val file = java.io.File(context.cacheDir, "result_${item.student.id}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share Result Card"))
    } finally {
        bitmap.recycle()
    }
}

private fun printResultCard(
    context: android.content.Context,
    item: StudentResultItem,
    exam: com.batchfee.edu.data.models.ExamEntity,
    batchName: String,
    gradeColor: androidx.compose.ui.graphics.Color,
    passColor: androidx.compose.ui.graphics.Color,
    passFail: String,
    instituteName: String,
    instituteLogoUri: String?,
) {
    val bitmap = createResultCardBitmap(context, item, exam, batchName, gradeColor, passColor, passFail, instituteName, instituteLogoUri)
    val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE) as? PrintManager
    if (printManager == null) {
        bitmap.recycle()
        android.widget.Toast.makeText(context, "Printing is not available on this device.", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val jobName = "Result - ${item.student.studentCode.ifBlank { item.student.fullName }}"
    printManager.print(
        jobName,
        ResultCardPrintAdapter(bitmap, jobName),
        PrintAttributes.Builder()
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .build(),
    )
}

private class ResultCardPrintAdapter(
    private val bitmap: Bitmap,
    private val documentName: String,
) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: android.os.Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder("$documentName.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
            .setPageCount(1)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<android.print.PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onWriteCancelled()
            return
        }
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val canvas = page.canvas
            canvas.drawColor(android.graphics.Color.WHITE)
            val maxWidth = page.info.pageWidth - 48f
            val maxHeight = page.info.pageHeight - 48f
            val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height)
            val targetWidth = bitmap.width * scale
            val targetHeight = bitmap.height * scale
            val target = RectF(
                (page.info.pageWidth - targetWidth) / 2f,
                (page.info.pageHeight - targetHeight) / 2f,
                (page.info.pageWidth + targetWidth) / 2f,
                (page.info.pageHeight + targetHeight) / 2f,
            )
            canvas.drawBitmap(bitmap, null, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            document.finishPage(page)
            FileOutputStream(destination.fileDescriptor).use { document.writeTo(it) }
            callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
        } catch (error: Exception) {
            callback.onWriteFailed(error.message)
        } finally {
            document.close()
        }
    }

    override fun onFinish() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

private fun fitResultCardText(value: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(value) <= maxWidth) return value
    val suffix = "..."
    var end = value.length
    while (end > 0 && paint.measureText(value.take(end) + suffix) > maxWidth) end -= 1
    return value.take(end) + suffix
}

private fun loadResultCardLogo(context: android.content.Context, source: String?): Bitmap? {
    val resolvedSource = FirebaseStorageImageUploadHelper.displaySource(context, source) ?: return null
    return try {
        val uri = Uri.parse(resolvedSource)
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val connection = (java.net.URL(resolvedSource).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    doInput = true
                }
                connection.inputStream.use { BitmapFactory.decodeStream(it) }.also { connection.disconnect() }
            }
            "file", "content" -> context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            else -> java.io.File(resolvedSource).inputStream().use { BitmapFactory.decodeStream(it) }
        }
    } catch (_: Exception) {
        null
    }
}

private fun shareLegacyResultImage(
    context: android.content.Context,
    item: StudentResultItem,
    exam: com.batchfee.edu.data.models.ExamEntity,
    batchName: String,
    gradeColor: androidx.compose.ui.graphics.Color,
    passColor: androidx.compose.ui.graphics.Color,
    passFail: String
) {
    val w = 800
    val h = 1050
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val bg = android.graphics.Color.parseColor("#FFFFFF")
    c.drawColor(bg)

    val ink = android.graphics.Color.parseColor("#0F172A")
    val mute = android.graphics.Color.parseColor("#64748B")
    val blue = android.graphics.Color.parseColor("#2563EB")
    val cyan = android.graphics.Color.parseColor("#0EA5E9")
    val green = android.graphics.Color.parseColor("#10B981")
    val amber = android.graphics.Color.parseColor("#F59E0B")
    val red = android.graphics.Color.parseColor("#EF4444")
    val softLine = android.graphics.Color.parseColor("#E2E8F0")
    val softBg = android.graphics.Color.parseColor("#F8FAFC")
    val gColor = gradeColor.toArgb()
    val pColor = passColor.toArgb()

    // ── Top accent bar ──
    val topBar = android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), 0f, blue, cyan, android.graphics.Shader.TileMode.CLAMP) }
    c.drawRect(0f, 0f, w.toFloat(), 8f, topBar)

    // ── Header section ──
    val white = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 34f; typeface = Typeface.DEFAULT_BOLD }
    val whiteSm = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 22f }
    val mutePaint = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mute; textSize = 22f }
    val inkPaint = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 24f }
    val inkBold = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 28f; typeface = Typeface.DEFAULT_BOLD }
    val inkTitle = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 36f; typeface = Typeface.DEFAULT_BOLD }
    val cyanBold = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textSize = 30f; typeface = Typeface.DEFAULT_BOLD }
    val blueBold = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue; textSize = 24f; typeface = Typeface.DEFAULT_BOLD }

    val headerBg = android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, 180f, blue, android.graphics.Color.parseColor("#1E40AF"), android.graphics.Shader.TileMode.CLAMP) }
    c.drawRoundRect(20f, 16f, 780f, 176f, 24f, 24f, headerBg)

    white.textSize = 28f
    c.drawText("BATCHFEE", 52f, 56f, white)
    white.textSize = 16f
    c.drawText("RESULT CARD", 52f, 84f, whiteSm)
    white.textSize = 22f
    c.drawText(exam.examName, 52f, 128f, white)
    whiteSm.textSize = 15f
    c.drawText("$batchName  ·  ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(exam.examDateMs))}", 52f, 156f, whiteSm)

    // ── Student section ──
    val stuY = 212f
    c.drawText(item.student.fullName, 44f, stuY + 24f, inkTitle)
    mutePaint.textSize = 22f
    c.drawText(item.student.studentCode.ifBlank { "" }, 44f, stuY + 50f, mutePaint)

    // ── Marks & Grade row ──
    val cardY = stuY + 86f
    // Marks card
    c.drawRoundRect(36f, cardY, 386f, cardY + 240f, 20f, 20f, android.graphics.Paint().apply { color = softBg })
    c.drawRoundRect(36f, cardY, 386f, cardY + 240f, 20f, 20f, android.graphics.Paint().apply { color = softLine; style = Paint.Style.STROKE; strokeWidth = 1.5f })
    mutePaint.textSize = 20f
    c.drawText("MARKS OBTAINED", 64f, cardY + 40f, mutePaint)
    val marksStr = "${item.result?.marksObtained?.let { formatNum(it) } ?: "-"}"
    android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue; textSize = 56f; typeface = Typeface.DEFAULT_BOLD }.let { paint ->
        c.drawText(marksStr, 64f, cardY + 100f, paint)
    }
    c.drawText("out of ${formatNum(exam.totalMarks)}", 64f, cardY + 130f, mutePaint)

    // Progress bar in marks card
    val pct = if (exam.totalMarks > 0) ((item.result?.marksObtained ?: 0.0) / exam.totalMarks).coerceIn(0.0, 1.0) else 0.0
    c.drawRoundRect(64f, cardY + 162f, 358f, cardY + 176f, 7f, 7f, android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#E2E8F0") })
    c.drawRoundRect(64f, cardY + 162f, (64f + 294f * pct.toFloat()).coerceAtLeast(64f + 7f), cardY + 176f, 7f, 7f, android.graphics.Paint().apply { color = gColor })
    mutePaint.textSize = 16f
    c.drawText("${"%.0f".format(pct * 100)}%", 358f, cardY + 192f, android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gColor; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT })

    // Grade card
    c.drawRoundRect(408f, cardY, 764f, cardY + 240f, 20f, 20f, android.graphics.Paint().apply { color = softBg })
    c.drawRoundRect(408f, cardY, 764f, cardY + 240f, 20f, 20f, android.graphics.Paint().apply { color = softLine; style = Paint.Style.STROKE; strokeWidth = 1.5f })
    mutePaint.textSize = 20f; mutePaint.textAlign = Paint.Align.LEFT
    c.drawText("GRADE", 436f, cardY + 40f, mutePaint)
    android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gColor; textSize = 64f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }.let { paint ->
        c.drawText(item.result?.grade ?: "-", 586f, cardY + 110f, paint)
    }
    mutePaint.textAlign = Paint.Align.LEFT
    mutePaint.textSize = 18f
    c.drawText("Position: #${item.position}", 436f, cardY + 168f, mutePaint)
    c.drawText(passFail, 436f, cardY + 196f, android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pColor; textSize = 22f; typeface = Typeface.DEFAULT_BOLD })

    // ── Subject / exam info ──
    val infoY = cardY + 272f
    mutePaint.textSize = 22f
    c.drawText("EXAM DETAILS", 44f, infoY + 24f, android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue; textSize = 18f; typeface = Typeface.DEFAULT_BOLD })
    c.drawText(exam.examName, 44f, infoY + 58f, inkBold)
    exam.subject?.let { c.drawText("Subject: $it", 44f, infoY + 90f, mutePaint) }
    inkPaint.textSize = 22f
    c.drawText("Total Marks: ${formatNum(exam.totalMarks)}  ·  Pass Marks: ${formatNum(exam.passingMarks)}", 44f, infoY + 120f, inkPaint)

    // ── Footer ──
    val footerY = h - 80f
    c.drawLine(60f, footerY - 8f, 740f, footerY - 8f, android.graphics.Paint().apply { color = softLine; strokeWidth = 1f })
    mutePaint.textSize = 16f; mutePaint.textAlign = Paint.Align.CENTER
    c.drawText("Generated by BatchFee  ·  ${SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault()).format(Date())}", 400f, footerY + 30f, mutePaint)

    val file = java.io.File(context.cacheDir, "result_${item.student.id}.jpg")
    java.io.FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Share Result Card"))
}

private fun Color.toArgb(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())


