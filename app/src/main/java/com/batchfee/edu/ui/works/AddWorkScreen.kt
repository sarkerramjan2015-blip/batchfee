package com.batchfee.edu.ui.works

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.BatchEntity
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWorkScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: WorksViewModel = viewModel(factory = WorksViewModelFactory(db))
    val batches by viewModel.batches.collectAsState()

    var workType by remember { mutableStateOf("HOMEWORK") }
    var selectedBatch by remember { mutableStateOf<BatchEntity?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var hasDueDate by remember { mutableStateOf(false) }
    var dueDateMs by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var batchDropdown by remember { mutableStateOf(false) }
    var typeDropdown by remember { mutableStateOf(false) }
    var showBatchField by remember { mutableStateOf(false) }

    val workTypeLabel = if (workType == "HOMEWORK") "Homework" else "Assignment"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add $workTypeLabel") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Type selector
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = workType == "HOMEWORK", onClick = { workType = "HOMEWORK" },
                    label = { Text("Homework") }, leadingIcon = { Icon(Icons.Filled.Home, null, Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f))
                FilterChip(selected = workType == "ASSIGNMENT", onClick = { workType = "ASSIGNMENT" },
                    label = { Text("Assignment") }, leadingIcon = { Icon(Icons.Filled.Assignment, null, Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f))
            }

            // Batch selector
            var batchExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = batchExpanded, onExpandedChange = { batchExpanded = it }) {
                OutlinedTextField(
                    value = selectedBatch?.name ?: "All Batches",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Batch (optional)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = batchExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(expanded = batchExpanded, onDismissRequest = { batchExpanded = false }) {
                    DropdownMenuItem(text = { Text("All Batches (everyone)") }, onClick = {
                        selectedBatch = null; batchExpanded = false
                    })
                    batches.forEach { b ->
                        DropdownMenuItem(text = { Text("${b.name} (${b.batchCode})") }, onClick = {
                            selectedBatch = b; batchExpanded = false
                        })
                    }
                }
            }

            // Title
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Title") },
                placeholder = { Text("e.g. Math exercise chapter 5") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            // Description
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("Description") },
                placeholder = { Text("Details about the work...") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                shape = RoundedCornerShape(12.dp),
                minLines = 4
            )

            // Due date
            var showDdPicker by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Set due date", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = hasDueDate, onCheckedChange = {
                    hasDueDate = it
                    if (it && dueDateMs == null) dueDateMs = System.currentTimeMillis() + 86400000
                    if (!it) dueDateMs = null
                })
            }
            if (hasDueDate && dueDateMs != null) {
                OutlinedButton(
                    onClick = { showDdPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(java.text.SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(dueDateMs!!)))
                }
            }

            if (saveError != null) {
                Text(saveError!!, color = Color(0xFFEF4444), fontSize = 13.sp)
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (title.isBlank()) { saveError = "Title is required."; return@Button }
                    isSaving = true; saveError = null
                    viewModel.addWork(
                        batchId = selectedBatch?.id,
                        type = workType,
                        title = title,
                        description = description,
                        dueDateMs = if (hasDueDate) dueDateMs else null,
                        onSuccess = { isSaving = false; onBack() },
                        onError = { saveError = it; isSaving = false }
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = !isSaving
            ) {
                if (isSaving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Add $workTypeLabel", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }

    if (showDatePicker) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = dueDateMs ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dueDateMs = dpState.selectedDateMillis
                    showDatePicker = false
                }) { Text("OK") }
            }
        ) { DatePicker(state = dpState) }
    }
}
