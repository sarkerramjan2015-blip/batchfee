package com.batchfee.edu.ui.batches

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RoutineBg = Color(0xFF07111F)
private val RoutineCard = Color(0xFF0F172A)
private val RoutineCardAlt = Color(0xFF111827)
private val RoutineBorder = Color(0xFF1E293B)
private val RoutineCyan = Color(0xFF22D3EE)
private val RoutineBlue = Color(0xFF3B82F6)
private val RoutineGreen = Color(0xFF22C55E)
private val RoutineText = Color(0xFFF8FAFC)
private val RoutineMuted = Color(0xFF94A3B8)

private enum class RoutineScope { SINGLE, SELECTED, ALL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineScreen(db: AppDatabase, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val instituteId = SessionManager.currentInstituteId.collectAsState().value
    val batches by remember(instituteId) {
        instituteId?.takeIf { it.isNotBlank() }
            ?.let { db.batchDao().getBatchesByInstitute(it) }
            ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    var institute by remember { mutableStateOf<InstituteEntity?>(null) }
    var routineScope by remember { mutableStateOf(RoutineScope.SINGLE) }
    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var selectedBatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchPickerDraft by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchPicker by remember { mutableStateOf(false) }
    var batchMenuExpanded by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }

    LaunchedEffect(instituteId) {
        if (instituteId != null) {
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instituteId)
            institute = withContext(Dispatchers.IO) { db.instituteDao().getInstitute(instituteId) }
        }
    }

    val activeBatches = remember(batches) { batches.filter { it.status == "active" && it.archivedAtMs == null } }
    LaunchedEffect(activeBatches) {
        val activeIds = activeBatches.map { it.id }.toSet()
        if (selectedBatchId !in activeIds) selectedBatchId = activeBatches.firstOrNull()?.id
        selectedBatchIds = selectedBatchIds.intersect(activeIds)
    }
    val selectedBatch = activeBatches.firstOrNull { it.id == selectedBatchId }
    val exportBatches = when (routineScope) {
        RoutineScope.SINGLE -> listOfNotNull(selectedBatch)
        RoutineScope.SELECTED -> activeBatches.filter { it.id in selectedBatchIds }
        RoutineScope.ALL -> activeBatches
    }
    val isOwner = SessionManager.isAdmin()

