package com.batchfee.edu.ui.homework

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
import com.batchfee.edu.data.models.HomeworkEntity
import com.batchfee.edu.domain.PasswordHasher
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.*

// Premium dark palette matching DashboardScreen
private val HwBg        = Color(0xFF07111F)
private val HwCard      = Color(0xFF0F172A)
private val HwCardAlt   = Color(0xFF111827)
private val HwStroke    = Color(0xFF1E293B)
private val HwCyan      = Color(0xFF22D3EE)
private val HwBlue      = Color(0xFF3B82F6)
private val HwGreen     = Color(0xFF22C55E)
private val HwRed       = Color(0xFFEF4444)
private val HwAmber     = Color(0xFFF59E0B)
private val HwWhite     = Color(0xFFF8FAFC)
private val HwMuted     = Color(0xFF94A3B8)
private val HwDim       = Color(0xFF64748B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddHomeworkScreen(db: AppDatabase, onBack: () -> Unit) {
    val instId = SessionManager.currentInstituteId.value ?: ""
    var batches by remember { mutableStateOf<List<BatchEntity>>(emptyList()) }
    var selectedBatch by remember { mutableStateOf<BatchEntity?>(null) }
    var title by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var className by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var bookPage by remember { mutableStateOf("") }
    var hasDueDate by remember { mutableStateOf(false) }
    var dueDateMs by remember { mutableStateOf<Long?>(null) }
    var requiresSubmission by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var batchExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(instId) {
        try {
            batches = withContext(Dispatchers.IO) { db.batchDao().getBatchesByInstituteOnce(instId) }
        } catch (_: Exception) {}
    }

    Scaffold(
        containerColor = HwBg,
        topBar = {
            TopAppBar(
                title = { Text("Add Homework", color = HwWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = HwMuted) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = HwBg)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(HwBg).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header card
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = HwCard), border = BorderStroke(1.dp, HwStroke)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(HwBlue.copy(alpha = 0.15f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Home, null, tint = HwBlue, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Homework", color = HwWhite, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        Text("Assign practice work for students", color = HwMuted, fontSize = 12.sp)
                    }
                }
            }

            // Batch dropdown
            ExposedDropdownMenuBox(expanded = batchExpanded, onExpandedChange = { batchExpanded = it }) {
                OutlinedTextField(
                    value = selectedBatch?.name ?: "All Batches",
                    onValueChange = {}, readOnly = true,
                    label = { Text("Batch", color = HwMuted) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(batchExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    shape = RoundedCornerShape(14.dp),
                    colors = darkFieldColors()
                )
                ExposedDropdownMenu(expanded = batchExpanded, onDismissRequest = { batchExpanded = false }, containerColor = HwCard) {
                    DropdownMenuItem(text = { Text("All Batches (everyone)", color = HwWhite) }, onClick = { selectedBatch = null; batchExpanded = false })
                    batches.forEach { b ->
                        DropdownMenuItem(text = { Text("${b.name} (${b.batchCode})", color = HwWhite) }, onClick = { selectedBatch = b; batchExpanded = false })
                    }
                }
            }

            // Title
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title *", color = HwMuted) }, placeholder = { Text("e.g. Chapter 3 Practice", color = HwDim) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), singleLine = true, colors = darkFieldColors())

            // Subject + Class row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Subject", color = HwMuted) }, placeholder = { Text("e.g. Mathematics", color = HwDim) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true, colors = darkFieldColors())
                OutlinedTextField(value = className, onValueChange = { className = it }, label = { Text("Class/Section", color = HwMuted) }, placeholder = { Text("Class 7-A", color = HwDim) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true, colors = darkFieldColors())
            }

            // Instructions
            OutlinedTextField(value = instructions, onValueChange = { instructions = it }, label = { Text("Instructions/Description", color = HwMuted) }, placeholder = { Text("What students need to do...", color = HwDim) }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), shape = RoundedCornerShape(14.dp), minLines = 3, colors = darkFieldColors())
            OutlinedTextField(value = bookPage, onValueChange = { bookPage = it }, label = { Text("Book / Page", color = HwMuted) }, placeholder = { Text("e.g. Page 25-27", color = HwDim) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), singleLine = true, colors = darkFieldColors())

            // Due date
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = HwCard), border = BorderStroke(1.dp, HwStroke)) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text("Due Date", color = HwWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text("Set a deadline for this homework", color = HwMuted, fontSize = 11.sp) }
                        Switch(checked = hasDueDate, onCheckedChange = { hasDueDate = it; if (it && dueDateMs == null) dueDateMs = System.currentTimeMillis() + 86400000; if (!it) dueDateMs = null }, colors = SwitchDefaults.colors(checkedThumbColor = HwBlue, checkedTrackColor = HwBlue.copy(alpha = 0.3f)))
                    }
                    if (hasDueDate && dueDateMs != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, HwBlue.copy(alpha = 0.4f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = HwBlue)) {
                            Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                            Text(java.text.SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(dueDateMs!!)), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Submission toggle
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = HwCard), border = BorderStroke(1.dp, HwStroke)) {
                Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text("Requires Submission", color = HwWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text("Student must upload their work", color = HwMuted, fontSize = 11.sp) }
                    Switch(checked = requiresSubmission, onCheckedChange = { requiresSubmission = it }, colors = SwitchDefaults.colors(checkedThumbColor = HwGreen, checkedTrackColor = HwGreen.copy(alpha = 0.3f)))
                }
            }

            if (saveError != null) {
                Box(Modifier.fillMaxWidth().background(Color(0x22EF4444), RoundedCornerShape(10.dp)).padding(12.dp)) { Text(saveError!!, color = Color(0xFFFCA5A5), fontSize = 13.sp) }
            }

            // Save button
            Button(onClick = {
                if (title.isBlank()) { saveError = "Title is required"; return@Button }
                isSaving = true; saveError = null
                scope.launch {
                    val hw = HomeworkEntity(
                        id = UUID.randomUUID().toString(), instituteId = instId, batchId = selectedBatch?.id,
                        title = title.trim(), subject = subject.takeIf { it.isNotBlank() }, className = className.takeIf { it.isNotBlank() },
                        instructions = instructions.trim(), bookPage = bookPage.takeIf { it.isNotBlank() },
                        startDateMs = System.currentTimeMillis(), dueDateMs = if (hasDueDate) dueDateMs else null,
                        requiresSubmission = requiresSubmission, status = "active", attachmentUri = null,
                        createdAtMs = System.currentTimeMillis(), updatedAtMs = System.currentTimeMillis(), archivedAtMs = null
                    )
                    try {
                        // Student work is live data. Do not show a local-only
                        // save as published when the shared cloud write failed.
                        WorkCloudSyncHelper.syncHomework(hw)
                        withContext(Dispatchers.IO) { db.homeworkDao().upsert(hw) }
                        onBack()
                    } catch (_: Exception) {
                        saveError = "Could not share homework with students. Check your connection and try again."
                    } finally {
                        isSaving = false
                    }
                }
            }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), contentPadding = PaddingValues(0.dp)) {
                Box(Modifier.fillMaxSize().shadow(12.dp, RoundedCornerShape(16.dp), spotColor = HwBlue.copy(alpha = 0.4f)).background(Brush.horizontalGradient(listOf(HwBlue, HwCyan)), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("Save Homework", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = dueDateMs ?: System.currentTimeMillis())
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = { TextButton(onClick = { dueDateMs = dpState.selectedDateMillis; showDatePicker = false }) { Text("OK") } }) { DatePicker(state = dpState) }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = HwCardAlt, unfocusedContainerColor = HwCardAlt,
    focusedBorderColor = HwBlue, unfocusedBorderColor = HwStroke,
    focusedTextColor = HwWhite, unfocusedTextColor = HwWhite,
    cursorColor = HwBlue, focusedLabelColor = HwBlue, unfocusedLabelColor = HwMuted,
    focusedLeadingIconColor = HwMuted, unfocusedLeadingIconColor = HwMuted
)
