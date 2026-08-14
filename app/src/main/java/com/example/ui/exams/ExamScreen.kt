package com.batchfee.edu.ui.exams

import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.ExamEntity
import com.batchfee.edu.domain.SessionManager
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val CardBgAlt = Color(0xFF111827)
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
fun ExamListScreen(db: AppDatabase, onBack: () -> Unit, onAddExam: () -> Unit, onNavigateToDetail: (String) -> Unit, onNavigateToPricing: () -> Unit) {
    val viewModel: ExamViewModel = viewModel(factory = ExamViewModelFactory(db))
    val exams by viewModel.exams.collectAsState()
    val batches by viewModel.batches.collectAsState()
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

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
                onClick = onAddExam,
                containerColor = Color.Transparent, contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                    .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
            ) { Icon(Icons.Default.Add, "Add", tint = Color.White) }
        }
    ) { padding ->
        if (exams.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
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
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
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
    var selectedDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var teacherTemplates by remember { mutableStateOf<List<String>>(emptyList()) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    var formInitialized by remember(examId) { mutableStateOf(false) }

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
                                selected = sel, onClick = { selectedBatchId = if (sel) null else b.id },
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
                value = examName, onValueChange = { examName = it },
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
                trailingIcon = { IconButton(onClick = { showDatePicker = true }) { Icon(Icons.Filled.CalendarToday, null, tint = Cyan) } },
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

            Spacer(Modifier.height(20.dp))
            val canSaveExam = selectedBatchId != null && examName.isNotBlank() &&
                    (totalMarks.toDoubleOrNull() ?: 0.0) > 0 &&
                    (passingMarks.toDoubleOrNull() ?: 0.0) <= (totalMarks.toDoubleOrNull() ?: 100.0)
            Box(
                modifier = Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
                    .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.3f))
                    .let { m -> if (canSaveExam) m.background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))) else m.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(14.dp)) },
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = {
                        val onError: (String) -> Unit = { message ->
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
                                onSuccess = onBack,
                                onError = onError
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(), enabled = canSaveExam,
                    colors = ButtonDefaults.textButtonColors(contentColor = if (canSaveExam) Color.White else TextMuted)
                ) { Text(if (isEditMode) "Update Exam" else "Create Exam", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
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

    var marksMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showMeritDialog by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var showStudentMessageDialog by remember { mutableStateOf<StudentResultItem?>(null) }
    var showExamMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
                    val canSave = marksMap.values.any { (it.toDoubleOrNull() ?: -1.0) >= 0 }
                    Box(
                        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                            .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.3f))
                            .let { m -> if (canSave) m.background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))) else m.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(14.dp)) },
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(
                            onClick = {
                                val marksList = marksMap.map { it.key to (it.value.toDoubleOrNull() ?: 0.0) }.filter { it.second > 0 }
                                viewModel.saveResults(examId, selectedExam?.batchId ?: "", marksList,
                                    onSuccess = { scope.launch { snackbarHostState.showSnackbar("Results saved!") } },
                                    onError = { scope.launch { snackbarHostState.showSnackbar(it) } }
                                )
                            },
                            modifier = Modifier.fillMaxSize(), enabled = canSave,
                            colors = ButtonDefaults.textButtonColors(contentColor = if (canSave) Color.White else TextMuted)
                        ) { Text("Save All Marks", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
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
        val meritMsg = viewModel.buildMeritMessage(exam)
        Dialog(onDismissRequest = { showShareSheet = false }) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = CardBg), border = BorderStroke(1.dp, BorderSub)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Share Results", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(exam.examName, color = TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    ShareOption("Merit List (All)", Icons.Filled.ListAlt, WAGreen) {
                        showShareSheet = false
                        shareText(context, meritMsg, "Merit List")
                    }
                    Spacer(Modifier.height(8.dp))
                    ShareOption("Top 10", Icons.Filled.Leaderboard, AccentViolet) {
                        showShareSheet = false
                        shareText(context, viewModel.buildMeritMessage(exam, false), "Top 10")
                    }
                    Spacer(Modifier.height(8.dp))
                    ShareOption("Group Message (All Students)", Icons.Filled.Forum, Cyan) {
                        showShareSheet = false
                        // Build a message per student and let user share
                        val allMsg = studentResults.filter { it.result != null }
                            .joinToString("\n\n") { viewModel.buildStudentMessage(it, exam) }
                        shareText(context, allMsg, "Individual Results")
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { showShareSheet = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = TextMuted) }
                }
            }
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
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column {
                    // Header
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            .background(Brush.linearGradient(listOf(ElectricBlue, AccentViolet)))
                            .padding(20.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("RESULT CARD", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(exam.examName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (exam.subject != null) Text(exam.subject, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(batchName, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                Text(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(exam.examDateMs)), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                    }

                    // Student info
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(Cyan.copy(0.3f), AccentViolet.copy(0.2f)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(item.student.fullName.firstOrNull()?.uppercase() ?: "?", color = Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(item.student.fullName, color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.student.studentCode.ifBlank { "No ID" }, color = TextMuted, fontSize = 13.sp)
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = BorderSub)
                        Spacer(Modifier.height(16.dp))

                        // Marks + Grade row
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            StatCard("Marks", "${item.result?.marksObtained?.let { formatNum(it) } ?: "-"} / ${formatNum(exam.totalMarks)}", Cyan)
                            StatCard("Grade", item.result?.grade ?: "-", gradeColor)
                            StatCard("Position", "#${item.position}", AccentViolet)
                        }

                        Spacer(Modifier.height(14.dp))

                        // Pass/Fail badge
                        Box(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(passColor.copy(alpha = 0.12f)).border(1.dp, passColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (passFail == "PASSED") Icons.Filled.CheckCircle else Icons.Filled.Cancel, null, tint = passColor, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(passFail, color = passColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // Progress bar
                        val pct = if (exam.totalMarks > 0) ((item.result?.marksObtained ?: 0.0) / exam.totalMarks).coerceIn(0.0, 1.0) else 0.0
                        LinearProgressIndicator(
                            progress = { pct.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = gradeColor,
                            trackColor = CardBgAlt,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0", color = TextMuted, fontSize = 10.sp)
                            Text("${"%.0f".format(pct * 100)}%", color = gradeColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(formatNum(exam.totalMarks), color = TextMuted, fontSize = 10.sp)
                        }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = BorderSub)
                        Spacer(Modifier.height(12.dp))

                        // Actions
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    showStudentMessageDialog = null
                                    shareResultImage(context, item, exam, batchName, gradeColor, passColor, passFail)
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Cyan.copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Filled.Share, null, tint = Cyan, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share", color = Cyan, fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    showStudentMessageDialog = null
                                    printResultCard(context, item, exam, batchName, gradeColor, passColor, passFail)
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.55f))
                            ) {
                                Icon(Icons.Filled.Print, null, tint = ElectricBlue, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Print", color = ElectricBlue, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    showStudentMessageDialog = null
                                    val msg = viewModel.buildStudentMessage(item, exam)
                                    sendWhatsApp(context, item.student.phone, msg)
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, WAGreen)
                            ) {
                                Icon(Icons.Filled.Chat, null, tint = WAGreen, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("WhatsApp", color = WAGreen, fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { showStudentMessageDialog = null },
                            modifier = Modifier.fillMaxWidth()
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
private fun StatCard(label: String, value: String, color: Color) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBgAlt),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(label, color = TextMuted, fontSize = 11.sp)
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
    item: StudentResultItem,
    exam: com.batchfee.edu.data.models.ExamEntity,
    batchName: String,
    gradeColor: androidx.compose.ui.graphics.Color,
    passColor: androidx.compose.ui.graphics.Color,
    passFail: String,
): Bitmap {
    val width = 1080
    val height = 1350
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val navy = android.graphics.Color.parseColor("#0B1F3A")
    val navyMid = android.graphics.Color.parseColor("#123C6A")
    val cyan = android.graphics.Color.parseColor("#22D3EE")
    val ink = android.graphics.Color.parseColor("#10233F")
    val muted = android.graphics.Color.parseColor("#64748B")
    val pale = android.graphics.Color.parseColor("#F6F9FF")
    val paleBlue = android.graphics.Color.parseColor("#E0F2FE")
    val line = android.graphics.Color.parseColor("#DCE6F2")
    val white = android.graphics.Color.WHITE
    val grade = gradeColor.toArgb()
    val pass = passColor.toArgb()
    val resultMarks = item.result?.marksObtained ?: 0.0
    val percentage = if (exam.totalMarks > 0.0) ((resultMarks / exam.totalMarks) * 100).coerceIn(0.0, 100.0) else 0.0

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    canvas.drawColor(pale)
    fill.color = paleBlue
    canvas.drawCircle(width * 0.94f, 410f, 260f, fill)
    fill.color = android.graphics.Color.parseColor("#DBEAFE")
    canvas.drawCircle(90f, 1060f, 190f, fill)

    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            navy, navyMid, android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRoundRect(28f, 28f, width - 28f, 282f, 34f, 34f, headerPaint)
    fill.color = android.graphics.Color.argb(26, 255, 255, 255)
    canvas.drawCircle(900f, 75f, 185f, fill)
    canvas.drawCircle(1000f, 240f, 120f, fill)

    val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 24f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    fill.color = cyan
    canvas.drawCircle(82f, 82f, 30f, fill)
    canvas.drawText("BF", 82f, 90f, brandPaint)
    val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 18f; isFakeBoldText = true; letterSpacing = 0.10f }
    canvas.drawText("RESULT STATEMENT", 130f, 70f, badgePaint)
    val headerSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(185, 255, 255, 255); textSize = 18f }
    canvas.drawText("BatchFee Academic Record", 130f, 101f, headerSmall)
    val examPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 34f; isFakeBoldText = true }
    canvas.drawText(fitResultCardText(exam.examName, examPaint, 780f), 66f, 173f, examPaint)
    val examMeta = listOf(batchName, SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(exam.examDateMs))).filter { it.isNotBlank() }.joinToString("  |  ")
    canvas.drawText(fitResultCardText(examMeta, headerSmall, 760f), 66f, 212f, headerSmall)

    val identityRect = RectF(54f, 232f, width - 54f, 422f)
    fill.color = white
    canvas.drawRoundRect(identityRect, 26f, 26f, fill)
    val identityStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; style = Paint.Style.STROKE; strokeWidth = 2f }
    canvas.drawRoundRect(identityRect, 26f, 26f, identityStroke)
    fill.color = navy
    canvas.drawCircle(132f, 327f, 49f, fill)
    val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 44f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    canvas.drawText(item.student.fullName.trim().take(1).uppercase(), 132f, 343f, initialPaint)
    val studentNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 36f; isFakeBoldText = true }
    canvas.drawText(fitResultCardText(item.student.fullName, studentNamePaint, 590f), 210f, 313f, studentNamePaint)
    val studentMetaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 20f; isFakeBoldText = true; letterSpacing = 0.05f }
    canvas.drawText("STUDENT ID  |  ${item.student.studentCode.ifBlank { "N/A" }}", 210f, 348f, studentMetaPaint)
    val statusRect = RectF(794f, 291f, 982f, 360f)
    fill.color = pass
    canvas.drawRoundRect(statusRect, 35f, 35f, fill)
    val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 19f; isFakeBoldText = true; textAlign = Paint.Align.CENTER; letterSpacing = 0.07f }
    canvas.drawText(passFail, statusRect.centerX(), 334f, statusPaint)

    val scoreRect = RectF(54f, 465f, width - 54f, 900f)
    val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, scoreRect.top, scoreRect.right, scoreRect.bottom, navy, navyMid, android.graphics.Shader.TileMode.CLAMP)
    }
    canvas.drawRoundRect(scoreRect, 30f, 30f, scorePaint)
    fill.color = android.graphics.Color.argb(25, 255, 255, 255)
    canvas.drawCircle(scoreRect.right - 70f, scoreRect.top + 70f, 120f, fill)
    canvas.drawCircle(scoreRect.right - 150f, scoreRect.bottom - 25f, 170f, fill)
    val scoreLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(190, 255, 255, 255); textSize = 18f; isFakeBoldText = true; letterSpacing = 0.12f }
    canvas.drawText("FINAL SCORE", 100f, 527f, scoreLabelPaint)
    val scoreNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 104f; isFakeBoldText = true }
    canvas.drawText(formatNum(resultMarks), 98f, 648f, scoreNumberPaint)
    val outOfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(190, 255, 255, 255); textSize = 23f }
    canvas.drawText("out of ${formatNum(exam.totalMarks)} marks", 103f, 687f, outOfPaint)
    val progressBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(45, 255, 255, 255) }
    canvas.drawRoundRect(102f, 728f, 690f, 746f, 9f, 9f, progressBg)
    val progressWidth = (588f * (percentage / 100.0)).toFloat()
    val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan }
    canvas.drawRoundRect(102f, 728f, 102f + progressWidth.coerceAtLeast(8f), 746f, 9f, 9f, progressPaint)
    val percentagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textSize = 22f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
    canvas.drawText("${"%.0f".format(percentage)}%", 690f, 786f, percentagePaint)
    val gradeCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = grade }
    canvas.drawCircle(843f, 659f, 100f, gradeCirclePaint)
    val gradePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 72f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    canvas.drawText(item.result?.grade ?: "-", 843f, 682f, gradePaint)
    val gradeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(210, 255, 255, 255); textSize = 15f; isFakeBoldText = true; textAlign = Paint.Align.CENTER; letterSpacing = 0.10f }
    canvas.drawText("GRADE", 843f, 817f, gradeLabelPaint)

    val statLabels = listOf(
        "POSITION" to if (item.position > 0) "#${item.position}" else "—",
        "PASS MARK" to formatNum(exam.passingMarks),
        "SUBJECT" to (exam.subject ?: "General"),
    )
    statLabels.forEachIndexed { index, (label, value) ->
        val left = 54f + index * 326f
        val statRect = RectF(left, 944f, left + 300f, 1058f)
        fill.color = white
        canvas.drawRoundRect(statRect, 20f, 20f, fill)
        canvas.drawRoundRect(statRect, 20f, 20f, identityStroke)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 14f; isFakeBoldText = true; letterSpacing = 0.10f }
        val valueStatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = if (label == "SUBJECT") 20f else 28f; isFakeBoldText = true }
        canvas.drawText(label, left + 22f, 978f, labelPaint)
        canvas.drawText(fitResultCardText(value, valueStatPaint, 250f), left + 22f, 1025f, valueStatPaint)
    }

    val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (passFail == "PASSED") android.graphics.Color.parseColor("#15803D") else android.graphics.Color.parseColor("#B91C1C"); textSize = 23f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    val message = if (passFail == "PASSED") "Congratulations on your achievement!" else "Keep learning — your next result can be stronger."
    canvas.drawText(message, width / 2f, 1135f, messagePaint)
    val footerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; strokeWidth = 2f }
    canvas.drawLine(92f, 1193f, width - 92f, 1193f, footerLine)
    val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 17f; textAlign = Paint.Align.CENTER }
    canvas.drawText("Verified academic record  |  Generated by BatchFee", width / 2f, 1235f, footerPaint)
    canvas.drawText(SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date()), width / 2f, 1265f, footerPaint)

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
) {
    val bitmap = createResultCardBitmap(item, exam, batchName, gradeColor, passColor, passFail)
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
) {
    val bitmap = createResultCardBitmap(item, exam, batchName, gradeColor, passColor, passFail)
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


