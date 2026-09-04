package com.batchfee.edu.ui.exams

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.FinalExamMarksEntity
import com.batchfee.edu.data.models.FinalExamSubjectEntity
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.domain.SessionManager
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

// ── Shared palette ────────────────────────────────────────────
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

/** Mutable draft used while building a subject config in the create form. */
private data class SubjectDraft(
    val name: String = "",
    val fullMarks: String = "",
    val passMarks: String = "",
    val mcqFullMarks: String = "",
    val cqFullMarks: String = "",
    val practicalFullMarks: String = "",
    val mcqPassMarks: String = "",
    val cqPassMarks: String = "",
    val practicalPassMarks: String = "",
    val useMcq: Boolean = true,
    val useCq: Boolean = true,
    val usePractical: Boolean = false,
    val totalOnly: Boolean = false,
    val assignedStaffId: String? = null
)

/** Standard school & college level subjects across Bangladesh (all boards). */
private val BANGLADESH_SUBJECTS = listOf(
    "Bangla", "English", "Mathematics", "General Mathematics", "Higher Mathematics",
    "Physics", "Chemistry", "Biology", "Science", "General Science",
    "Bangladesh Studies", "Bangladesh & Global Studies", "ICT", "Information & Communication Technology",
    "Accounting", "Finance & Banking", "Business Entrepreneurship", "Economics",
    "Geography", "History", "History & World Civilization", "Civics", "Civics & Citizenship",
    "Social Science", "Islamic Studies", "Islam & Moral Education", "Hindu Religion", "Buddhist Religion", "Christian Religion",
    "Arabic", "Urdu", "Sanskrit", "Pali", "English Literature",
    "Statistics", "Psychology", "Sociology", "Logic", "Philosophy",
    "Agriculture", "Home Science", "Art & Craft", "Music", "Physical Education",
    "Career Education", "Computer Studies", "Programming", "Data Science", "Robotics",
    "Environmental Science", "Engineering Drawing", "Electrical Engineering", "Mechanical Engineering", "Civil Engineering",
    "Textile", "Food & Nutrition", "Tourism & Hospitality", "Marketing", "Management"
)

/** Standard school & college level exams used across Bangladesh. */
private val BANGLADESH_EXAM_NAMES = listOf(
    "Annual Examination",
    "Half Yearly Examination",
    "First Term Examination",
    "Second Term Examination",
    "Final Examination",
    "Class Test",
    "Weekly Test",
    "Monthly Test",
    "Model Test",
    "Pre-Test Examination",
    "Test Examination",
    "JSC Examination",
    "JDC Examination",
    "SSC Examination",
    "Dakhil Examination",
    "HSC Examination",
    "Alim Examination",
    "PEC Examination",
    "Admission Test",
    "Semester Final Examination"
)

private fun FinalSubjectView.componentsLabel(): String {
    val comps = subject.components.split(",").map { it.trim() }.filter { it.isNotBlank() }
    return when {
        comps.isEmpty() || comps == listOf("total_only") -> "Total Only"
        else -> comps.joinToString(" + ") { it.uppercase() }
    }
}