    fun createPdf(action: (java.io.File, String) -> Boolean) {
        val currentInstitute = institute
        if (!isOwner) {
            scope.launch { snackbarHostState.showSnackbar("Only the institute owner can create routines.") }
            return
        }
        if (currentInstitute == null || exportBatches.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("Choose a batch with saved routine information first.") }
            return
        }
        scope.launch {
            isGenerating = true
            try {
                val routineTitle = when (routineScope) {
                    RoutineScope.SINGLE -> "BATCH CLASS ROUTINE"
                    RoutineScope.SELECTED -> "SELECTED BATCH CLASS ROUTINE"
                    RoutineScope.ALL -> "ALL BATCH CLASS ROUTINE"
                }
                val fileSuffix = when (routineScope) {
                    RoutineScope.SINGLE -> selectedBatch?.name.orEmpty()
                    RoutineScope.SELECTED -> "selected_${exportBatches.size}_batches"
                    RoutineScope.ALL -> "all_batches"
                }
                val file = withContext(Dispatchers.IO) {
                    generateRoutinePdf(context, currentInstitute, exportBatches, routineTitle, fileSuffix)
                }
                val label = when (routineScope) {
                    RoutineScope.SINGLE -> "${selectedBatch!!.name} class routine"
                    RoutineScope.SELECTED -> "${currentInstitute.name} class routine (${exportBatches.size} batches)"
                    RoutineScope.ALL -> "${currentInstitute.name} class routine"
                }
                if (!action(file, label)) snackbarHostState.showSnackbar("Could not open this option on the device.")
            } catch (_: Exception) {
                snackbarHostState.showSnackbar("Routine PDF could not be created. Please try again.")
            } finally {
                isGenerating = false
            }
        }
    }

    Scaffold(
        containerColor = RoutineBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Create Routine", color = RoutineText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Batch schedule, fees and class timing", color = RoutineMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = RoutineText) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = RoutineBg)
            )
        }
    ) { padding ->
        if (!isOwner) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Only the institute owner can create routines.", color = RoutineMuted)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { RoutineIntroCard(activeBatches.size) }
            item {
                Text("Create routine for", color = RoutineText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = routineScope == RoutineScope.SINGLE,
                        onClick = { routineScope = RoutineScope.SINGLE },
                        label = { Text("One batch") },
                        leadingIcon = if (routineScope == RoutineScope.SINGLE) {{ Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp)) }} else null,
                        colors = routineChipColors()
                    )
                    FilterChip(
                        selected = routineScope == RoutineScope.SELECTED,
                        onClick = {
                            routineScope = RoutineScope.SELECTED
                            val activeIds = activeBatches.map { it.id }.toSet()
                            batchPickerDraft = when {
                                selectedBatchIds.isNotEmpty() -> selectedBatchIds.intersect(activeIds)
                                selectedBatchId != null -> setOf(selectedBatchId!!).intersect(activeIds)
                                else -> emptySet()
                            }
                            showBatchPicker = true
                        },
                        label = { Text("Choose batches") },
                        leadingIcon = if (routineScope == RoutineScope.SELECTED) {{ Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp)) }} else null,
                        colors = routineChipColors()
                    )
                    FilterChip(
                        selected = routineScope == RoutineScope.ALL,
                        onClick = { routineScope = RoutineScope.ALL },
                        label = { Text("All batches") },
                        leadingIcon = if (routineScope == RoutineScope.ALL) {{ Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp)) }} else null,
                        colors = routineChipColors()
                    )
                }
            }
            if (routineScope == RoutineScope.SINGLE) {
                item {
                    ExposedDropdownMenuBox(expanded = batchMenuExpanded, onExpandedChange = { batchMenuExpanded = it }) {
                        OutlinedTextField(
                            value = selectedBatch?.name ?: "Select a batch",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Batch") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = batchMenuExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = RoutineText,
                                unfocusedTextColor = RoutineText,
                                focusedBorderColor = RoutineCyan,
                                unfocusedBorderColor = RoutineBorder,
                                focusedContainerColor = RoutineCardAlt,
                                unfocusedContainerColor = RoutineCardAlt,
                                focusedLabelColor = RoutineCyan,
                                unfocusedLabelColor = RoutineMuted
                            )
                        )
                        ExposedDropdownMenu(expanded = batchMenuExpanded, onDismissRequest = { batchMenuExpanded = false }, modifier = Modifier.background(RoutineCard)) {
                            activeBatches.forEach { batch ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(batch.name, color = RoutineText) },
                                    onClick = { selectedBatchId = batch.id; batchMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
            }
            if (exportBatches.isEmpty()) {
                item { EmptyRoutineCard() }
            } else {
                item {
                    Text(
                        when (routineScope) {
                            RoutineScope.SINGLE -> "Routine preview"
                            RoutineScope.SELECTED -> "Selected routine (${exportBatches.size} batches)"
                            RoutineScope.ALL -> "Routine preview (${exportBatches.size} batches)"
                        },
                        color = RoutineText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(exportBatches, key = { it.id }) { batch -> RoutinePreviewCard(batch) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { createPdf { file, label -> printRoutinePdf(context, file, label) } },
                            enabled = !isGenerating,
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(13.dp),
                            border = BorderStroke(1.dp, RoutineCyan.copy(alpha = 0.75f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoutineCyan)
                        ) {
                            Icon(Icons.Filled.Print, null, Modifier.size(19.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Print PDF", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { createPdf { file, label -> shareRoutinePdf(context, file, label) } },
                            enabled = !isGenerating,
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RoutineBlue, contentColor = Color.White)
                        ) {
                            if (isGenerating) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            else {
                                Icon(Icons.Filled.IosShare, null, Modifier.size(19.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Share PDF", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }

    if (showBatchPicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showBatchPicker = false
                if (selectedBatchIds.isEmpty()) routineScope = RoutineScope.SINGLE
            },
            containerColor = RoutineCard,
            title = { Text("Choose batches", color = RoutineText, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Tick the batches you want in this routine.", color = RoutineMuted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { batchPickerDraft = activeBatches.map { it.id }.toSet() }) {
                            Text("Select all")
                        }
                        TextButton(onClick = { batchPickerDraft = emptySet() }) {
                            Text("Clear")
                        }
                    }
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(activeBatches, key = { it.id }) { batch ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        batchPickerDraft = if (batch.id in batchPickerDraft) {
                                            batchPickerDraft - batch.id
                                        } else {
                                            batchPickerDraft + batch.id
                                        }
                                    }
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = batch.id in batchPickerDraft, onCheckedChange = null)
                                Column(Modifier.weight(1f)) {
                                    Text(batch.name, color = RoutineText, fontWeight = FontWeight.Medium)
                                    Text(listOfNotNull(batch.className, batch.subject).joinToString(" - ").ifBlank { "Batch schedule" }, color = RoutineMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedBatchIds = batchPickerDraft
                        showBatchPicker = false
                    },
                    enabled = batchPickerDraft.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = RoutineBlue),
                ) {
                    Text("Use ${batchPickerDraft.size} batches")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBatchPicker = false
                    if (selectedBatchIds.isEmpty()) routineScope = RoutineScope.SINGLE
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RoutineIntroCard(batchCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = RoutineCard),
        border = BorderStroke(1.dp, RoutineBorder)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).clip(RoundedCornerShape(13.dp)).background(RoutineBlue.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CalendarMonth, null, tint = RoutineCyan, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ready from batch settings", color = RoutineText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("$batchCount active batches · days, time, duration and fees", color = RoutineMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyRoutineCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = RoutineCard),
        border = BorderStroke(1.dp, RoutineBorder)
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Schedule, null, tint = RoutineMuted.copy(alpha = 0.55f), modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(8.dp))
            Text("No active batch is available yet.", color = RoutineMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RoutinePreviewCard(batch: BatchEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = RoutineCard),
        border = BorderStroke(1.dp, RoutineBorder)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(35.dp).clip(CircleShape).background(RoutineCyan.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Schedule, null, tint = RoutineCyan, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(batch.name, color = RoutineText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(batch.className, batch.subject).joinToString(" · ").ifBlank { "Class not specified" }, color = RoutineMuted, fontSize = 11.sp)
                }
                Text("${routineDayCount(batch)} days", color = RoutineCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            RoutineDetail("Class days", routineDaysForUi(batch))
            RoutineDetail("Class time", routineTimeForUi(batch))
            RoutineDetail("Duration", routineDurationForUi(batch))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                RoutineFee("Monthly fee", batch.monthlyFeeAmount, Modifier.weight(1f))
                RoutineFee("Admission fee", batch.admissionFeeAmount, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RoutineDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = RoutineMuted, fontSize = 11.sp)
        Text(value, color = RoutineText, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RoutineFee(label: String, amount: Double, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(RoutineCardAlt).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, color = RoutineMuted, fontSize = 10.sp)
        Text("BDT ${java.text.DecimalFormat("#,##0.##").format(amount)}", color = RoutineGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun routineChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = RoutineCyan.copy(alpha = 0.16f),
    selectedLabelColor = RoutineCyan,
    selectedLeadingIconColor = RoutineCyan,
    labelColor = RoutineMuted
)

private fun routineDayCount(batch: BatchEntity): Int = batch.scheduleDays?.split(",")?.count { it.trim().isNotEmpty() } ?: 0

private fun routineDaysForUi(batch: BatchEntity): String = batch.scheduleDays
    ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.joinToString(", ")
    ?.ifBlank { "Not set" } ?: "Not set"

private fun routineTimeForUi(batch: BatchEntity): String {
    val start = routineFormatTime(batch.startTime)
    val end = routineFormatTime(batch.endTime)
    return if (start != null && end != null) "$start - $end" else "Not set"
}

private fun routineDurationForUi(batch: BatchEntity): String {
    val start = routineTimeMinutes(batch.startTime) ?: return "Not set"
    val end = routineTimeMinutes(batch.endTime) ?: return "Not set"
    if (end <= start) return "Not set"
    val minutes = end - start
    return when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} hr"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
}

private fun routineTimeMinutes(value: String?): Int? {
    val parts = value?.split(":") ?: return null
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

private fun routineFormatTime(value: String?): String? {
    val total = routineTimeMinutes(value) ?: return null
    val hour = total / 60
    val minute = total % 60
    val suffix = if (hour < 12) "AM" else "PM"
    val displayHour = when (val normalized = hour % 12) { 0 -> 12; else -> normalized }
    return String.format(java.util.Locale.US, "%d:%02d %s", displayHour, minute, suffix)
}
