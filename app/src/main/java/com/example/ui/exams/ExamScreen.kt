package com.batchfee.edu.ui.exams

import android.content.Intent
import android.net.Uri
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

    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var examName by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var totalMarks by remember { mutableStateOf("100") }
    var passingMarks by remember { mutableStateOf("40") }
    var selectedDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    var formInitialized by remember(examId) { mutableStateOf(false) }

    LaunchedEffect(examId) {
        if (examId != null) {
            viewModel.loadExamDetails(examId)
        }
    }

    LaunchedEffect(selectedExam?.id, examId) {
        val exam = selectedExam
        if (isEditMode && exam != null && exam.id == examId && !formInitialized) {
            selectedBatchId = exam.batchId
            examName = exam.examName
            subject = exam.subject.orEmpty()
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

            OutlinedTextField(
                value = examName, onValueChange = { examName = it },
                label = { Text("Exam Name *") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = subject, onValueChange = { subject = it },
                label = { Text("Subject (optional)") }, singleLine = true,
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
                                teacherName = null,
                                onSuccess = onBack,
                                onError = onError
                            )
                        } else {
                            viewModel.createExam(
                                batchId = selectedBatchId!!, examName = examName, subject = subject,
                                totalMarks = totalMarks.toDoubleOrNull() ?: 100.0,
                                passingMarks = passingMarks.toDoubleOrNull() ?: 40.0,
                                examDateMs = selectedDateMs, teacherName = null,
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
                                StatLabel("Pass", formatNum(exam.passingMarks), AccentAmber)
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

    // ── Single student message dialog ───────────────
    if (showStudentMessageDialog != null && selectedExam != null) {
        val item = showStudentMessageDialog!!
        val msg = viewModel.buildStudentMessage(item, selectedExam!!)
        Dialog(onDismissRequest = { showStudentMessageDialog = null }) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = CardBg), border = BorderStroke(1.dp, BorderSub)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Result — ${item.student.fullName}", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            showStudentMessageDialog = null
                            sendWhatsApp(context, item.student.phone, msg)
                        }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, WAGreen)) {
                            Icon(Icons.Filled.Chat, null, tint = WAGreen, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("WhatsApp", color = WAGreen, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = {
                            showStudentMessageDialog = null
                            sendSMS(context, item.student.phone, msg)
                        }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Cyan)) {
                            Icon(Icons.Filled.Sms, null, tint = Cyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("SMS", color = Cyan, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showStudentMessageDialog = null }, modifier = Modifier.fillMaxWidth()) { Text("Close", color = TextMuted) }
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