// ═══════════════════════════════════════════════════════════════
//  FinalExamListScreen
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalExamListScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onCreateExam: () -> Unit,
    onOpenExam: (String) -> Unit
) {
    val viewModel: FinalExamViewModel = viewModel(factory = FinalExamViewModelFactory(db))
    val exams by viewModel.exams.collectAsState()
    val context = LocalContext.current
    val isOwner = SessionManager.isAdmin()
    var progress by remember { mutableStateOf<Map<String, Pair<Int, Int>>>(emptyMap()) }

    LaunchedEffect(Unit) { viewModel.loadExams() }
    LaunchedEffect(exams) {
        val dao = db.finalExamDao()
        progress = exams.associate { exam ->
            val approved = runCatching { dao.countApprovedSubjects(exam.id) }.getOrDefault(0)
            val total = runCatching { dao.getSubjectsOnce(exam.id).size }.getOrDefault(0)
            exam.id to (approved to total)
        }
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Final Exams", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        },
        floatingActionButton = {
            if (isOwner) {
                FloatingActionButton(
                    onClick = onCreateExam,
                    containerColor = Cyan,
                    contentColor = BgColor
                ) { Icon(Icons.Filled.Add, "Create Final Exam") }
            }
        }
    ) { padding ->
        if (exams.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(Cyan.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.School, null, tint = Cyan, modifier = Modifier.size(32.dp)) }
                    Spacer(Modifier.height(14.dp))
                    Text("No final exams yet", color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (isOwner) "Tap + to create your first final exam" else "Your institute owner hasn't created a final exam yet",
                        color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(exams, key = { it.id }) { exam ->
                    val (statusColor, statusLabel) = when (exam.status) {
                        "published" -> AccentGreen to "Published"
                        "draft" -> TextMuted to "Draft"
                        else -> AccentAmber to "In Progress"
                    }
                    val (approvedSubs, totalSubs) = progress[exam.id] ?: (0 to 0)
                    val progressFraction = if (totalSubs > 0) approvedSubs.toFloat() / totalSubs.toFloat() else 0f
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenExam(exam.id) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, BorderSub)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
                                        .background(Brush.linearGradient(listOf(ElectricBlue, Cyan))),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Filled.School, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(exam.examName, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(3.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            "Created ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(exam.createdAtMs))}",
                                            color = TextMuted, fontSize = 12.sp
                                        )
                                        Box(
                                            Modifier.clip(RoundedCornerShape(999.dp)).background(Cyan.copy(alpha = 0.12f))
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("$totalSubs subjects", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Box(
                                    Modifier.clip(RoundedCornerShape(999.dp)).background(statusColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(statusLabel, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (totalSubs > 0) {
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(999.dp))
                                            .background(BorderSub)
                                    ) {
                                        Box(
                                            Modifier.fillMaxHeight().fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(Brush.horizontalGradient(listOf(ElectricBlue, AccentGreen)))
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "$approvedSubs/$totalSubs approved",
                                        color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  CreateFinalExamScreen
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateFinalExamScreen(db: AppDatabase, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val viewModel: FinalExamViewModel = viewModel(factory = FinalExamViewModelFactory(db))
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var examName by remember { mutableStateOf("") }
    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var batches by remember { mutableStateOf<List<com.batchfee.edu.data.models.BatchEntity>>(emptyList()) }
    var staffList by remember { mutableStateOf<List<StaffEntity>>(emptyList()) }
    var showExamNameDropdown by remember { mutableStateOf(false) }

    // Subject configs being built — mutableStateListOf so add/remove recomposes.
    val subjectDrafts = remember { mutableStateListOf<SubjectDraft>() }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var examFeeText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val instId = SessionManager.currentInstituteId.value
        batches = instId?.let { db.batchDao().getBatchesByInstituteOnce(it) }.orEmpty()
            .filter { it.status == "active" }
        staffList = instId?.let { db.staffDao().getStaffByInstituteAsList(it) }.orEmpty()
            .filter { it.status == "active" }
        if (subjectDrafts.isEmpty()) subjectDrafts.add(SubjectDraft())
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Create Final Exam", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("Final Exam Name", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Box {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg)
                        .border(1.dp, if (showExamNameDropdown) Cyan else BorderSub, RoundedCornerShape(12.dp))
                        .clickable { showExamNameDropdown = true }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.School, null, tint = Cyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        examName.ifBlank { "Select exam name" },
                        color = if (examName.isBlank()) TextMuted else TextWhite,
                        fontSize = 15.sp,
                        fontWeight = if (examName.isBlank()) FontWeight.Normal else FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("▾", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(
                    expanded = showExamNameDropdown,
                    onDismissRequest = { showExamNameDropdown = false },
                    containerColor = CardBgAlt,
                    modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 420.dp)
                ) {
                    BANGLADESH_EXAM_NAMES.forEach { name ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    name,
                                    color = if (name == examName) Cyan else TextWhite,
                                    fontWeight = if (name == examName) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                examName = name
                                showExamNameDropdown = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = examName,
                onValueChange = { examName = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Or type / edit the name (e.g. Annual Final Exam 2026)", color = TextMuted) },
                label = { Text("Edit name", fontSize = 11.sp) },
                colors = feTextFieldColors(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))
            Text("Select Batch", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                batches.take(6).forEach { batch ->
                    val selected = selectedBatchId == batch.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) Cyan.copy(alpha = 0.18f) else CardBgAlt)
                            .border(1.dp, if (selected) Cyan else BorderSub, RoundedCornerShape(999.dp))
                            .clickable { selectedBatchId = batch.id }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(batch.name, color = if (selected) Cyan else TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Subjects & Marks Configuration", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { subjectDrafts.add(SubjectDraft()) }) {
                    Icon(Icons.Filled.Add, null, tint = Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Subject", color = Cyan, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))

            subjectDrafts.forEachIndexed { index, draft ->
                FinalSubjectConfigCard(
                    index = index,
                    draft = draft,
                    staffList = staffList,
                    onNameChange = { subjectDrafts[index] = draft.copy(name = it) },
                    onFullMarksChange = { subjectDrafts[index] = draft.copy(fullMarks = it) },
                    onPassMarksChange = { subjectDrafts[index] = draft.copy(passMarks = it) },
                    onMcqFullMarksChange = { subjectDrafts[index] = draft.copy(mcqFullMarks = it) },
                    onCqFullMarksChange = { subjectDrafts[index] = draft.copy(cqFullMarks = it) },
                    onPracticalFullMarksChange = { subjectDrafts[index] = draft.copy(practicalFullMarks = it) },
                    onMcqPassMarksChange = { subjectDrafts[index] = draft.copy(mcqPassMarks = it) },
                    onCqPassMarksChange = { subjectDrafts[index] = draft.copy(cqPassMarks = it) },
                    onPracticalPassMarksChange = { subjectDrafts[index] = draft.copy(practicalPassMarks = it) },
                    onComponentToggle = { component ->
                        var next = draft
                        when (component) {
                            "mcq" -> next = next.copy(useMcq = !next.useMcq, totalOnly = false)
                            "cq" -> next = next.copy(useCq = !next.useCq, totalOnly = false)
                            "practical" -> next = next.copy(usePractical = !next.usePractical, totalOnly = false)
                            "total_only" -> {
                                next = if (next.totalOnly) {
                                    next.copy(totalOnly = false, useMcq = true, useCq = true)
                                } else {
                                    next.copy(totalOnly = true, useMcq = false, useCq = false, usePractical = false)
                                }
                            }
                        }
                        subjectDrafts[index] = next
                    },
                    onStaffChange = { subjectDrafts[index] = draft.copy(assignedStaffId = it) },
                    onRemove = {
                        if (subjectDrafts.size > 1) subjectDrafts.removeAt(index)
                    }
                )
                Spacer(Modifier.height(10.dp))
            }

            errorMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            // Optional exam fee (same as regular exam fee flow)
            Spacer(Modifier.height(16.dp))
            Text("Exam Fee (optional)", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = examFeeText,
                onValueChange = { new ->
                    if (new.length <= 8 && (new.all { it.isDigit() } || (new.count { it == '.' } <= 1 && new.all { it.isDigit() || it == '.' }))) {
                        examFeeText = new
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("0 = no exam fee", color = TextMuted) },
                label = { Text("Exam fee per student (BDT)", fontSize = 11.sp) },
                colors = feTextFieldColors(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                    .clickable(enabled = !saving) {
                        if (saving) return@clickable
                        val configs = subjectDrafts.mapNotNull { draft ->
                            if (draft.name.isBlank()) return@mapNotNull null
                            val components = when {
                                draft.totalOnly -> listOf("total_only")
                                else -> buildList {
                                    if (draft.useMcq) add("mcq")
                                    if (draft.useCq) add("cq")
                                    if (draft.usePractical) add("practical")
                                }
                            }
                            val mcqFull = draft.mcqFullMarks.toDoubleOrNull() ?: 0.0
                            val cqFull = draft.cqFullMarks.toDoubleOrNull() ?: 0.0
                            val practicalFull = draft.practicalFullMarks.toDoubleOrNull() ?: 0.0
                            val computedTotal = when {
                                draft.totalOnly -> draft.fullMarks.toDoubleOrNull() ?: 0.0
                                else -> mcqFull + cqFull + practicalFull
                            }
                            FinalExamViewModel.SubjectConfig(
                                subjectName = draft.name.trim(),
                                fullMarks = computedTotal,
                                passMarks = draft.passMarks.toDoubleOrNull() ?: 0.0,
                                components = components,
                                mcqFullMarks = mcqFull,
                                cqFullMarks = cqFull,
                                practicalFullMarks = practicalFull,
                                mcqPassMarks = draft.mcqPassMarks.toDoubleOrNull() ?: 0.0,
                                cqPassMarks = draft.cqPassMarks.toDoubleOrNull() ?: 0.0,
                                practicalPassMarks = draft.practicalPassMarks.toDoubleOrNull() ?: 0.0,
                                assignedStaffId = draft.assignedStaffId
                            )
                        }
                        saving = true
                        viewModel.createExam(
                            examName = examName,
                            batchId = selectedBatchId ?: "",
                            subjectConfigs = configs,
                            examFeeAmount = examFeeText.toDoubleOrNull() ?: 0.0,
                            onSuccess = { examId ->
                                saving = false
                                onCreated(examId)
                            },
                            onError = {
                                saving = false
                                errorMessage = it
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (saving) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text("Create Final Exam", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FinalSubjectConfigCard(
    index: Int,
    draft: SubjectDraft,
    staffList: List<StaffEntity>,
    onNameChange: (String) -> Unit,
    onFullMarksChange: (String) -> Unit,
    onPassMarksChange: (String) -> Unit,
    onMcqFullMarksChange: (String) -> Unit,
    onCqFullMarksChange: (String) -> Unit,
    onPracticalFullMarksChange: (String) -> Unit,
    onMcqPassMarksChange: (String) -> Unit,
    onCqPassMarksChange: (String) -> Unit,
    onPracticalPassMarksChange: (String) -> Unit,
    onComponentToggle: (String) -> Unit,
    onStaffChange: (String?) -> Unit,
    onRemove: () -> Unit
) {
    var staffDropdownOpen by remember { mutableStateOf(false) }
    var subjectDropdownOpen by remember { mutableStateOf(false) }
    var marksExpanded by remember { mutableStateOf(true) }

    val autoTotal = (draft.mcqFullMarks.toDoubleOrNull() ?: 0.0) +
        (draft.cqFullMarks.toDoubleOrNull() ?: 0.0) +
        (draft.practicalFullMarks.toDoubleOrNull() ?: 0.0)
    val componentsSummary = when {
        draft.totalOnly -> "Total Only"
        else -> buildList {
            if (draft.useMcq) add("MCQ")
            if (draft.useCq) add("CQ")
            if (draft.usePractical) add("Practical")
        }.joinToString("+").ifBlank { "—" }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(14.dp)) {
            // ── Header: serial badge + subject name + component summary ──
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(ElectricBlue, Cyan))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${index + 1}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        draft.name.ifBlank { "Untitled subject" },
                        color = TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(AccentViolet.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(componentsSummary, color = AccentViolet, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        if (autoTotal > 0 && !draft.totalOnly) {
                            Text("${formatMarks(autoTotal)} marks", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        } else if (draft.totalOnly && draft.fullMarks.isNotBlank()) {
                            Text("${formatMarks(draft.fullMarks.toDoubleOrNull() ?: 0.0)} marks", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Delete, "Remove", tint = AccentRed, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            // Subject dropdown + editable field
            Box {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBgAlt)
                        .border(1.dp, if (subjectDropdownOpen) Cyan else BorderSub, RoundedCornerShape(12.dp))
                        .clickable { subjectDropdownOpen = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Book, null, tint = ElectricBlue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        draft.name.ifBlank { "Select subject from list" },
                        color = if (draft.name.isBlank()) TextMuted else TextWhite,
                        fontSize = 14.sp,
                        fontWeight = if (draft.name.isBlank()) FontWeight.Normal else FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("▾", color = Cyan, fontSize = 14.sp)
                }
                DropdownMenu(
                    expanded = subjectDropdownOpen,
                    onDismissRequest = { subjectDropdownOpen = false },
                    containerColor = CardBgAlt,
                    modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 380.dp)
                ) {
                    BANGLADESH_SUBJECTS.forEach { subj ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    subj,
                                    color = if (subj == draft.name) Cyan else TextWhite,
                                    fontWeight = if (subj == draft.name) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                onNameChange(subj)
                                subjectDropdownOpen = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.name,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Or type / edit subject name", color = TextMuted) },
                label = { Text("Edit subject", fontSize = 11.sp) },
                colors = feTextFieldColors(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            // ── Collapsible marks configuration ──
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { marksExpanded = !marksExpanded }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (marksExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                    null, tint = Cyan, modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Marks Configuration", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (autoTotal > 0) "Total ${formatMarks(autoTotal)}" else "Not set",
                    color = TextMuted, fontSize = 11.sp
                )
            }

            if (marksExpanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ComponentChip("MCQ", draft.useMcq, ElectricBlue) { onComponentToggle("mcq") }
                    ComponentChip("CQ", draft.useCq, AccentViolet) { onComponentToggle("cq") }
                    ComponentChip("Practical", draft.usePractical, AccentGreen) { onComponentToggle("practical") }
                    ComponentChip("Total Only", draft.totalOnly, AccentAmber) { onComponentToggle("total_only") }
                }

                // Per-component full marks + per-component pass marks
                if (!draft.totalOnly && (draft.useMcq || draft.useCq || draft.usePractical)) {
                    Spacer(Modifier.height(10.dp))
                    if (draft.useMcq) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MarksField(draft.mcqFullMarks, onMcqFullMarksChange, "MCQ Full", ElectricBlue)
                            MarksField(draft.mcqPassMarks, onMcqPassMarksChange, "MCQ Pass", AccentAmber)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (draft.useCq) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MarksField(draft.cqFullMarks, onCqFullMarksChange, "CQ Full", AccentViolet)
                            MarksField(draft.cqPassMarks, onCqPassMarksChange, "CQ Pass", AccentAmber)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (draft.usePractical) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MarksField(draft.practicalFullMarks, onPracticalFullMarksChange, "Practical Full", AccentGreen)
                            MarksField(draft.practicalPassMarks, onPracticalPassMarksChange, "Practical Pass", AccentAmber)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarksField(draft.passMarks, onPassMarksChange, "Overall Pass", AccentAmber)
                        Box(
                            Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(10.dp))
                                .background(CardHi).border(1.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Column {
                                Text("TOTAL (auto)", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (autoTotal > 0) formatMarks(autoTotal) else "—",
                                    color = Cyan,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else if (draft.totalOnly) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarksField(draft.fullMarks, onFullMarksChange, "Total Marks", ElectricBlue)
                        MarksField(draft.passMarks, onPassMarksChange, "Pass Marks", AccentAmber)
                    }
                }
            }

            // ── Teacher assignment with avatar ──
            Spacer(Modifier.height(10.dp))
            Text("Assign Teacher/Staff (subject access)", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Box {
                val selected = staffList.find { it.id == draft.assignedStaffId }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(10.dp))
                        .clickable { staffDropdownOpen = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selected != null) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Cyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(staffInitials(selected.fullName), color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(BorderSub),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Person, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        selected?.fullName ?: "Select teacher/staff",
                        color = if (selected != null) TextWhite else TextMuted,
                        fontSize = 13.sp,
                        fontWeight = if (selected != null) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("▾", color = Cyan, fontSize = 14.sp)
                }
                DropdownMenu(
                    expanded = staffDropdownOpen,
                    onDismissRequest = { staffDropdownOpen = false },
                    containerColor = CardBgAlt
                ) {
                    staffList.forEach { staff ->
                        DropdownMenuItem(
                            text = { Text(staff.fullName, color = TextWhite) },
                            leadingIcon = {
                                Box(
                                    Modifier.size(24.dp).clip(CircleShape).background(Cyan.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(staffInitials(staff.fullName), color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            },
                            onClick = {
                                onStaffChange(staff.id)
                                staffDropdownOpen = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("None (owner only)", color = TextMuted) },
                        onClick = {
                            onStaffChange(null)
                            staffDropdownOpen = false
                        }
                    )
                }
            }
        }
    }
}

/** Compact marks input with a colored accent label. Weight comes from the parent row. */
@Composable
private fun RowScope.MarksField(value: String, onValueChange: (String) -> Unit, label: String, accent: Color) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.weight(1f),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(4.dp))
                Text(label, fontSize = 10.sp)
            }
        },
        colors = feTextFieldColors(),
        shape = RoundedCornerShape(10.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

private fun staffInitials(name: String): String =
    name.split(" ").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("")

@Composable
private fun ComponentChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (selected) color.copy(alpha = 0.2f) else CardBgAlt)
            .border(1.dp, if (selected) color else BorderSub, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (selected) color else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ═══════════════════════════════════════════════════════════════
//  FinalExamDetailScreen
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalExamDetailScreen(
    db: AppDatabase,
    examId: String,
    onBack: () -> Unit,
    onOpenMarks: (String, String) -> Unit,
    onOpenResults: (String) -> Unit
) {
    val viewModel: FinalExamViewModel = viewModel(factory = FinalExamViewModelFactory(db))
    val exam by viewModel.selectedExam.collectAsState()
    val subjects by viewModel.subjects.collectAsState()
    val marks by viewModel.marks.collectAsState()
    val results by viewModel.results.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isOwner = SessionManager.isAdmin()
    val currentUserId = SessionManager.currentUserId.value

    var showEditMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(examId) { viewModel.loadExam(examId) }

    val mySubjects = if (isOwner) subjects else subjects.filter { it.subject.assignedStaffId == currentUserId }
    var batchName by remember { mutableStateOf("Batch") }
    LaunchedEffect(exam) {
        val instId = SessionManager.currentInstituteId.value
        val e = exam
        if (instId != null && e != null) {
            batchName = db.batchDao().getBatchesByInstituteOnce(instId).find { it.id == e.batchId }?.name ?: "Batch"
        }
    }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(exam?.examName ?: "Final Exam", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                actions = {
                    if (isOwner && exam?.status == "draft") {
                        Box {
                            IconButton(onClick = { showEditMenu = true }) {
                                Icon(Icons.Filled.MoreVert, "Options", tint = TextWhite)
                            }
                            DropdownMenu(
                                expanded = showEditMenu,
                                onDismissRequest = { showEditMenu = false },
                                containerColor = CardBgAlt
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename Exam", color = TextWhite) },
                                    leadingIcon = { Icon(Icons.Filled.Edit, null, tint = Cyan) },
                                    onClick = {
                                        showEditMenu = false
                                        renameText = exam?.examName.orEmpty()
                                        showRenameDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete Exam", color = AccentRed) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = AccentRed) },
                                    onClick = {
                                        showEditMenu = false
                                        showDeleteConfirm = true
                                    }
                                )
                            }
                        }
                    }
                    if (isOwner && exam?.status != "published" && subjects.isNotEmpty()) {
                        TextButton(onClick = {
                            viewModel.publishExam(
                                onSuccess = {
                                    scope.launch { snackbarHostState.showSnackbar("Final exam published!") }
                                },
                                onError = {
                                    scope.launch { snackbarHostState.showSnackbar(it) }
                                }
                            )
                        }) {
                            Text("Publish", color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // Status + batch summary card
            val (statusColor, statusLabel) = when (exam?.status) {
                "published" -> AccentGreen to "Published"
                "draft" -> TextMuted to "Draft"
                else -> AccentAmber to "In Progress"
            }
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(batchName, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text("${subjects.size} subjects • ${marks.size} mark entries", color = TextMuted, fontSize = 12.sp)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(statusColor.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (results.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(AccentViolet, Cyan)))
                        .clickable { onOpenResults(examId) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("View Results & Merit List", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                if (isOwner) "Subjects & Approval" else "My Subjects (Marks Entry)",
                color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            mySubjects.forEach { subjectView ->
                val subject = subjectView.subject
                val subjectMarks = marks.filter { it.subjectId == subject.id }
                val approvedCount = subjectMarks.count { it.status == "approved" }
                val submittedCount = subjectMarks.count { it.status == "submitted" || it.status == "under_review" }
                val draftCount = subjectMarks.count { it.status == "draft" }
                val (subjStatusColor, subjStatusLabel) = when {
                    approvedCount == subjectMarks.size && subjectMarks.isNotEmpty() -> AccentGreen to "Approved"
                    submittedCount > 0 -> AccentAmber to "Submitted"
                    else -> TextMuted to "Draft"
                }
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBgAlt),
                    border = BorderStroke(1.dp, BorderSub)
                ) {
                    Column(Modifier.padding(13.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(subject.subjectName, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Full: ${formatMarks(subject.fullMarks)} • Pass: ${formatMarks(subject.passMarks)} • ${subjectView.componentsLabel()}",
                                    color = TextMuted, fontSize = 11.sp
                                )
                                if (subject.assignedStaffName != null) {
                                    Text("Teacher: ${subject.assignedStaffName}", color = Cyan, fontSize = 11.sp)
                                }
                            }
                            Box(
                                Modifier.clip(RoundedCornerShape(999.dp)).background(subjStatusColor.copy(alpha = 0.15f))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(subjStatusLabel, color = subjStatusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TinyStatusChipFE("${approvedCount} approved", AccentGreen)
                            TinyStatusChipFE("${submittedCount} submitted", AccentAmber)
                            TinyStatusChipFE("${draftCount} draft", TextMuted)
                        }
                        if (isOwner) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Marks entry ${if (subject.marksEntryEnabled) "ON" else "OFF"}",
                                    color = if (subject.marksEntryEnabled) AccentGreen else AccentRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = subject.marksEntryEnabled,
                                    onCheckedChange = { viewModel.toggleMarksEntry(subject, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = AccentGreen,
                                        checkedTrackColor = AccentGreen.copy(alpha = 0.4f),
                                        uncheckedThumbColor = TextMuted,
                                        uncheckedTrackColor = BorderSub
                                    )
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        when {
                            !subject.marksEntryEnabled && !isOwner -> {
                                Text("Marks entry is currently off.", color = AccentRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            isOwner && subjStatusLabel != "Approved" && (submittedCount > 0 || draftCount > 0) -> {
                                Box(
                                    Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(12.dp))
                                        .background(AccentGreen)
                                        .clickable {
                                            viewModel.approveSubject(
                                                subject,
                                                onSuccess = {
                                                    scope.launch { snackbarHostState.showSnackbar("${subject.subjectName} marks approved & locked.") }
                                                },
                                                onError = {
                                                    scope.launch { snackbarHostState.showSnackbar(it) }
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Approve & Lock Marks", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            isOwner -> {
                                Box(
                                    Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(12.dp))
                                        .background(CardBg).border(1.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                        .clickable { onOpenMarks(examId, subject.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(if (subjStatusLabel == "Approved") "Review Approved Marks" else "Enter / Review Marks", color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            subjStatusLabel == "Approved" -> {
                                Text("Marks approved — no more edits.", color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            subjStatusLabel == "Submitted" -> {
                                Text("Submitted — waiting for owner approval.", color = AccentAmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            !subject.marksEntryEnabled -> {
                                Text("Marks entry is currently off.", color = AccentRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            else -> {
                                Box(
                                    Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(12.dp))
                                        .background(ElectricBlue)
                                        .clickable { onOpenMarks(examId, subject.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(if (draftCount > 0) "Continue Editing Draft" else "Enter Student Marks", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (mySubjects.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (isOwner) "Add subjects to configure this exam." else "No subjects assigned to you yet.",
                        color = TextMuted, fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    // Rename dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = CardBg,
            title = { Text("Rename Exam", color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Exam name", fontSize = 11.sp) },
                    colors = feTextFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameDraftExam(
                        examId,
                        renameText,
                        onSuccess = {
                            showRenameDialog = false
                            scope.launch { snackbarHostState.showSnackbar("Exam renamed.") }
                        },
                        onError = {
                            scope.launch { snackbarHostState.showSnackbar(it) }
                        }
                    )
                }) { Text("Save", color = Cyan, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel", color = TextMuted) }
            }
        )
    }

    // Delete confirm dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = CardBg,
            title = { Text("Delete Exam?", color = AccentRed, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = { Text("This draft exam and its subject configuration will be deleted. This cannot be undone.", color = TextMuted, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDraftExam(
                        examId,
                        onSuccess = {
                            showDeleteConfirm = false
                            onBack()
                        },
                        onError = {
                            showDeleteConfirm = false
                            scope.launch { snackbarHostState.showSnackbar(it) }
                        }
                    )
                }) { Text("Delete", color = AccentRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = TextMuted) }
            }
        )
    }
}

@Composable
private fun TinyStatusChipFE(label: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// ═══════════════════════════════════════════════════════════════
//  FinalExamMarksEntryScreen
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalExamMarksEntryScreen(
    db: AppDatabase,
    examId: String,
    subjectId: String,
    onBack: () -> Unit
) {
    val viewModel: FinalExamViewModel = viewModel(factory = FinalExamViewModelFactory(db))
    val exam by viewModel.selectedExam.collectAsState()
    val subjects by viewModel.subjects.collectAsState()
    val marks by viewModel.marks.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isOwner = SessionManager.isAdmin()

    var students by remember { mutableStateOf<List<StudentEntity>>(emptyList()) }
    // Local drafts: studentId -> (mcq, cq, practical)
    var draftMarks by remember { mutableStateOf<Map<String, Triple<String, String, String>>>(emptyMap()) }
    var showSubmitConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(examId) { viewModel.loadExam(examId) }

    val subjectView = subjects.find { it.subject.id == subjectId }
    val subject = subjectView?.subject

    LaunchedEffect(subject, exam) {
        val instId = SessionManager.currentInstituteId.value
        val batchId = exam?.batchId
        if (instId != null && batchId != null) {
            students = db.batchStudentDao().getStudentsForBatchOnce(batchId, instId)
                .filter { it.status == "active" }
            val existing = marks.filter { it.subjectId == subjectId }
            draftMarks = students.associate { student ->
                val m = existing.find { it.studentId == student.id }
                student.id to Triple(
                    m?.mcqMarks?.let { formatMarksInput(it) } ?: "",
                    m?.cqMarks?.let { formatMarksInput(it) } ?: "",
                    m?.practicalMarks?.let { formatMarksInput(it) } ?: ""
                )
            }
        }
    }

    // Draft = editable. Submitted/under_review/approved = locked for staff, owner can always edit.
    val locked = subject != null && marks.filter { it.subjectId == subjectId }.any { it.status == "submitted" || it.status == "under_review" || it.status == "approved" }
    val subjectMarks = marks.filter { it.subjectId == subjectId }
    val hasDraftMarks = subjectMarks.isNotEmpty() && subjectMarks.all { it.status == "draft" }
    val editable = isOwner || !locked

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(subject?.subjectName ?: "Marks Entry", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 19.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                actions = {
                    if (!isOwner && !locked && hasDraftMarks) {
                        TextButton(onClick = { showSubmitConfirm = true }) {
                            Text("Submit Marks", color = AccentAmber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            if (locked && !isOwner) {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AccentAmber.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.4f))
                ) {
                    Text(
                        "These marks are submitted and locked. You cannot edit after submission.",
                        color = AccentAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            if (locked && isOwner) {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.4f))
                ) {
                    Text(
                        "Owner mode: you can edit marks at any time, even after submission or approval.",
                        color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            // Column header
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardHi).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Student", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
                if (subjectView?.hasMcq == true) Text("MCQ", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                if (subjectView?.hasCq == true) Text("CQ", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                if (subjectView?.hasPractical == true) Text("Practical", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("Total", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(6.dp))

            // Component limits come from the subject configuration.
            val mcqLimit = subject?.mcqFullMarks ?: 0.0
            val cqLimit = subject?.cqFullMarks ?: 0.0
            val practicalLimit = subject?.practicalFullMarks ?: 0.0

            students.forEach { student ->
                val draft = draftMarks[student.id] ?: Triple("", "", "")
                val mcqVal = draft.first.toDoubleOrNull() ?: 0.0
                val cqVal = draft.second.toDoubleOrNull() ?: 0.0
                val practicalVal = draft.third.toDoubleOrNull() ?: 0.0
                val total = mcqVal + cqVal + practicalVal

                val mcqInvalid = subjectView?.hasMcq == true && mcqVal > mcqLimit
                val cqInvalid = subjectView?.hasCq == true && cqVal > cqLimit
                val practicalInvalid = subjectView?.hasPractical == true && practicalVal > practicalLimit
                val totalInvalid = subjectView?.totalOnly == true && total > (subject?.fullMarks ?: 0.0)
                val rowInvalid = mcqInvalid || cqInvalid || practicalInvalid || totalInvalid

                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(CardBgAlt)
                        .border(1.dp, if (rowInvalid) AccentRed else BorderSub, RoundedCornerShape(10.dp))
                        .padding(vertical = 8.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1.4f)) {
                        Text(student.fullName, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(student.studentCode, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (subjectView?.totalOnly == true) {
                        MarksField(
                            value = draft.first,
                            onValue = { v -> draftMarks = draftMarks + (student.id to Triple(v, "", "")) },
                            editable = editable,
                            modifier = Modifier.weight(1.6f),
                            invalid = totalInvalid,
                            hint = "${formatMarks(subject?.fullMarks ?: 0.0)}"
                        )
                    } else {
                        if (subjectView?.hasMcq == true) {
                            MarksField(
                                value = draft.first,
                                onValue = { v -> draftMarks = draftMarks + (student.id to Triple(v, draft.second, draft.third)) },
                                editable = editable,
                                modifier = Modifier.weight(1f),
                                invalid = mcqInvalid,
                                hint = "${formatMarks(mcqLimit)}"
                            )
                        }
                        if (subjectView?.hasCq == true) {
                            MarksField(
                                value = draft.second,
                                onValue = { v -> draftMarks = draftMarks + (student.id to Triple(draft.first, v, draft.third)) },
                                editable = editable,
                                modifier = Modifier.weight(1f),
                                invalid = cqInvalid,
                                hint = "${formatMarks(cqLimit)}"
                            )
                        }
                        if (subjectView?.hasPractical == true) {
                            MarksField(
                                value = draft.third,
                                onValue = { v -> draftMarks = draftMarks + (student.id to Triple(draft.first, draft.second, v)) },
                                editable = editable,
                                modifier = Modifier.weight(1f),
                                invalid = practicalInvalid,
                                hint = "${formatMarks(practicalLimit)}"
                            )
                        }
                    }
                    Box(Modifier.weight(0.9f), contentAlignment = Alignment.Center) {
                        Text(formatMarks(total), color = if (rowInvalid) AccentRed else Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (editable) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                        .clickable {
                            if (subject == null) return@clickable
                            // Validate against subject component limits before saving.
                            val mcqLimit = subject.mcqFullMarks
                            val cqLimit = subject.cqFullMarks
                            val practicalLimit = subject.practicalFullMarks
                            val totalLimit = subject.fullMarks
                            val isTotalOnly = subjectView?.totalOnly == true

                            val invalidRows = students.mapNotNull { student ->
                                val draft = draftMarks[student.id] ?: return@mapNotNull null
                                val mcq = draft.first.toDoubleOrNull() ?: 0.0
                                val cq = draft.second.toDoubleOrNull() ?: 0.0
                                val practical = draft.third.toDoubleOrNull() ?: 0.0
                                val total = mcq + cq + practical
                                val problems = mutableListOf<String>()
                                if (!isTotalOnly) {
                                    if (subjectView?.hasMcq == true && mcq > mcqLimit) problems.add("MCQ > ${formatMarks(mcqLimit)}")
                                    if (subjectView?.hasCq == true && cq > cqLimit) problems.add("CQ > ${formatMarks(cqLimit)}")
                                    if (subjectView?.hasPractical == true && practical > practicalLimit) problems.add("Practical > ${formatMarks(practicalLimit)}")
                                } else if (total > totalLimit) {
                                    problems.add("Total > ${formatMarks(totalLimit)}")
                                }
                                if (problems.isEmpty()) null else "${student.fullName}: ${problems.joinToString(", ")}"
                            }
                            if (invalidRows.isNotEmpty()) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(invalidRows.joinToString(" • ").take(180))
                                }
                                return@clickable
                            }
                            val existingByStudent = subjectMarks.associateBy { it.studentId }
                            val entries = students.mapNotNull { student ->
                                val draft = draftMarks[student.id] ?: return@mapNotNull null
                                val mcq = if (isTotalOnly) 0.0 else draft.first.toDoubleOrNull() ?: 0.0
                                val cq = if (isTotalOnly) 0.0 else draft.second.toDoubleOrNull() ?: 0.0
                                val practical = if (isTotalOnly) 0.0 else draft.third.toDoubleOrNull() ?: 0.0
                                val total = if (isTotalOnly) draft.first.toDoubleOrNull() ?: 0.0 else mcq + cq + practical
                                val existing = existingByStudent[student.id]
                                // Preserve status on owner edits: never downgrade a submitted/approved mark back to draft.
                                val preservedStatus = if (isOwner && existing != null) {
                                    when (existing.status) {
                                        "submitted", "under_review", "approved" -> existing.status
                                        else -> "draft"
                                    }
                                } else "draft"
                                FinalExamMarksEntity(
                                    id = "${subject.id}_${student.id}",
                                    instituteId = subject.instituteId,
                                    finalExamId = examId,
                                    subjectId = subject.id,
                                    studentId = student.id,
                                    mcqMarks = mcq,
                                    cqMarks = cq,
                                    practicalMarks = practical,
                                    totalMarks = total,
                                    status = preservedStatus,
                                    enteredByUserId = SessionManager.currentUserId.value.orEmpty(),
                                    enteredByName = SessionManager.currentUserId.value.orEmpty(),
                                    submittedAtMs = existing?.submittedAtMs,
                                    reviewedAtMs = existing?.reviewedAtMs,
                                    approvedAtMs = existing?.approvedAtMs,
                                    updatedAtMs = System.currentTimeMillis()
                                )
                            }
                            viewModel.saveMarks(
                                subject,
                                entries,
                                onSuccess = {
                                    scope.launch {
                                        if (isOwner) {
                                            snackbarHostState.showSnackbar("Draft saved. You can edit anytime.")
                                        } else {
                                            snackbarHostState.showSnackbar("Draft saved. You can edit until you submit — after submit, editing is locked.")
                                        }
                                    }
                                },
                                onError = {
                                    scope.launch { snackbarHostState.showSnackbar(it) }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Save Marks", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Submit confirmation dialog (staff)
    if (showSubmitConfirm) {
        AlertDialog(
            onDismissRequest = { showSubmitConfirm = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(18.dp),
            title = { Text("Submit Marks?", color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Once you submit, you will no longer be able to edit these marks. The institute owner will review and approve them.",
                    color = TextMuted, fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSubmitConfirm = false
                    viewModel.submitSubject(
                        subject!!,
                        onSuccess = {
                            scope.launch { snackbarHostState.showSnackbar("Marks submitted. Waiting for owner approval.") }
                        },
                        onError = {
                            scope.launch { snackbarHostState.showSnackbar(it) }
                        }
                    )
                }) { Text("Submit & Lock", color = AccentAmber, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showSubmitConfirm = false }) { Text("Keep Editing", color = TextMuted) }
            }
        )
    }
}

@Composable
private fun MarksField(
    value: String,
    onValue: (String) -> Unit,
    editable: Boolean,
    modifier: Modifier = Modifier,
    invalid: Boolean = false,
    hint: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new ->
            if (new.length <= 6 && new.all { it.isDigit() || it == '.' }) onValue(new)
        },
        modifier = modifier.padding(horizontal = 3.dp).height(48.dp),
        singleLine = true,
        enabled = editable,
        placeholder = { Text(hint, fontSize = 10.sp, color = TextMuted.copy(alpha = 0.5f)) },
        textStyle = androidx.compose.ui.text.TextStyle(color = TextWhite, fontSize = 13.sp, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = feTextFieldColors(invalid),
        shape = RoundedCornerShape(8.dp)
    )
}

// ═══════════════════════════════════════════════════════════════
//  FinalExamResultsScreen
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalExamResultsScreen(db: AppDatabase, examId: String, onBack: () -> Unit) {
    val viewModel: FinalExamViewModel = viewModel(factory = FinalExamViewModelFactory(db))
    val exam by viewModel.selectedExam.collectAsState()
    val subjects by viewModel.subjects.collectAsState()
    val results by viewModel.results.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedStudent by remember { mutableStateOf<FinalResultRow?>(null) }
    var showShareSheet by remember { mutableStateOf(false) }

    LaunchedEffect(examId) { viewModel.loadExam(examId) }

    var batchName by remember { mutableStateOf("Batch") }
    var instituteName by remember { mutableStateOf("Institute") }
    var instituteAddress by remember { mutableStateOf("") }
    var institutePhone by remember { mutableStateOf("") }
    var instituteLogoSource by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(exam, examId) {
        val instId = SessionManager.currentInstituteId.value ?: return@LaunchedEffect
        val e = exam ?: return@LaunchedEffect
        batchName = db.batchDao().getBatchesByInstituteOnce(instId).find { it.id == e.batchId }?.name ?: "Batch"
        val inst = db.instituteDao().getInstitute(instId)
        instituteName = inst?.name?.trim().orEmpty().ifBlank { "Institute" }
        instituteAddress = inst?.address?.trim().orEmpty()
        institutePhone = inst?.phone?.trim().orEmpty()
        instituteLogoSource = com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper.displaySource(context, inst?.profilePhotoUri)
    }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Final Results", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                actions = {
                    IconButton(onClick = { showShareSheet = true }) { Icon(Icons.Filled.Share, "Share", tint = Cyan) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Merit list header
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Merit List — ${exam?.examName ?: ""}", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("$batchName • ${results.size} students", color = TextMuted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))

            results.forEach { row ->
                val gradeColor = when (row.grade) {
                    "A+", "A" -> AccentGreen
                    "A-", "B" -> AccentAmber
                    "F" -> AccentRed
                    else -> TextMuted
                }
                Card(
                    Modifier.fillMaxWidth().clickable { selectedStudent = row },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = if (row.meritPosition == 1) CardHi else CardBgAlt),
                    border = BorderStroke(1.dp, if (row.meritPosition == 1) AccentAmber.copy(alpha = 0.6f) else BorderSub)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(listOf(ElectricBlue, Cyan))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("#${row.meritPosition}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.student.fullName, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${formatMarks(row.totalMarks)} / ${formatMarks(row.fullMarks)} • GPA ${"%.2f".format(row.gpa)}", color = TextMuted, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(row.grade, color = gradeColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(if (row.passed) "PASS" else "FAIL", color = if (row.passed) AccentGreen else AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (results.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Text("Results will appear once all subject marks are approved.", color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    // Student result card dialog
    if (selectedStudent != null && exam != null) {
        val row = selectedStudent!!
        val examEntity = exam!!
        Dialog(onDismissRequest = { selectedStudent = null }) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.student.fullName, color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("ID: ${row.student.studentCode} • Roll: ${row.student.studentCode}", color = TextMuted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { selectedStudent = null }) {
                            Icon(Icons.Filled.Close, "Close", tint = AccentRed)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = BorderSub)
                    Spacer(Modifier.height(10.dp))

                    // Subject-wise marks table (MCQ / CQ / Practical / Total)
                    val hasMcq = subjects.any { it.hasMcq }
                    val hasCq = subjects.any { it.hasCq }
                    val hasPractical = subjects.any { it.hasPractical }
                    // Header
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CardHi).padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Subject", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.6f))
                        if (hasMcq) Text("MCQ", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        if (hasCq) Text("CQ", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        if (hasPractical) Text("Prac", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        Text("Total", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        Text("Status", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(4.dp))
                    subjects.forEach { subjectView ->
                        val subject = subjectView.subject
                        val m = row.subjectMarks[subject.id]
                        val comps = subject.components.split(",").map { it.trim() }.toSet()
                        val passedSub = (m?.totalMarks ?: 0.0) >= subject.passMarks
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (subjectView.subject.id.hashCode() % 2 == 0) CardBgAlt else CardBg)
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(subject.subjectName, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (hasMcq) Text(if ("mcq" in comps) formatMarks(m?.mcqMarks ?: 0.0) else "—", color = TextWhite, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            if (hasCq) Text(if ("cq" in comps) formatMarks(m?.cqMarks ?: 0.0) else "—", color = TextWhite, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            if (hasPractical) Text(if ("practical" in comps) formatMarks(m?.practicalMarks ?: 0.0) else "—", color = TextWhite, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            Text(formatMarks(m?.totalMarks ?: 0.0), color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            Text(if (passedSub) "PASS" else "FAIL", color = if (passedSub) AccentGreen else AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = BorderSub)
                    Spacer(Modifier.height(10.dp))

                    Row(Modifier.fillMaxWidth()) {
                        Text("Total", color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${formatMarks(row.totalMarks)}", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("Percentage", color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${"%.2f".format(row.percentage)}%", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("GPA", color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${"%.2f".format(row.gpa)}", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("Grade", color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(row.grade, color = Cyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("Result", color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(if (row.passed) "Passed" else "Failed", color = if (row.passed) AccentGreen else AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("Merit Position", color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("#${row.meritPosition}", color = AccentAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("Comment", color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(finalComment(row), color = TextMuted, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(12.dp)).background(Cyan)
                                .clickable {
                                    selectedStudent = null
                                    shareFinalResultCard(context, row, subjects.map { it.subject }, examEntity, batchName, instituteName, instituteAddress, institutePhone, instituteLogoSource)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Share Card", color = BgColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(12.dp)).background(ElectricBlue)
                                .clickable {
                                    selectedStudent = null
                                    printFinalResultCard(context, row, subjects.map { it.subject }, examEntity, batchName, instituteName, instituteAddress, institutePhone, instituteLogoSource)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Print / PDF", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Share sheet
    if (showShareSheet && exam != null) {
        Dialog(onDismissRequest = { showShareSheet = false }) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Share Final Results", color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(WAGreen.copy(alpha = 0.15f))
                            .border(1.dp, WAGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable {
                                showShareSheet = false
                                shareFinalResultsPdf(context, results, subjects.map { it.subject }, exam!!, batchName, instituteName)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("All Results PDF", color = WAGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(Cyan.copy(alpha = 0.15f))
                            .border(1.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable {
                                showShareSheet = false
                                val msg = results.joinToString("\n\n") { row ->
                                    "${row.meritPosition}. ${row.student.fullName} — ${formatMarks(row.totalMarks)}/${formatMarks(row.fullMarks)} (${row.grade}, GPA ${"%.2f".format(row.gpa)})"
                                }
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Final Exam Merit List — ${exam!!.examName}\n$batchName\n\n$msg") },
                                        "Merit List"
                                    )
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Merit List (Text)", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showShareSheet = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = TextMuted) }
                }
            }
        }
    }
}

// ── Result card + PDF generation (plain Canvas) ────────────────
private fun finalComment(row: FinalResultRow): String = when {
    row.meritPosition == 1 -> "Outstanding performance — top of the class!"
    row.grade in listOf("A+", "A") -> "Excellent result. Keep up the great work!"
    row.passed -> "Good result. A little more effort can make it even better."
    else -> "Needs improvement. Regular practice will bring better results."
}

private fun shareFinalResultCard(
    context: android.content.Context,
    row: FinalResultRow,
    subjects: List<FinalExamSubjectEntity>,
    exam: com.batchfee.edu.data.models.FinalExamEntity,
    batchName: String,
    instituteName: String,
    instituteAddress: String = "",
    institutePhone: String = "",
    instituteLogoSource: String? = null
) {
    val bitmap = createFinalResultCardBitmap(row, subjects, exam, batchName, instituteName, instituteAddress, institutePhone, context, instituteLogoSource)
    try {
        val file = java.io.File(context.cacheDir, "final_result_${row.student.id}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share Final Result Card"
            )
        )
    } finally {
        bitmap.recycle()
    }
}

private fun printFinalResultCard(
    context: android.content.Context,
    row: FinalResultRow,
    subjects: List<FinalExamSubjectEntity>,
    exam: com.batchfee.edu.data.models.FinalExamEntity,
    batchName: String,
    instituteName: String,
    instituteAddress: String = "",
    institutePhone: String = "",
    instituteLogoSource: String? = null
) {
    val bitmap = createFinalResultCardBitmap(row, subjects, exam, batchName, instituteName, instituteAddress, institutePhone, context, instituteLogoSource)
    val document = PdfDocument()
    try {
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        canvas.drawColor(android.graphics.Color.WHITE)
        val scale = minOf(595f / bitmap.width, 842f / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        canvas.drawBitmap(bitmap, null, RectF((595f - w) / 2f, (842f - h) / 2f, (595f + w) / 2f, (842f + h) / 2f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        document.finishPage(page)
        val file = java.io.File(context.cacheDir, "final_result_${row.student.id}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Final Result PDF"
            )
        )
    } finally {
        document.close()
        bitmap.recycle()
    }
}

private fun shareFinalResultsPdf(
    context: android.content.Context,
    results: List<FinalResultRow>,
    subjects: List<FinalExamSubjectEntity>,
    exam: com.batchfee.edu.data.models.FinalExamEntity,
    batchName: String,
    instituteName: String
) {
    val document = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val rowsPerPage = 20
    try {
        results.chunked(rowsPerPage).forEachIndexed { pageIndex, pageRows ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create())
            val canvas = page.canvas
            canvas.drawColor(android.graphics.Color.WHITE)
            val navy = android.graphics.Color.parseColor("#0B1F3A")
            val navyMid = android.graphics.Color.parseColor("#123C6A")
            val cyan = android.graphics.Color.parseColor("#22D3EE")
            val ink = android.graphics.Color.parseColor("#10233F")
            val muted = android.graphics.Color.parseColor("#64748B")
            val paleRow = android.graphics.Color.parseColor("#F1F6FC")
            val green = android.graphics.Color.parseColor("#16A34A")
            val red = android.graphics.Color.parseColor("#DC2626")
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

            // Header
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(0f, 0f, pageWidth.toFloat(), 0f, navy, navyMid, android.graphics.Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), 130f, headerPaint)
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 26f; isFakeBoldText = true }
            canvas.drawText(fitFEPdfText(exam.examName, titlePaint, 480f), 24f, 52f, titlePaint)
            val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(190, 255, 255, 255); textSize = 15f }
            canvas.drawText("$batchName  •  Final Exam Result Sheet  •  Page ${pageIndex + 1}", 24f, 84f, metaPaint)
            val instPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textSize = 14f; isFakeBoldText = true }
            canvas.drawText(fitFEPdfText(instituteName.uppercase(), instPaint, 480f), 24f, 114f, instPaint)

            // Table header
            val tableTop = 150f
            val rowH = 30f
            canvas.drawRect(0f, tableTop, pageWidth.toFloat(), tableTop + rowH, headerPaint)
            val thPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 13f; isFakeBoldText = true }
            canvas.drawText("POS", 14f, tableTop + 20f, thPaint)
            canvas.drawText("STUDENT", 60f, tableTop + 20f, thPaint)
            canvas.drawText("TOTAL", 300f, tableTop + 20f, thPaint)
            canvas.drawText("GPA", 380f, tableTop + 20f, thPaint)
            canvas.drawText("GRADE", 440f, tableTop + 20f, thPaint)
            canvas.drawText("RESULT", 510f, tableTop + 20f, thPaint)

            pageRows.forEachIndexed { idx, row ->
                val y = tableTop + (idx + 1) * rowH
                if (idx % 2 == 1) {
                    fill.color = paleRow
                    canvas.drawRect(0f, y, pageWidth.toFloat(), y + rowH, fill)
                }
                val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 12f }
                canvas.drawText("#${row.meritPosition}", 14f, y + 20f, rowPaint)
                canvas.drawText(fitFEPdfText(row.student.fullName, rowPaint, 220f), 60f, y + 20f, rowPaint)
                canvas.drawText("${formatMarks(row.totalMarks)}/${formatMarks(row.fullMarks)}", 300f, y + 20f, rowPaint)
                canvas.drawText("%.2f".format(row.gpa), 380f, y + 20f, rowPaint)
                canvas.drawText(row.grade, 440f, y + 20f, rowPaint)
                canvas.drawText(
                    if (row.passed) "PASS" else "FAIL",
                    510f, y + 20f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (row.passed) green else red; textSize = 12f; isFakeBoldText = true }
                )
            }

            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 11f; textAlign = Paint.Align.CENTER }
            canvas.drawText("Generated by BatchFee  •  ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())}", pageWidth / 2f, pageHeight - 24f, footerPaint)
            document.finishPage(page)
        }

        val safeName = exam.examName.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "FinalExam" }
        val file = java.io.File(context.cacheDir, "final_results_$safeName.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Final Results PDF"
            )
        )
    } finally {
        document.close()
    }
}

private fun createFinalResultCardBitmap(
    row: FinalResultRow,
    subjects: List<FinalExamSubjectEntity>,
    exam: com.batchfee.edu.data.models.FinalExamEntity,
    batchName: String,
    instituteName: String,
    instituteAddress: String = "",
    institutePhone: String = "",
    context: android.content.Context? = null,
    instituteLogoSource: String? = null
): Bitmap {
    val width = 1080
    val height = 1720
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val ink = android.graphics.Color.parseColor("#0F172A")
    val navy = android.graphics.Color.parseColor("#1E3A5F")
    val muted = android.graphics.Color.parseColor("#64748B")
    val white = android.graphics.Color.WHITE
    val softBg = android.graphics.Color.parseColor("#F8FAFC")
    val line = android.graphics.Color.parseColor("#E2E8F0")
    val cyan = android.graphics.Color.parseColor("#0891B2")
    val green = android.graphics.Color.parseColor("#16A34A")
    val red = android.graphics.Color.parseColor("#DC2626")
    val redBg = android.graphics.Color.parseColor("#FEE2E2")
    val amber = android.graphics.Color.parseColor("#D97706")

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    canvas.drawColor(white)

    // ── Watermark institute logo (center, very light, behind everything) ──
    val logoBitmap = if (context != null && instituteLogoSource != null) {
        runCatching {
            val resolved = com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper.displaySource(context, instituteLogoSource) ?: return@runCatching null
            val uri = Uri.parse(resolved)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    val conn = (java.net.URL(resolved).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 5_000; readTimeout = 5_000; doInput = true
                    }
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }.also { conn.disconnect() }
                }
                "file", "content" -> context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                else -> java.io.File(resolved).inputStream().use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    } else null
    if (logoBitmap != null) {
        val logoSize = 460f
        val dst = RectF(width / 2f - logoSize / 2f, height / 2f - logoSize / 2f + 60f, width / 2f + logoSize / 2f, height / 2f + logoSize / 2f + 60f)
        val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 30 }
        canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), logoPaint)
        canvas.drawBitmap(logoBitmap, null, dst, logoPaint)
        canvas.restore()
    }

    // ── Top accent ──
    val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), 0f,
            android.graphics.Color.parseColor("#0EA5E9"), cyan, android.graphics.Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, width.toFloat(), 12f, accent)

    // ── Header: logo (small, left) + institute name + address + contact ──
    var cursorY = 64f
    val nameX: Float
    if (logoBitmap != null) {
        val logoDst = RectF(48f, 40f, 148f, 140f)
        canvas.drawBitmap(logoBitmap, null, logoDst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        nameX = 168f
    } else {
        nameX = 48f
    }
    val instPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy; textSize = 34f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    canvas.drawText(fitFEPdfText(instituteName.uppercase(), instPaint, width - nameX - 48f), nameX + (width - nameX - 48f) / 2f, cursorY + 8f, instPaint)
    cursorY += 34f
    if (instituteAddress.isNotBlank()) {
        val addrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 18f; textAlign = Paint.Align.CENTER }
        canvas.drawText(fitFEPdfText(instituteAddress, addrPaint, width - nameX - 48f), nameX + (width - nameX - 48f) / 2f, cursorY, addrPaint)
        cursorY += 24f
    }
    val contactLine = listOf(institutePhone).filter { it.isNotBlank() }.joinToString("  •  ")
    if (contactLine.isNotBlank()) {
        val contactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textSize = 17f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        canvas.drawText(fitFEPdfText(contactLine, contactPaint, width - nameX - 48f), nameX + (width - nameX - 48f) / 2f, cursorY, contactPaint)
        cursorY += 24f
    }
    cursorY += 6f

    // ── Exam title (large) ──
    val examPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 44f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    canvas.drawText(fitFEPdfText(exam.examName, examPaint, width - 96f), width / 2f, cursorY, examPaint)
    cursorY += 30f
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 20f; textAlign = Paint.Align.CENTER }
    canvas.drawText("Academic Result Statement", width / 2f, cursorY, subPaint)
    cursorY += 30f

    val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; strokeWidth = 2f }
    canvas.drawLine(48f, cursorY, width - 48f, cursorY, divider)
    cursorY += 28f

    // ── Student details box ──
    val detailsTop = cursorY
    fill.color = softBg
    canvas.drawRoundRect(48f, detailsTop, width - 48f, detailsTop + 150f, 14f, 14f, fill)
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 17f }
    val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 21f; isFakeBoldText = true }
    val colX = 76f
    val colW = (width - 96f - 48f) / 3f
    canvas.drawText("STUDENT NAME", colX, detailsTop + 36f, labelPaint)
    canvas.drawText(fitFEPdfText(row.student.fullName, valuePaint, colW - 8f), colX, detailsTop + 64f, valuePaint)
    canvas.drawText("ROLL / ID", colX, detailsTop + 100f, labelPaint)
    canvas.drawText(fitFEPdfText(row.student.studentCode.ifBlank { "N/A" }, valuePaint, colW - 8f), colX, detailsTop + 128f, valuePaint)

    canvas.drawText("BATCH / CLASS", colX + colW, detailsTop + 36f, labelPaint)
    canvas.drawText(fitFEPdfText(batchName, valuePaint, colW - 8f), colX + colW, detailsTop + 64f, valuePaint)
    canvas.drawText("CONTACT", colX + colW, detailsTop + 100f, labelPaint)
    canvas.drawText(fitFEPdfText(row.student.phone ?: row.student.guardianPhone ?: "N/A", valuePaint, colW - 8f), colX + colW, detailsTop + 128f, valuePaint)

    canvas.drawText("GUARDIAN NAME", colX + colW * 2, detailsTop + 36f, labelPaint)
    canvas.drawText(fitFEPdfText(row.student.guardianName?.ifBlank { "N/A" } ?: "N/A", valuePaint, colW - 8f), colX + colW * 2, detailsTop + 64f, valuePaint)
    canvas.drawText("STATUS", colX + colW * 2, detailsTop + 100f, labelPaint)
    val statusColor = if (row.passed) green else red
    canvas.drawText(if (row.passed) "PASSED" else "FAILED", colX + colW * 2, detailsTop + 128f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = statusColor; textSize = 21f; isFakeBoldText = true })
    cursorY = detailsTop + 170f

    // ── Subject marks table ──
    // Columns: Subject | MCQ | CQ | Practical | Total | Pass | Status (dynamic)
    val hasMcq = subjects.any { it.components.split(",").any { c -> c.trim() == "mcq" } }
    val hasCq = subjects.any { it.components.split(",").any { c -> c.trim() == "cq" } }
    val hasPractical = subjects.any { it.components.split(",").any { c -> c.trim() == "practical" } }
    val cols = mutableListOf<Triple<String, Float, Float>>()
    var colLeft = 48f
    var subjectColW = 330f
    val remaining = (width - 96f - subjectColW)
    val extraCols = 1 + (if (hasMcq) 1 else 0) + (if (hasCq) 1 else 0) + (if (hasPractical) 1 else 0) + 1 + 1 // Total + Pass + Status
    val extraW = remaining / extraCols
    cols.add(Triple("SUBJECT", colLeft, subjectColW))
    colLeft += subjectColW
    if (hasMcq) { cols.add(Triple("MCQ", colLeft, extraW)); colLeft += extraW }
    if (hasCq) { cols.add(Triple("CQ", colLeft, extraW)); colLeft += extraW }
    if (hasPractical) { cols.add(Triple("Practical", colLeft, extraW)); colLeft += extraW }
    cols.add(Triple("TOTAL", colLeft, extraW)); colLeft += extraW
    cols.add(Triple("PASS", colLeft, extraW)); colLeft += extraW
    cols.add(Triple("STATUS", colLeft, extraW))

    val tableTop = cursorY
    val rowH = 48f
    // Header: gradient navy bar
    val headerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.LinearGradient(0f, tableTop, width.toFloat(), tableTop, ink, navy, android.graphics.Shader.TileMode.CLAMP)
    }
    canvas.drawRoundRect(48f, tableTop, width - 48f, tableTop + rowH, 10f, 10f, headerBg)
    val thPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = 15f; isFakeBoldText = true }
    cols.forEach { (label, x, w) ->
        val isNum = label != "SUBJECT"
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white; textSize = 15f; isFakeBoldText = true
            textAlign = if (isNum) Paint.Align.CENTER else Paint.Align.LEFT
        }
        canvas.drawText(label, if (isNum) x + w / 2 else x + 16f, tableTop + 31f, lp)
    }

    // Vertical column separators + row grid lines
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#CBD5E1"); strokeWidth = 1.5f }
    val totalRows = subjects.size + 1
    for (r in 0..totalRows) {
        val gy = tableTop + r * rowH
        canvas.drawLine(48f, gy, width - 48f, gy, gridPaint)
    }
    canvas.drawLine(48f, tableTop, 48f, tableTop + totalRows * rowH, gridPaint)
    canvas.drawLine(width - 48f, tableTop, width - 48f, tableTop + totalRows * rowH, gridPaint)
    var vx = 48f + subjectColW
    repeat(cols.size - 1) {
        canvas.drawLine(vx, tableTop, vx, tableTop + totalRows * rowH, gridPaint)
        vx += extraW
    }

    subjects.forEachIndexed { idx, subject ->
        val y = tableTop + (idx + 1) * rowH
        val m = row.subjectMarks[subject.id]
        val comps = subject.components.split(",").map { it.trim() }.toSet()
        val mcq = if ("mcq" in comps) (m?.mcqMarks ?: 0.0) else null
        val cq = if ("cq" in comps) (m?.cqMarks ?: 0.0) else null
        val practical = if ("practical" in comps) (m?.practicalMarks ?: 0.0) else null
        val totalVal = m?.totalMarks ?: 0.0
        val passedSub = totalVal >= subject.passMarks
        // Row background: FAIL rows light red, otherwise zebra
        fill.color = if (!passedSub) redBg else if (idx % 2 == 0) softBg else white
        canvas.drawRect(48f + 1f, y + 1f, width - 48f - 1f, y + rowH - 1f, fill)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (!passedSub) red else ink; textSize = 17f; isFakeBoldText = true }
        canvas.drawText(fitFEPdfText(subject.subjectName, subPaint, subjectColW - 24f), 64f, y + 31f, subPaint)
        var cellX = 48f + subjectColW
        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (!passedSub) red else ink; textSize = 16f; isFakeBoldText = !passedSub; textAlign = Paint.Align.CENTER }
        if (hasMcq) { canvas.drawText(mcq?.let { formatMarks(it) } ?: "—", cellX + extraW / 2, y + 31f, cellPaint); cellX += extraW }
        if (hasCq) { canvas.drawText(cq?.let { formatMarks(it) } ?: "—", cellX + extraW / 2, y + 31f, cellPaint); cellX += extraW }
        if (hasPractical) { canvas.drawText(practical?.let { formatMarks(it) } ?: "—", cellX + extraW / 2, y + 31f, cellPaint); cellX += extraW }
        canvas.drawText(formatMarks(totalVal), cellX + extraW / 2, y + 31f, cellPaint); cellX += extraW
        canvas.drawText(formatMarks(subject.passMarks), cellX + extraW / 2, y + 31f, cellPaint); cellX += extraW
        canvas.drawText(
            if (passedSub) "PASS" else "FAIL",
            cellX + extraW / 2, y + 31f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (passedSub) green else red; textSize = 15f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        )
    }

    cursorY = tableTop + (subjects.size + 1) * rowH + 18f

    // ── Subject pass/fail summary ──
    val failedSubjects = subjects.mapNotNull { subject ->
        val m = row.subjectMarks[subject.id]
        val totalVal = m?.totalMarks ?: 0.0
        if (totalVal >= subject.passMarks) null else subject.subjectName
    }
    val summaryBoxH = 56f
    val summaryBg = if (failedSubjects.isEmpty()) android.graphics.Color.parseColor("#ECFDF5") else android.graphics.Color.parseColor("#FEF2F2")
    fill.color = summaryBg
    canvas.drawRoundRect(48f, cursorY, width - 48f, cursorY + summaryBoxH, 12f, 12f, fill)
    val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (failedSubjects.isEmpty()) green else red
        textSize = 19f; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    val summaryText = if (failedSubjects.isEmpty()) {
        "All Subjects Passed"
    } else {
        "Failed Subjects (${failedSubjects.size}): ${failedSubjects.joinToString(", ")}"
    }
    canvas.drawText(fitFEPdfText(summaryText, summaryPaint, width - 140f), width / 2f, cursorY + 37f, summaryPaint)
    cursorY += summaryBoxH + 14f

    // ── Totals + stats row ──
    fill.color = android.graphics.Color.parseColor("#ECFEFF")
    canvas.drawRoundRect(48f, cursorY, width - 48f, cursorY + 76f, 12f, 12f, fill)
    val totalLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 22f; isFakeBoldText = true }
    canvas.drawText("TOTAL MARKS", 76f, cursorY + 32f, totalLabel)
    canvas.drawText(formatMarks(row.totalMarks), width - 200f, cursorY + 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textSize = 30f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT })
    canvas.drawText("GPA ${"%.2f".format(row.gpa)}", 76f, cursorY + 62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 18f; isFakeBoldText = true })
    canvas.drawText(row.grade, width - 200f, cursorY + 62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan; textSize = 22f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT })
    cursorY += 96f

    // Merit + percentage + comment
    val statLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 18f }
    val statValue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 20f; isFakeBoldText = true }
    canvas.drawText("Merit Position", 76f, cursorY, statLabel)
    canvas.drawText("#${row.meritPosition}", width - 200f, cursorY, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = amber; textSize = 22f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT })
    cursorY += 34f
    canvas.drawText("Percentage", 76f, cursorY, statLabel)
    canvas.drawText("%.2f".format(row.percentage) + "%", width - 200f, cursorY, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 20f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT })
    cursorY += 34f
    canvas.drawText("Comment", 76f, cursorY, statLabel)
    canvas.drawText(fitFEPdfText(finalComment(row), statValue, 500f), 220f, cursorY, statValue)
    cursorY += 30f

    // ── Signature row (positioned above footer with breathing room) ──
    val sigY = height - 150f
    val sigLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; strokeWidth = 2.5f }
    val sigLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 16f; textAlign = Paint.Align.CENTER }
    canvas.drawLine(120f, sigY, 460f, sigY, sigLinePaint)
    canvas.drawText("Authority / Teacher Signature", 290f, sigY + 36f, sigLabel)
    canvas.drawLine(620f, sigY, 960f, sigY, sigLinePaint)
    canvas.drawText("Guardian Signature", 790f, sigY + 36f, sigLabel)

    val footLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; strokeWidth = 1f }
    canvas.drawLine(48f, height - 76f, width - 48f, height - 76f, footLine)
    val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 14f; textAlign = Paint.Align.CENTER }
    canvas.drawText("Verified academic record • Generated by BatchFee • ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())}", width / 2f, height - 42f, footPaint)

    return bitmap
}

private fun fitFEPdfText(value: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(value) <= maxWidth) return value
    val suffix = "..."
    var end = value.length
    while (end > 0 && paint.measureText(value.take(end) + suffix) > maxWidth) end -= 1
    return value.take(end) + suffix
}

private fun formatMarks(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)

private fun formatMarksInput(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)

@Composable
private fun feTextFieldColors(invalid: Boolean = false) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedBorderColor = if (invalid) AccentRed else Cyan,
    unfocusedBorderColor = if (invalid) AccentRed else BorderSub,
    focusedContainerColor = CardBg,
    unfocusedContainerColor = CardBg,
    cursorColor = Cyan
)
