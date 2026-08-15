package com.batchfee.edu.ui.assignments

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.WorkCloudSyncHelper
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.AssignmentEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.*

private val AsBg      = Color(0xFF07111F)
private val AsCard    = Color(0xFF0F172A)
private val AsCardAlt = Color(0xFF111827)
private val AsStroke  = Color(0xFF1E293B)
private val AsAmber   = Color(0xFFF59E0B)
private val AsCyan    = Color(0xFF22D3EE)
private val AsGreen   = Color(0xFF22C55E)
private val AsRed     = Color(0xFFEF4444)
private val AsWhite   = Color(0xFFF8FAFC)
private val AsMuted   = Color(0xFF94A3B8)
private val AsDim     = Color(0xFF64748B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAssignmentScreen(db: AppDatabase, onBack: () -> Unit) {
    val instId = SessionManager.currentInstituteId.value ?: ""
    var batches by remember { mutableStateOf<List<BatchEntity>>(emptyList()) }
    var selectedBatch by remember { mutableStateOf<BatchEntity?>(null) }
    var title by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var className by remember { mutableStateOf("") }
    var assignmentType by remember { mutableStateOf("individual") }
    var instructions by remember { mutableStateOf("") }
    var learningObjective by remember { mutableStateOf("") }
    var totalMarks by remember { mutableStateOf("") }
    var gradingMethod by remember { mutableStateOf("marks") }
    var hasDueDate by remember { mutableStateOf(false) }
    var dueDateMs by remember { mutableStateOf<Long?>(null) }
    var allowLateSubmission by remember { mutableStateOf(false) }
    var submissionFormat by remember { mutableStateOf("any") }
    // Assignment is normally intended for students, so publish is the simple default.
    // The owner can still explicitly choose Draft when it is not ready.
    var status by remember { mutableStateOf("published") }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var batchExpanded by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var gmExpanded by remember { mutableStateOf(false) }
    var fmtExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val types = listOf("individual" to "Individual", "group" to "Group", "project" to "Project", "presentation" to "Presentation", "written" to "Written", "lab" to "Lab Report")
    val formats = listOf("any" to "Any Format", "pdf" to "PDF", "word" to "Word", "image" to "Image", "video" to "Video", "presentation" to "Presentation", "text" to "Text")
    val gradingMethods = listOf("marks" to "Marks", "grade" to "Grade", "percentage" to "Percentage")

    LaunchedEffect(instId) {
        try { batches = withContext(Dispatchers.IO) { db.batchDao().getBatchesByInstituteOnce(instId) } } catch (_: Exception) {}
    }

    Scaffold(
        containerColor = AsBg,
        topBar = {
            TopAppBar(title = { Text("Add Assignment", color = AsWhite, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AsMuted) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AsBg))
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(AsBg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Header card
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = AsCard), border = BorderStroke(1.dp, AsStroke)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(AsAmber.copy(alpha = 0.15f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Assignment, null, tint = AsAmber, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column { Text("Assignment", color = AsWhite, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp); Text("Create graded work with marks & rubric", color = AsMuted, fontSize = 12.sp) }
                }
            }

            // Batch dropdown
            ExposedDropdownMenuBox(expanded = batchExpanded, onExpandedChange = { batchExpanded = it }) {
                OutlinedTextField(value = selectedBatch?.name ?: "All Batches", onValueChange = {}, readOnly = true, label = { Text("Batch", color = AsMuted) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(batchExpanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(), shape = RoundedCornerShape(14.dp), colors = asFieldColors())
                ExposedDropdownMenu(expanded = batchExpanded, onDismissRequest = { batchExpanded = false }, containerColor = AsCard) {
                    DropdownMenuItem(text = { Text("All Batches", color = AsWhite) }, onClick = { selectedBatch = null; batchExpanded = false })
                    batches.forEach { DropdownMenuItem(text = { Text("${it.name} (${it.batchCode})", color = AsWhite) }, onClick = { selectedBatch = it; batchExpanded = false }) }
                }
            }

            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title *", color = AsMuted) }, placeholder = { Text("e.g. Climate Change Project", color = AsDim) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), singleLine = true, colors = asFieldColors())

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Subject", color = AsMuted) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true, colors = asFieldColors())
                OutlinedTextField(value = className, onValueChange = { className = it }, label = { Text("Class/Section", color = AsMuted) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true, colors = asFieldColors())
            }

            // Assignment Type
            ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                OutlinedTextField(value = types.first { it.first == assignmentType }.second, onValueChange = {}, readOnly = true, label = { Text("Assignment Type", color = AsMuted) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(), shape = RoundedCornerShape(14.dp), colors = asFieldColors())
                ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }, containerColor = AsCard) { types.forEach { DropdownMenuItem(text = { Text(it.second, color = AsWhite) }, onClick = { assignmentType = it.first; typeExpanded = false }) } }
            }

            OutlinedTextField(value = instructions, onValueChange = { instructions = it }, label = { Text("Instructions", color = AsMuted) }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), shape = RoundedCornerShape(14.dp), minLines = 3, colors = asFieldColors())
            OutlinedTextField(value = learningObjective, onValueChange = { learningObjective = it }, label = { Text("Learning Objective", color = AsMuted) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), minLines = 2, colors = asFieldColors())

            // Marks + Grading row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = totalMarks, onValueChange = { totalMarks = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Total Marks", color = AsMuted) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true, colors = asFieldColors())
                Box(Modifier.weight(1f)) {
                    ExposedDropdownMenuBox(expanded = gmExpanded, onExpandedChange = { gmExpanded = it }) {
                        OutlinedTextField(value = gradingMethods.first { it.first == gradingMethod }.second, onValueChange = {}, readOnly = true, label = { Text("Grading", color = AsMuted) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(gmExpanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(), shape = RoundedCornerShape(14.dp), colors = asFieldColors())
                        ExposedDropdownMenu(expanded = gmExpanded, onDismissRequest = { gmExpanded = false }, containerColor = AsCard) { gradingMethods.forEach { DropdownMenuItem(text = { Text(it.second, color = AsWhite) }, onClick = { gradingMethod = it.first; gmExpanded = false }) } }
                    }
                }
            }

            // Submission Format
            ExposedDropdownMenuBox(expanded = fmtExpanded, onExpandedChange = { fmtExpanded = it }) {
                OutlinedTextField(value = formats.first { it.first == submissionFormat }.second, onValueChange = {}, readOnly = true, label = { Text("Submission Format", color = AsMuted) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(fmtExpanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(), shape = RoundedCornerShape(14.dp), colors = asFieldColors())
                ExposedDropdownMenu(expanded = fmtExpanded, onDismissRequest = { fmtExpanded = false }, containerColor = AsCard) { formats.forEach { DropdownMenuItem(text = { Text(it.second, color = AsWhite) }, onClick = { submissionFormat = it.first; fmtExpanded = false }) } }
            }

            // Late submission toggle
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = AsCard), border = BorderStroke(1.dp, AsStroke)) {
                Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("Allow Late Submission", color = AsWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text("Students can submit after the deadline", color = AsMuted, fontSize = 11.sp) }
                    Switch(checked = allowLateSubmission, onCheckedChange = { allowLateSubmission = it }, colors = SwitchDefaults.colors(checkedThumbColor = AsAmber, checkedTrackColor = AsAmber.copy(alpha = 0.3f)))
                }
            }

            // Due date
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = AsCard), border = BorderStroke(1.dp, AsStroke)) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text("Due Date", color = AsWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text("Submission deadline", color = AsMuted, fontSize = 11.sp) }
                        Switch(checked = hasDueDate, onCheckedChange = { hasDueDate = it; if (it && dueDateMs == null) dueDateMs = System.currentTimeMillis() + 86400000; if (!it) dueDateMs = null }, colors = SwitchDefaults.colors(checkedThumbColor = AsAmber, checkedTrackColor = AsAmber.copy(alpha = 0.3f)))
                    }
                    if (hasDueDate && dueDateMs != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, AsAmber.copy(alpha = 0.4f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = AsAmber)) {
                            Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                            Text(java.text.SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(dueDateMs!!)), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Publishing is the usual path; draft keeps unfinished work private.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = status == "draft", onClick = { status = "draft" }, label = { Text("Save as Draft", color = if (status == "draft") AsWhite else AsMuted) }, modifier = Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AsAmber.copy(alpha = 0.2f)))
                FilterChip(selected = status == "published", onClick = { status = "published" }, label = { Text("Publish Now", color = if (status == "published") AsWhite else AsMuted) }, modifier = Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AsGreen.copy(alpha = 0.2f)))
            }

            if (saveError != null) { Box(Modifier.fillMaxWidth().background(Color(0x22EF4444), RoundedCornerShape(10.dp)).padding(12.dp)) { Text(saveError!!, color = Color(0xFFFCA5A5), fontSize = 13.sp) } }

            Button(onClick = {
                if (title.isBlank()) { saveError = "Title is required"; return@Button }
                isSaving = true; saveError = null
                scope.launch {
                    val a = AssignmentEntity(id = UUID.randomUUID().toString(), instituteId = instId, batchId = selectedBatch?.id, title = title.trim(), subject = subject.takeIf { it.isNotBlank() }, className = className.takeIf { it.isNotBlank() }, assignmentType = assignmentType, instructions = instructions.trim(), learningObjective = learningObjective.takeIf { it.isNotBlank() }, totalMarks = totalMarks.toDoubleOrNull(), passingMarks = null, gradingMethod = gradingMethod, rubricJson = null, startDateMs = System.currentTimeMillis(), dueDateMs = if (hasDueDate) dueDateMs else null, allowLateSubmission = allowLateSubmission, latePenalty = null, submissionFormat = submissionFormat, maxFileSizeKb = null, referenceMaterials = null, status = status, publishDateMs = if (status == "published") System.currentTimeMillis() else null, createdAtMs = System.currentTimeMillis(), updatedAtMs = System.currentTimeMillis(), archivedAtMs = null)
                    try {
                        // A published assignment must reach the student app before
                        // this screen reports a successful save.
                        WorkCloudSyncHelper.syncAssignment(a)
                        withContext(Dispatchers.IO) { db.assignmentDao().upsert(a) }
                        onBack()
                    } catch (_: Exception) {
                        saveError = "Could not share assignment with students. Check your connection and try again."
                    } finally {
                        isSaving = false
                    }
                }
            }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(0.dp)) {
                Box(Modifier.fillMaxSize().shadow(12.dp, RoundedCornerShape(16.dp), spotColor = AsAmber.copy(alpha = 0.4f)).background(Brush.horizontalGradient(listOf(AsAmber, AsCyan)), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(if (status == "published") "Save & Publish" else "Save Draft", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showDatePicker) { val dpState = rememberDatePickerState(initialSelectedDateMillis = dueDateMs ?: System.currentTimeMillis()); DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = { TextButton(onClick = { dueDateMs = dpState.selectedDateMillis; showDatePicker = false }) { Text("OK") } }) { DatePicker(state = dpState) } }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun asFieldColors() = OutlinedTextFieldDefaults.colors(focusedContainerColor = AsCardAlt, unfocusedContainerColor = AsCardAlt, focusedBorderColor = AsAmber, unfocusedBorderColor = AsStroke, focusedTextColor = AsWhite, unfocusedTextColor = AsWhite, cursorColor = AsAmber, focusedLabelColor = AsAmber, unfocusedLabelColor = AsMuted, focusedLeadingIconColor = AsMuted, unfocusedLeadingIconColor = AsMuted)
