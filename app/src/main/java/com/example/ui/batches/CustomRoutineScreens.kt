package com.batchfee.edu.ui.batches

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.CustomRoutineEntryEntity
import com.batchfee.edu.data.models.CustomRoutineEntity
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.domain.SessionManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ── Palette (same as app design system) ──────────────────────
private val CrBg = Color(0xFF07111F)
private val CrCard = Color(0xFF0F172A)
private val CrCardAlt = Color(0xFF111827)
private val CrCardHi = Color(0xFF132033)
private val CrBorder = Color(0xFF1E293B)
private val CrCyan = Color(0xFF22D3EE)
private val CrBlue = Color(0xFF3B82F6)
private val CrGreen = Color(0xFF10B981)
private val CrAmber = Color(0xFFF59E0B)
private val CrRed = Color(0xFFEF4444)
private val CrViolet = Color(0xFF8B5CF6)
private val CrText = Color(0xFFF8FAFC)
private val CrMuted = Color(0xFF94A3B8)

val CR_DAY_NAMES = listOf("Saturday", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday")

/** Time slots for start/end selection (05:00 → 23:00, 30-minute steps). */
private val CR_TIME_SLOTS = (5 * 60..23 * 60 step 30).map { minutesOfDay ->
    "%02d:%02d".format(minutesOfDay / 60, minutesOfDay % 60) to minutesOfDay
}

private fun minutesToLabel(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
private fun parseTimeToMinutes(raw: String): Int? =
    raw.trim().takeIf { Regex("""^\d{1,2}:\d{2}$""").matches(it) }?.let {
        val parts = it.split(":")
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

/** Opens a real 24-hour clock time picker so users can set ANY minute (not only 30-min slots). */
private fun showTimePicker(context: Context, initial: String?, onPick: (String) -> Unit) {
    val initialMinutes = parseTimeToMinutes(initial.orEmpty()) ?: (9 * 60)
    android.app.TimePickerDialog(
        context,
        { _, hour, minute -> onPick(minutesToLabel(hour * 60 + minute)) },
        initialMinutes / 60,
        initialMinutes % 60,
        true
    ).show()
}

private val CR_SUBJECT_SUGGESTIONS = listOf(
    "Bangla", "English", "Mathematics", "General Mathematics", "Higher Mathematics",
    "Physics", "Chemistry", "Biology", "General Science", "ICT",
    "Bangladesh & Global Studies", "Islamic Studies", "Hindu Religion", "Accounting",
    "Finance & Banking", "Economics", "Geography", "History", "Civics",
    "Statistics", "Psychology", "Sociology", "Logic", "Agriculture",
    "Home Science", "Art & Craft", "Music", "Physical Education", "Computer Studies"
)

@Composable
private fun crTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = CrText,
    unfocusedTextColor = CrText,
    focusedBorderColor = CrCyan,
    unfocusedBorderColor = CrBorder,
    focusedContainerColor = CrCardAlt,
    unfocusedContainerColor = CrCardAlt,
    focusedLabelColor = CrCyan,
    unfocusedLabelColor = CrMuted,
    cursorColor = CrCyan
)

// ═══════════════════════════════════════════════════════════════
//  Custom Routine List
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRoutineListScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onView: (String) -> Unit
) {
    val viewModel: CustomRoutineViewModel = viewModel(factory = CustomRoutineViewModelFactory(db))
    val routines by viewModel.routines.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val isOwner = SessionManager.isAdmin()

    var pendingDelete by remember { mutableStateOf<CustomRoutineEntity?>(null) }
    var entryCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(Unit) { viewModel.loadRoutines() }
    LaunchedEffect(routines) {
        val dao = db.customRoutineDao()
        entryCounts = routines.associate { r ->
            r.id to runCatching { dao.getEntriesOnce(r.id).size }.getOrDefault(0)
        }
    }

    Scaffold(
        containerColor = CrBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Custom Routines", color = CrText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Owner-created day-wise class routines", color = CrMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = CrText) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CrBg)
            )
        },
        floatingActionButton = {
            if (isOwner) {
                FloatingActionButton(
                    onClick = onCreate,
                    containerColor = CrCyan,
                    contentColor = CrBg
                ) { Icon(Icons.Filled.Add, "Create Custom Routine") }
            }
        }
    ) { padding ->
        if (routines.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(CrCyan.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.CalendarMonth, null, tint = CrCyan, modifier = Modifier.size(32.dp)) }
                    Spacer(Modifier.height(14.dp))
                    Text("No custom routines yet", color = CrText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (isOwner) "Tap + to create your first day-wise routine" else "Your institute owner hasn't created any custom routine yet",
                        color = CrMuted, fontSize = 13.sp, textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(routines, key = { it.id }) { routine ->
                    val totalEntries = entryCounts[routine.id] ?: 0
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onView(routine.id) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CrCard),
                        border = BorderStroke(1.dp, CrBorder)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
                                    .background(Brush.linearGradient(listOf(CrBlue, CrCyan))),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.CalendarMonth, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(routine.routineName, color = CrText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                val sub = listOfNotNull(
                                    routine.className,
                                    routine.section?.takeIf { it.isNotBlank() },
                                    routine.academicSession?.takeIf { it.isNotBlank() }
                                ).joinToString(" • ")
                                Text(sub, color = CrMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("$totalEntries classes", color = CrCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Updated ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(routine.updatedAtMs))}",
                                        color = CrMuted, fontSize = 11.sp
                                    )
                                }
                            }
                            if (isOwner) {
                                Box {
                                    var menuOpen by remember { mutableStateOf(false) }
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Filled.MoreVert, "Options", tint = CrMuted)
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = CrCardAlt) {
                                        DropdownMenuItem(
                                            text = { Text("Edit", color = CrText) },
                                            leadingIcon = { Icon(Icons.Filled.Edit, null, tint = CrCyan) },
                                            onClick = { menuOpen = false; onEdit(routine.id) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete", color = CrRed) },
                                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = CrRed) },
                                            onClick = { menuOpen = false; pendingDelete = routine }
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

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = CrCard,
            title = { Text("Delete Routine?", color = CrRed, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CrRed.copy(alpha = 0.12f))
                            .border(1.dp, CrRed.copy(alpha = 0.4f), RoundedCornerShape(10.dp)).padding(10.dp)
                    ) {
                        Text(pendingDelete?.routineName.orEmpty(), color = CrRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("This routine and all its class entries will be deleted permanently.", color = CrMuted, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val routine = pendingDelete ?: return@TextButton
                    pendingDelete = null
                    viewModel.deleteRoutine(
                        routine.id,
                        onSuccess = { scope.launch { snackbarHostState.showSnackbar("Routine deleted.") } },
                        onError = { scope.launch { snackbarHostState.showSnackbar(it) } }
                    )
                }) { Text("Yes, Delete", color = CrRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep Routine", color = CrMuted) }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  Custom Routine Editor (Create / Edit)
// ═══════════════════════════════════════════════════════════════
private data class EntryDraft(
    val id: String = UUID.randomUUID().toString(),
    val subject: String = "",
    val teacherId: String? = null,
    val startLabel: String = "",
    val endLabel: String = ""
) {
    fun startMinutes(): Int? = parseTimeToMinutes(startLabel)
    fun endMinutes(): Int? = parseTimeToMinutes(endLabel)
    fun isComplete(): Boolean =
        subject.isNotBlank() && startMinutes() != null && endMinutes() != null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRoutineEditorScreen(
    db: AppDatabase,
    routineId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit
) {
    val viewModel: CustomRoutineViewModel = viewModel(factory = CustomRoutineViewModelFactory(db))
    val existingRoutine by viewModel.selectedRoutine.collectAsState()
    val existingEntries by viewModel.entries.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val isOwner = SessionManager.isAdmin()
    val isEdit = routineId != null

    var routineName by remember { mutableStateOf("") }
    var className by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("") }
    var academicSession by remember { mutableStateOf("") }
    var effectiveDateMs by remember { mutableStateOf<Long?>(null) }
    var periodCount by remember { mutableStateOf(7) }
    var staffList by remember { mutableStateOf<List<StaffEntity>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }

    // Day index -> list of entry drafts. Inner lists are SnapshotStateList so
    // add/remove recompose the affected day immediately instead of waiting for
    // an unrelated state change.
    val dayEntries = remember {
        mutableStateListOf<androidx.compose.runtime.snapshots.SnapshotStateList<EntryDraft>>().apply {
            repeat(7) { add(mutableStateListOf()) }
        }
    }
    var expandedDays by remember { mutableStateOf(setOf<Int>()) }

    var formLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(routineId) {
        if (routineId != null) viewModel.loadRoutine(routineId)
    }
    LaunchedEffect(Unit) {
        val instId = SessionManager.currentInstituteId.value
        staffList = instId?.let { db.staffDao().getStaffByInstituteAsList(it) }.orEmpty()
            .filter { it.status == "active" }
    }
    LaunchedEffect(existingRoutine, existingEntries, isEdit) {
        val loaded = existingRoutine
        if (isEdit && loaded != null && !formLoaded) {
            routineName = loaded.routineName
            className = loaded.className
            section = loaded.section.orEmpty()
            academicSession = loaded.academicSession.orEmpty()
            effectiveDateMs = loaded.effectiveDateMs
            periodCount = loaded.periodCount.coerceIn(1, 14)
            for (i in 0 until 7) dayEntries[i].clear()
            existingEntries.forEach { entry ->
                val list = dayEntries.getOrNull(entry.dayIndex) ?: return@forEach
                list.add(
                    EntryDraft(
                        id = entry.id,
                        subject = entry.subjectName,
                        teacherId = entry.teacherId,
                        startLabel = minutesToLabel(entry.startMinutes),
                        endLabel = minutesToLabel(entry.endMinutes)
                    )
                )
            }
            formLoaded = true
        }
    }

    if (!isOwner) {
        Scaffold(containerColor = CrBg, topBar = {
            TopAppBar(
                title = { Text("Custom Routine", color = CrText, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = CrText) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CrBg)
            )
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Only the institute owner can create or edit custom routines.", color = CrMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
        }
        return
    }

    fun validateAndSave() {
        if (saving) return
        if (routineName.isBlank()) { scope.launch { snackbarHostState.showSnackbar("Routine name is required.") }; return }
        if (className.isBlank()) { scope.launch { snackbarHostState.showSnackbar("Class name is required.") }; return }

        // Overlap validation per day
        for (day in 0 until 7) {
            val sorted = dayEntries[day].filter { it.isComplete() }.sortedBy { it.startMinutes() }
            for (i in 0 until sorted.size - 1) {
                val current = sorted[i]
                val next = sorted[i + 1]
                if (next.startMinutes()!! < current.endMinutes()!!) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Overlap on ${CR_DAY_NAMES[day]}: '${current.subject}' (${current.startLabel}–${current.endLabel}) clashes with '${next.subject}' (${next.startLabel}–${next.endLabel}). Adjust the times."
                        )
                    }
                    return
                }
            }
            sorted.forEach { draft ->
                if (draft.endMinutes()!! <= draft.startMinutes()!!) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "${CR_DAY_NAMES[day]} • ${draft.subject}: end time must be after start time."
                        )
                    }
                    return
                }
            }
        }

        val allEntries = mutableListOf<CustomRoutineEntryEntity>()
        for (day in 0 until 7) {
            dayEntries[day].forEachIndexed { index, draft ->
                if (draft.isComplete()) {
                    val teacher = staffList.find { it.id == draft.teacherId }
                    allEntries.add(
                        CustomRoutineEntryEntity(
                            id = draft.id,
                            routineId = routineId.orEmpty(),
                            instituteId = SessionManager.currentInstituteId.value.orEmpty(),
                            dayIndex = day,
                            subjectName = draft.subject.trim(),
                            teacherName = teacher?.fullName ?: draft.subject.trim(),
                            teacherId = draft.teacherId,
                            startMinutes = draft.startMinutes()!!,
                            endMinutes = draft.endMinutes()!!,
                            sortOrder = index
                        )
                    )
                }
            }
        }

        saving = true
        viewModel.saveRoutine(
            routineId = routineId,
            routineName = routineName,
            className = className,
            section = section,
            academicSession = academicSession,
            periodCount = periodCount,
            effectiveDateMs = effectiveDateMs,
            entries = allEntries,
            onSuccess = { savedId -> saving = false; onSaved(savedId) },
            onError = { saving = false; scope.launch { snackbarHostState.showSnackbar(it) } }
        )
    }

    Scaffold(
        containerColor = CrBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit Custom Routine" else "Create Custom Routine", color = CrText, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = CrText) } },
                actions = {
                    TextButton(onClick = { validateAndSave() }, enabled = !saving) {
                        if (saving) CircularProgressIndicator(Modifier.size(16.dp), color = CrCyan, strokeWidth = 2.dp)
                        else Text("Save", color = CrCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CrBg)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("Routine Details", color = CrText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = routineName,
                onValueChange = { routineName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Routine Name", fontSize = 11.sp) },
                placeholder = { Text("e.g. Morning Shift Routine 2026", color = CrMuted) },
                colors = crTextFieldColors(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = className,
                onValueChange = { className = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Class Name", fontSize = 11.sp) },
                placeholder = { Text("e.g. Class 8, SSC 2026", color = CrMuted) },
                colors = crTextFieldColors(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = section,
                    onValueChange = { section = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Section (optional)", fontSize = 10.sp) },
                    colors = crTextFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = academicSession,
                    onValueChange = { academicSession = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Session (optional)", fontSize = 10.sp) },
                    placeholder = { Text("2026", color = CrMuted) },
                    colors = crTextFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CrCardAlt)
                    .border(1.dp, CrBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Effective Date (optional)", color = CrMuted, fontSize = 11.sp)
                    Text(
                        effectiveDateMs?.let { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it)) } ?: "Not set",
                        color = CrText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(onClick = {
                    android.app.DatePickerDialog(
                        context,
                        { _, y, m, d ->
                            val cal = java.util.Calendar.getInstance()
                            cal.set(y, m, d, 0, 0, 0)
                            effectiveDateMs = cal.timeInMillis
                        },
                        java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
                        java.util.Calendar.getInstance().get(java.util.Calendar.MONTH),
                        java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
                    ).show()
                }) { Text(if (effectiveDateMs != null) "Change" else "Pick Date", color = CrCyan, fontSize = 12.sp) }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CrCardAlt)
                    .border(1.dp, CrBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Number of Periods", color = CrMuted, fontSize = 11.sp)
                    Text("Periods per day shown in the routine table", color = CrText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { if (periodCount > 1) periodCount-- },
                        enabled = periodCount > 1
                    ) {
                        Icon(Icons.Filled.Remove, "Fewer periods", tint = if (periodCount > 1) CrCyan else CrMuted)
                    }
                    Text(
                        "$periodCount",
                        color = CrText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(28.dp),
                        textAlign = TextAlign.Center
                    )
                    IconButton(
                        onClick = { if (periodCount < 14) periodCount++ },
                        enabled = periodCount < 14
                    ) {
                        Icon(Icons.Filled.Add, "More periods", tint = if (periodCount < 14) CrCyan else CrMuted)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Day-wise Classes", color = CrText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text("A day can have zero or many classes. Each entry needs Subject, Teacher, Start and End time.", color = CrMuted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))

            for (day in 0 until 7) {
                DayEntryCard(
                    dayName = CR_DAY_NAMES[day],
                    entries = dayEntries[day],
                    staffList = staffList,
                    expanded = day in expandedDays,
                    onToggle = {
                        expandedDays = if (day in expandedDays) expandedDays - day else expandedDays + day
                    },
                    onAdd = {
                        dayEntries[day].add(EntryDraft())
                        expandedDays = expandedDays + day
                    },
                    onUpdate = { index, draft -> dayEntries[day][index] = draft },
                    onRemove = { index -> dayEntries[day].removeAt(index) }
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.horizontalGradient(listOf(CrBlue, CrCyan)))
                    .clickable(enabled = !saving) { validateAndSave() },
                contentAlignment = Alignment.Center
            ) {
                if (saving) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text(if (isEdit) "Save Changes" else "Save Routine", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Day entry card with unlimited classes
// ═══════════════════════════════════════════════════════════════
@Composable
private fun DayEntryCard(
    dayName: String,
    entries: List<EntryDraft>,
    staffList: List<StaffEntity>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onUpdate: (Int, EntryDraft) -> Unit,
    onRemove: (Int) -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CrCard),
        border = BorderStroke(1.dp, if (entries.any { it.isComplete() }) CrBlue.copy(alpha = 0.5f) else CrBorder)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggle)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                    null, tint = CrCyan, modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(dayName, color = CrText, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(CrCyan.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        "${entries.count { it.isComplete() }} class${if (entries.count { it.isComplete() } != 1) "es" else ""}",
                        color = CrCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                if (entries.isEmpty()) {
                    Text("No classes on $dayName.", color = CrMuted, fontSize = 12.sp)
                }
                entries.forEachIndexed { index, draft ->
                    EntryDraftRow(
                        draft = draft,
                        staffList = staffList,
                        onUpdate = { onUpdate(index, it) },
                        onRemove = { onRemove(index) }
                    )
                    if (index < entries.lastIndex) {
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(color = CrBorder)
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(10.dp))
                        .background(CrCardHi).border(1.dp, CrCyan.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, null, tint = CrCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Class", color = CrCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryDraftRow(
    draft: EntryDraft,
    staffList: List<StaffEntity>,
    onUpdate: (EntryDraft) -> Unit,
    onRemove: () -> Unit,
    showRemove: Boolean = true
) {
    val context = LocalContext.current
    var subjectDropdownOpen by remember { mutableStateOf(false) }
    var teacherDropdownOpen by remember { mutableStateOf(false) }
    var startDropdownOpen by remember { mutableStateOf(false) }
    var endDropdownOpen by remember { mutableStateOf(false) }

    val startMin = draft.startMinutes()
    val endMin = draft.endMinutes()
    val invalidTime = startMin != null && endMin != null && endMin <= startMin
    val selectedTeacher = staffList.find { it.id == draft.teacherId }

    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Subject (dropdown suggestions + free type)
            Box(Modifier.weight(1.4f)) {
                OutlinedTextField(
                    value = draft.subject,
                    onValueChange = { onUpdate(draft.copy(subject = it)) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    label = { Text("Subject", fontSize = 10.sp) },
                    colors = crTextFieldColors(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    trailingIcon = {
                        Text("▾", color = CrCyan, fontSize = 12.sp, modifier = Modifier.clickable { subjectDropdownOpen = true })
                    }
                )
                DropdownMenu(
                    expanded = subjectDropdownOpen,
                    onDismissRequest = { subjectDropdownOpen = false },
                    containerColor = CrCardAlt,
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    CR_SUBJECT_SUGGESTIONS.forEach { subj ->
                        DropdownMenuItem(
                            text = { Text(subj, color = CrText, fontSize = 12.sp) },
                            onClick = { onUpdate(draft.copy(subject = subj)); subjectDropdownOpen = false }
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            // Teacher dropdown (active staff)
            Box(Modifier.weight(1.4f)) {
                Row(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(10.dp)).background(CrCardAlt)
                        .border(1.dp, CrBorder, RoundedCornerShape(10.dp))
                        .clickable { teacherDropdownOpen = true }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedTeacher != null) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(CrCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) { Text(crInitials(selectedTeacher.fullName), color = CrCyan, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold) }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        selectedTeacher?.fullName ?: "Teacher",
                        color = if (selectedTeacher != null) CrText else CrMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text("▾", color = CrCyan, fontSize = 12.sp)
                }
                DropdownMenu(
                    expanded = teacherDropdownOpen,
                    onDismissRequest = { teacherDropdownOpen = false },
                    containerColor = CrCardAlt,
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    staffList.forEach { staff ->
                        DropdownMenuItem(
                            text = { Text(staff.fullName, color = CrText, fontSize = 12.sp) },
                            onClick = { onUpdate(draft.copy(teacherId = staff.id)); teacherDropdownOpen = false }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("— No teacher —", color = CrMuted, fontSize = 12.sp) },
                        onClick = { onUpdate(draft.copy(teacherId = null)); teacherDropdownOpen = false }
                    )
                }
            }
            if (showRemove) {
                IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Delete, "Remove", tint = CrRed, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Start time — tapping the box opens a real clock time picker (any minute).
            // The ▾ still lists the quick 30-minute slots for fast entry.
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(CrCardAlt)
                        .border(1.dp, if (invalidTime) CrRed else CrBorder, RoundedCornerShape(10.dp))
                        .clickable {
                            showTimePicker(context, draft.startLabel) { label -> onUpdate(draft.copy(startLabel = label)) }
                        }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Schedule, null, tint = CrMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text("Start", color = CrMuted, fontSize = 9.sp)
                        Text(
                            draft.startLabel.ifBlank { "--:--" },
                            color = if (draft.startLabel.isBlank()) CrMuted else CrText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("▾", color = CrCyan, fontSize = 11.sp, modifier = Modifier.clickable { startDropdownOpen = true })
                }
                DropdownMenu(
                    expanded = startDropdownOpen,
                    onDismissRequest = { startDropdownOpen = false },
                    containerColor = CrCardAlt,
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("Pick any time…", color = CrCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        onClick = {
                            startDropdownOpen = false
                            showTimePicker(context, draft.startLabel) { label -> onUpdate(draft.copy(startLabel = label)) }
                        }
                    )
                    HorizontalDivider(color = CrBorder)
                    CR_TIME_SLOTS.forEach { (label, _) ->
                        DropdownMenuItem(
                            text = { Text(label, color = CrText, fontSize = 12.sp) },
                            onClick = { onUpdate(draft.copy(startLabel = label)); startDropdownOpen = false }
                        )
                    }
                }
            }
            // End time
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(CrCardAlt)
                        .border(1.dp, if (invalidTime) CrRed else CrBorder, RoundedCornerShape(10.dp))
                        .clickable {
                            showTimePicker(context, draft.endLabel) { label -> onUpdate(draft.copy(endLabel = label)) }
                        }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Schedule, null, tint = CrMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text("End", color = CrMuted, fontSize = 9.sp)
                        Text(
                            draft.endLabel.ifBlank { "--:--" },
                            color = if (draft.endLabel.isBlank()) CrMuted else CrText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("▾", color = CrCyan, fontSize = 11.sp, modifier = Modifier.clickable { endDropdownOpen = true })
                }
                DropdownMenu(
                    expanded = endDropdownOpen,
                    onDismissRequest = { endDropdownOpen = false },
                    containerColor = CrCardAlt,
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("Pick any time…", color = CrCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        onClick = {
                            endDropdownOpen = false
                            showTimePicker(context, draft.endLabel) { label -> onUpdate(draft.copy(endLabel = label)) }
                        }
                    )
                    HorizontalDivider(color = CrBorder)
                    CR_TIME_SLOTS.forEach { (label, _) ->
                        DropdownMenuItem(
                            text = { Text(label, color = CrText, fontSize = 12.sp) },
                            onClick = { onUpdate(draft.copy(endLabel = label)); endDropdownOpen = false }
                        )
                    }
                }
            }
        }
        if (invalidTime) {
            Spacer(Modifier.height(4.dp))
            Text("End time must be after start time", color = CrRed, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Custom Routine Detail (weekly table view)
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRoutineDetailScreen(
    db: AppDatabase,
    routineId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit
) {
    val viewModel: CustomRoutineViewModel = viewModel(factory = CustomRoutineViewModelFactory(db))
    val routine by viewModel.selectedRoutine.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val isOwner = SessionManager.isAdmin()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var institute by remember { mutableStateOf<InstituteEntity?>(null) }
    var staffList by remember { mutableStateOf<List<StaffEntity>>(emptyList()) }
    var isGenerating by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CustomRoutineEntryEntity?>(null) }
    var editDraft by remember { mutableStateOf<EntryDraft?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    var isSavingEdit by remember { mutableStateOf(false) }

    LaunchedEffect(routineId) { viewModel.loadRoutine(routineId) }
    LaunchedEffect(Unit) {
        val instId = SessionManager.currentInstituteId.value
        if (instId != null) {
            institute = withContext(Dispatchers.IO) { db.instituteDao().getInstitute(instId) }
            staffList = withContext(Dispatchers.IO) {
                db.staffDao().getStaffByInstituteAsList(instId).filter { it.status == "active" }
            }
        }
    }

    fun createPdf(action: (File, String) -> Boolean) {
        val routineNow = routine
        if (routineNow == null) {
            scope.launch { snackbarHostState.showSnackbar("Routine is still loading. Try again in a moment.") }
            return
        }
        val instituteNow = institute
        if (instituteNow == null) {
            scope.launch { snackbarHostState.showSnackbar("Institute details are still loading. Try again in a moment.") }
            return
        }
        scope.launch {
            isGenerating = true
            try {
                val file = withContext(Dispatchers.IO) {
                    generateCustomRoutinePdf(context, instituteNow, routineNow, entries)
                }
                val label = routineNow.routineName
                if (!action(file, label)) snackbarHostState.showSnackbar("Could not open this option on the device.")
            } catch (_: Exception) {
                snackbarHostState.showSnackbar("Routine PDF could not be created. Please try again.")
            } finally {
                isGenerating = false
            }
        }
    }

    fun openEdit(entry: CustomRoutineEntryEntity) {
        editTarget = entry
        editDraft = EntryDraft(
            id = entry.id,
            subject = entry.subjectName,
            teacherId = entry.teacherId,
            startLabel = minutesToLabel(entry.startMinutes),
            endLabel = minutesToLabel(entry.endMinutes)
        )
        editError = null
    }

    fun saveEdit() {
        val target = editTarget ?: return
        val draft = editDraft ?: return
        val subject = draft.subject.trim()
        if (subject.isBlank()) { editError = "Subject name is required."; return }
        val start = draft.startMinutes()
        val end = draft.endMinutes()
        if (start == null || end == null) { editError = "Start and end time are required."; return }
        if (end <= start) { editError = "End time must be after start time."; return }
        val clash = entries.firstOrNull { e ->
            e.id != target.id && e.dayIndex == target.dayIndex &&
                start < e.endMinutes && e.startMinutes < end
        }
        if (clash != null) { editError = "Time overlaps with '${clash.subjectName}' on this day."; return }
        val teacher = staffList.find { it.id == draft.teacherId }
        val teacherName = when {
            draft.teacherId == null -> subject
            teacher != null -> teacher.fullName
            else -> target.teacherName
        }
        isSavingEdit = true
        viewModel.updateEntry(
            entry = target,
            subject = subject,
            teacherId = draft.teacherId,
            teacherName = teacherName,
            startMinutes = start,
            endMinutes = end,
            onSuccess = {
                isSavingEdit = false
                editTarget = null
                editDraft = null
                editError = null
                scope.launch { snackbarHostState.showSnackbar("Class updated.") }
            },
            onError = { message ->
                isSavingEdit = false
                editError = message
            }
        )
    }

    Scaffold(
        containerColor = CrBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(routine?.routineName ?: "Custom Routine", color = CrText, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = CrText) } },
                actions = {
                    if (isOwner) {
                        IconButton(onClick = { onEdit(routineId) }) {
                            Icon(Icons.Filled.Edit, "Edit", tint = CrCyan)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CrBg)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Header card
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CrCard),
                border = BorderStroke(1.dp, CrBorder)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        listOfNotNull(
                            routine?.className,
                            routine?.section?.takeIf { it.isNotBlank() },
                            routine?.academicSession?.takeIf { it.isNotBlank() }
                        ).joinToString(" • "),
                        color = CrText, fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(CrCyan.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("${entries.size} classes", color = CrCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        routine?.effectiveDateMs?.let {
                            Text(
                                "Effective ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it))}",
                                color = CrMuted, fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { createPdf { file, label -> printRoutinePdf(context, file, label) } },
                            enabled = !isGenerating,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, CrCyan.copy(alpha = 0.75f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CrCyan)
                        ) {
                            Icon(Icons.Filled.Print, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Print PDF", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { createPdf { file, label -> shareRoutinePdf(context, file, label) } },
                            enabled = !isGenerating,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CrGreen, contentColor = Color.White)
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.IosShare, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Weekly table
            for (day in 0 until 7) {
                val dayEntries = entries.filter { it.dayIndex == day }
                RoutineDaySection(
                    dayName = CR_DAY_NAMES[day],
                    dayEntries = dayEntries,
                    onEdit = if (isOwner) ({ entry -> openEdit(entry) }) else null
                )
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (editTarget != null && editDraft != null) {
        EditCustomEntryDialog(
            draft = editDraft!!,
            staffList = staffList,
            saving = isSavingEdit,
            error = editError,
            onUpdate = { editDraft = it },
            onDismiss = {
                if (!isSavingEdit) {
                    editTarget = null
                    editDraft = null
                    editError = null
                }
            },
            onSave = { saveEdit() }
        )
    }
}

@Composable
private fun RoutineDaySection(
    dayName: String,
    dayEntries: List<CustomRoutineEntryEntity>,
    onEdit: ((CustomRoutineEntryEntity) -> Unit)? = null
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CrCard),
        border = BorderStroke(1.dp, CrBorder)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(CrBlue.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) { Text(dayName.take(3).uppercase(), color = CrCyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) }
                Spacer(Modifier.width(8.dp))
                Text(dayName, color = CrText, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (dayEntries.isEmpty()) {
                    Text("No classes", color = CrMuted, fontSize = 11.sp)
                }
            }
            if (dayEntries.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                dayEntries.sortedBy { it.startMinutes }.forEachIndexed { index, entry ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (index % 2 == 0) CrCardAlt else CrCardHi)
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(0.5f)) {
                            Text(
                                "${minutesToLabel(entry.startMinutes)} – ${minutesToLabel(entry.endMinutes)}",
                                color = CrCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Column(Modifier.weight(1.2f)) {
                            Text(entry.subjectName, color = CrText, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.End) {
                            Text(entry.teacherName, color = CrMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (onEdit != null) {
                            IconButton(onClick = { onEdit(entry) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Edit, "Edit", tint = CrCyan, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    if (index < dayEntries.lastIndex) Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun EditCustomEntryDialog(
    draft: EntryDraft,
    staffList: List<StaffEntity>,
    saving: Boolean,
    error: String?,
    onUpdate: (EntryDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = CrCard,
        title = { Text("Edit Class", color = CrText, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Change the subject name, teacher or class time. Tap a time box to open the clock picker.",
                    color = CrMuted, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                EntryDraftRow(
                    draft = draft,
                    staffList = staffList,
                    onUpdate = onUpdate,
                    onRemove = {},
                    showRemove = false
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = CrRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !saving) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = CrCyan, strokeWidth = 2.dp)
                } else {
                    Text("Save", color = CrCyan, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel", color = CrMuted) }
        }
    )
}

private fun crInitials(name: String): String =
    name.split(" ").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("")
