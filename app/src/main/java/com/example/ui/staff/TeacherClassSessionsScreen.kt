package com.batchfee.edu.ui.staff

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.InstituteRefreshScope
import com.batchfee.edu.data.firestore.TeachingSessionSyncHelper
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.data.models.TeachingSessionEntity
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

private val TeacherBg = Color(0xFF07111F)
private val TeacherCard = Color(0xFF0F172A)
private val TeacherBorder = Color(0xFF1E293B)
private val TeacherCyan = Color(0xFF22D3EE)
private val TeacherBlue = Color(0xFF3B82F6)
private val TeacherGreen = Color(0xFF22C55E)
private val TeacherText = Color(0xFFF8FAFC)
private val TeacherMuted = Color(0xFF94A3B8)
private val TeacherRed = Color(0xFFEF4444)

class TeacherClassSessionsViewModel(
    private val db: AppDatabase,
    private val staffId: String,
) : ViewModel() {
    private val _staff = MutableStateFlow<StaffEntity?>(null)
    val staff = _staff.asStateFlow()
    private val _batches = MutableStateFlow<List<BatchEntity>>(emptyList())
    val batches = _batches.asStateFlow()
    private val _sessions = MutableStateFlow<List<TeachingSessionEntity>>(emptyList())
    val sessions = _sessions.asStateFlow()

    init {
        SessionManager.currentInstituteId.value?.let { instituteId ->
            viewModelScope.launch {
                InstituteCacheRefreshManager.refreshScopeIfStale(
                    db, instituteId, InstituteRefreshScope.STAFF
                )
            }
            viewModelScope.launch { db.staffDao().getStaffById(staffId, instituteId).collect { _staff.value = it } }
            viewModelScope.launch { db.batchDao().getBatchesByInstitute(instituteId).collect { _batches.value = it } }
            viewModelScope.launch { db.teachingSessionDao().getSessionsForStaff(instituteId, staffId).collect { _sessions.value = it } }
        }
    }

    fun markCompleted(
        batch: BatchEntity,
        subject: String,
        dateMs: Long,
        durationMinutes: Int,
        note: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!SessionManager.isAdmin()) {
            onError("Only the institute owner can confirm completed teacher classes.")
            return
        }
        val instituteId = SessionManager.currentInstituteId.value ?: run { onError("No institute session found."); return }
        val userId = SessionManager.currentUserId.value ?: run { onError("Please sign in again."); return }
        val teacher = _staff.value ?: run { onError("Teacher information is still loading."); return }
        if (teacher.staffCategory != "teacher") { onError("Class completion is available for Teacher category staff."); return }
        if (durationMinutes !in 1..720) { onError("Class duration must be between 1 and 720 minutes."); return }
        val cleanSubject = subject.trim().takeIf { it.isNotBlank() }
            ?: run { onError("Select or enter the class subject."); return }
        val normalizedDate = startOfDay(dateMs)
        val key = "$staffId|${batch.id}|${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(normalizedDate))}|${batch.startTime.orEmpty()}"
        val stableId = "class-${UUID.nameUUIDFromBytes(key.toByteArray()).toString()}"
        val rate = when (teacher.salaryType) {
            "per_class" -> teacher.perClassRate
            "per_hour" -> teacher.perHourRate
            else -> 0.0
        }
        val amount = when (teacher.salaryType) {
            "per_class" -> rate
            "per_hour" -> rate * durationMinutes / 60.0
            else -> 0.0
        }
        viewModelScope.launch {
            val existing = db.teachingSessionDao().getBySessionKey(instituteId, key)
            if (existing != null && existing.deletedAtMs == null) {
                onError(if (existing.salaryId == null) "This scheduled class is already completed." else "This class is already included in a salary record.")
                return@launch
            }
            val now = System.currentTimeMillis()
            val record = TeachingSessionEntity(
                id = stableId,
                instituteId = instituteId,
                staffId = staffId,
                batchId = batch.id,
                sessionKey = key,
                subject = cleanSubject,
                sessionDateMs = normalizedDate,
                durationMinutes = durationMinutes,
                salaryTypeSnapshot = teacher.salaryType,
                rateSnapshot = rate,
                calculatedAmount = amount,
                salaryId = null,
                note = note.trim().takeIf { it.isNotBlank() },
                createdByUserId = userId,
                createdAtMs = existing?.createdAtMs ?: now,
                updatedAtMs = now,
                deletedAtMs = null,
            )
            try {
                TeachingSessionSyncHelper.createSessionIfAvailable(record)
                db.teachingSessionDao().insertSession(record)
                onSuccess()
            } catch (error: IllegalStateException) {
                onError(error.message ?: "This scheduled class is already completed.")
            } catch (_: Exception) {
                onError("Could not save this class. Check your connection and try again.")
            }
        }
    }

    fun removeSession(session: TeachingSessionEntity, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (!SessionManager.isAdmin()) { onError("Only the institute owner can remove a class entry."); return }
        if (session.salaryId != null) { onError("This class is already included in a salary and cannot be removed."); return }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val deleted = session.copy(deletedAtMs = now, updatedAtMs = now)
            try {
                TeachingSessionSyncHelper.upsertSession(deleted)
                db.teachingSessionDao().insertSession(deleted)
                onSuccess()
            } catch (_: Exception) { onError("Could not remove this class. Please try again.") }
        }
    }

    private fun startOfDay(value: Long): Long = Calendar.getInstance().apply {
        timeInMillis = value
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

class TeacherClassSessionsViewModelFactory(private val db: AppDatabase, private val staffId: String) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TeacherClassSessionsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST") return TeacherClassSessionsViewModel(db, staffId) as T
        }
        error("Unknown ViewModel")
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TeacherClassSessionsScreen(db: AppDatabase, staffId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: TeacherClassSessionsViewModel = viewModel(factory = TeacherClassSessionsViewModelFactory(db, staffId))
    val teacher by viewModel.staff.collectAsState()
    val batches by viewModel.batches.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val calendar = remember { Calendar.getInstance() }
    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var selectedDateMs by remember { mutableStateOf(startOfToday()) }
    var subject by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("60") }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val assignedIds = teacher?.assignedBatchIds?.split(",")?.map(String::trim)?.filter(String::isNotBlank)?.toSet().orEmpty()
    val assignedBatches = batches.filter { it.id in assignedIds }
    LaunchedEffect(teacher?.id) { if (subject.isBlank()) subject = teacher?.subjects?.split(",")?.firstOrNull()?.trim().orEmpty() }
    LaunchedEffect(assignedBatches) { if (selectedBatchId == null) selectedBatchId = assignedBatches.firstOrNull()?.id }

    Scaffold(
        containerColor = TeacherBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Teacher Classes", color = TeacherText, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TeacherText) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TeacherBg)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val displayTeacher = teacher
                if (displayTeacher == null) {
                    Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TeacherCyan) }
                } else if (displayTeacher.staffCategory != "teacher") {
                    SessionCard { Text("Change this staff category to Teacher to track completed classes and class-based salary.", color = TeacherMuted, fontSize = 14.sp) }
                } else {
                    SessionCard {
                        Text(displayTeacher.fullName, color = TeacherText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        val payText = when (displayTeacher.salaryType) {
                            "per_class" -> "BDT ${displayTeacher.perClassRate.toLong()} per completed class"
                            "per_hour" -> "BDT ${displayTeacher.perHourRate.toLong()} per hour"
                            else -> "Fixed monthly salary · class log for attendance"
                        }
                        Text(payText, color = TeacherCyan, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        Text("Subjects: ${displayTeacher.subjects ?: "Not set"}", color = TeacherMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
            if (teacher?.staffCategory == "teacher") {
                item {
                    SessionCard {
                        Text("Confirm Completed Class", color = TeacherText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("One scheduled class can be confirmed once per day. It will count automatically in salary.", color = TeacherMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Assigned batch", color = TeacherMuted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 5.dp)) {
                            assignedBatches.forEach { batch ->
                                FilterChip(
                                    selected = selectedBatchId == batch.id,
                                    onClick = { selectedBatchId = batch.id },
                                    label = { Text(batch.name, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TeacherBlue.copy(alpha = .28f), selectedLabelColor = TeacherCyan)
                                )
                            }
                        }
                        if (assignedBatches.isEmpty()) Text("Assign at least one batch in the teacher profile first.", color = TeacherRed, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Subject", color = TeacherMuted) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = sessionFieldColors())
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(selectedDateMs)),
                                onValueChange = {}, readOnly = true, label = { Text("Class date", color = TeacherMuted) }, singleLine = true,
                                trailingIcon = { IconButton(onClick = {
                                    calendar.timeInMillis = selectedDateMs
                                    DatePickerDialog(context, { _, y, m, d ->
                                        selectedDateMs = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                                    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
                                }) { Icon(Icons.Filled.CalendarMonth, null, tint = TeacherCyan) } },
                                modifier = Modifier.weight(1.45f), colors = sessionFieldColors()
                            )
                            OutlinedTextField(value = duration, onValueChange = { duration = it }, label = { Text("Minutes", color = TeacherMuted) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(.8f), colors = sessionFieldColors())
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)", color = TeacherMuted) }, modifier = Modifier.fillMaxWidth(), colors = sessionFieldColors())
                        Spacer(Modifier.height(12.dp))
                        val batch = assignedBatches.firstOrNull { it.id == selectedBatchId }
                        val canSave = !saving && batch != null && subject.isNotBlank() && (duration.toIntOrNull() ?: 0) > 0
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .then(
                                    if (canSave) Modifier.background(Brush.horizontalGradient(listOf(TeacherBlue, TeacherCyan)))
                                    else Modifier.background(TeacherCard)
                                )
                                .border(if (canSave) 0.dp else 1.dp, TeacherBorder, RoundedCornerShape(13.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            TextButton(enabled = canSave, onClick = {
                                saving = true
                                viewModel.markCompleted(batch!!, subject, selectedDateMs, duration.toInt(), note, onSuccess = {
                                    saving = false; note = ""
                                }, onError = { message ->
                                    saving = false
                                    scope.launch { snackbar.showSnackbar(message) }
                                })
                            }, modifier = Modifier.fillMaxSize(), colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                                Icon(Icons.Filled.DoneAll, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(7.dp))
                                Text(if (saving) "Saving..." else "Confirm Class Completed", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            item { Text("Recent Completed Classes", color = TeacherText, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            if (sessions.isEmpty()) item { SessionCard { Text("No completed classes recorded yet.", color = TeacherMuted, fontSize = 13.sp) } }
            items(sessions, key = { it.id }) { session ->
                val batchName = batches.firstOrNull { it.id == session.batchId }?.name ?: "Archived batch"
                SessionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(session.subject ?: batchName, color = TeacherText, fontWeight = FontWeight.SemiBold)
                            Text("$batchName · ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(session.sessionDateMs))} · ${session.durationMinutes} min", color = TeacherMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                            val amount = if (session.salaryTypeSnapshot == "monthly") "Monthly salary attendance" else "BDT ${session.calculatedAmount.toLong()}"
                            Text(if (session.salaryId == null) amount else "Included in salary", color = if (session.salaryId == null) TeacherCyan else TeacherGreen, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (session.salaryId == null) IconButton(onClick = { viewModel.removeSession(session, onSuccess = {}, onError = { message -> scope.launch { snackbar.showSnackbar(message) } }) }) { Icon(Icons.Filled.DeleteOutline, null, tint = TeacherRed) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(TeacherCard).border(1.dp, TeacherBorder, RoundedCornerShape(15.dp)).padding(14.dp), content = content)
}

@Composable
private fun sessionFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = TeacherCyan, unfocusedBorderColor = TeacherBorder,
    focusedTextColor = TeacherText, unfocusedTextColor = TeacherText, cursorColor = TeacherCyan
)

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
