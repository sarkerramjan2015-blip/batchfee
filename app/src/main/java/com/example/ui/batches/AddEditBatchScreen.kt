package com.batchfee.edu.ui.batches

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.domain.BatchBillingMode
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.isCourseBatch
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.UUID

// ── Colors (matching PricingScreen) ─────────────────────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)

private data class ScheduleDay(val shortName: String, val fullName: String)

private data class ScheduleFrequencyOption(val label: String, val dayCount: Int?)

private val WeeklyScheduleDays = listOf(
    ScheduleDay("Sun", "Sunday"),
    ScheduleDay("Mon", "Monday"),
    ScheduleDay("Tue", "Tuesday"),
    ScheduleDay("Wed", "Wednesday"),
    ScheduleDay("Thu", "Thursday"),
    ScheduleDay("Fri", "Friday"),
    ScheduleDay("Sat", "Saturday")
)

private val ScheduleFrequencyOptions = listOf(
    ScheduleFrequencyOption("7 days", 7),
    ScheduleFrequencyOption("6 days", 6),
    ScheduleFrequencyOption("5 days", 5),
    ScheduleFrequencyOption("4 days", 4),
    ScheduleFrequencyOption("3 days", 3),
    ScheduleFrequencyOption("2 days", 2),
    ScheduleFrequencyOption("Custom", null)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditBatchScreen(db: AppDatabase, batchId: String? = null, onBack: () -> Unit) {
    val viewModel: BatchViewModel = viewModel(factory = BatchViewModelFactory(db))
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val instId = SessionManager.currentInstituteId.collectAsState().value
    val isEditMode = batchId != null

    // Form state
    var name by remember { mutableStateOf("") }
    var feeString by remember { mutableStateOf("") }
    var admissionFeeString by remember { mutableStateOf("") }
    var billingMode by remember { mutableStateOf(BatchBillingMode.MONTHLY) }
    var courseStartDateMs by remember { mutableStateOf<Long?>(null) }
    var courseEndDateMs by remember { mutableStateOf<Long?>(null) }
    var showCourseStartPicker by remember { mutableStateOf(false) }
    var showCourseEndPicker by remember { mutableStateOf(false) }
    var courseDateError by remember { mutableStateOf<String?>(null) }
    var selectedScheduleFrequency by remember { mutableStateOf<ScheduleFrequencyOption?>(null) }
    var selectedScheduleDays by remember { mutableStateOf<Set<String>>(emptySet()) }
    var startTime by remember { mutableStateOf<String?>(null) }
    var endTime by remember { mutableStateOf<String?>(null) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    var editingBatch by remember(batchId) { mutableStateOf<BatchEntity?>(null) }
    var loadedBatchId by remember(batchId) { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val pendingBatchId = remember { UUID.randomUUID().toString() }
    val pendingBatchCode = remember { "BAT-${UUID.randomUUID().toString().take(8)}" }

    BackHandler(enabled = isSaving) { }

    // Validation
    var nameError by remember { mutableStateOf(false) }
    var feeError by remember { mutableStateOf(false) }
    var admissionFeeError by remember { mutableStateOf(false) }

    LaunchedEffect(batchId, instId) {
        val editId = batchId
        val instituteId = instId
        if (editId != null && instituteId != null) {
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instituteId)
            db.batchDao().getBatchById(editId, instituteId).collect { batch ->
                editingBatch = batch
                if (batch != null && loadedBatchId != batch.id) {
                    name = batch.name
                    billingMode = BatchBillingMode.normalize(batch.billingMode)
                    val displayFee = if (batch.isCourseBatch()) batch.courseFeeAmount else batch.monthlyFeeAmount
                    feeString = if (displayFee % 1.0 == 0.0) {
                        displayFee.toLong().toString()
                    } else {
                        displayFee.toString()
                    }
                    admissionFeeString = if (batch.admissionFeeAmount > 0.0) {
                        if (batch.admissionFeeAmount % 1.0 == 0.0) {
                            batch.admissionFeeAmount.toLong().toString()
                        } else {
                            batch.admissionFeeAmount.toString()
                        }
                    } else ""
                    val restoredDays = batch.scheduleDays
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { day -> WeeklyScheduleDays.any { it.shortName == day } }
                        ?.toSet()
                        .orEmpty()
                    selectedScheduleDays = restoredDays
                    selectedScheduleFrequency = if (restoredDays.isEmpty()) {
                        null
                    } else {
                        ScheduleFrequencyOptions.firstOrNull { it.dayCount == restoredDays.size }
                            ?: ScheduleFrequencyOptions.last()
                    }
                    startTime = batch.startTime
                    endTime = batch.endTime
                    courseStartDateMs = batch.startDateMs
                    courseEndDateMs = batch.endDateMs
                    loadedBatchId = batch.id
                }
            }
        }
    }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Edit Batch" else "Add Batch", color = TextWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isSaving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // ── Header ──────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(if (isEditMode) "Edit Batch Details" else "Create New Batch", color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (isEditMode) "Update the batch name, fee, and schedule"
                        else "Set up a class batch with its monthly fee",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            BatchTypeSelector(
                selectedMode = billingMode,
                enabled = !isEditMode,
                onSelected = { selected ->
                    billingMode = selected
                    feeError = false
                    courseDateError = null
                }
            )
            if (isEditMode) {
                Text(
                    "Batch type is locked after creation to keep existing fee history safe.",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Batch Name ──────────────────────────────────
            val isCourse = billingMode == BatchBillingMode.COURSE
            SectionLabel(if (isCourse) "Course Name *" else "Batch Name *")
            BatchTemplateRow(
                label = if (isCourse) "Course templates (optional)" else "Quick templates (optional)",
                options = if (isCourse) {
                    listOf(
                        "HSC ICT Crash Course",
                        "HSC Biology Crash Course",
                        "HSC Chemistry Crash Course",
                        "HSC Physics Crash Course",
                        "HSC Higher Math Crash Course",
                        "HSC Bangla Crash Course",
                        "HSC English Crash Course",
                        "HSC Accounting Crash Course",
                        "HSC Finance & Banking Crash Course",
                        "SSC ICT Crash Course",
                        "SSC Science Crash Course",
                        "SSC General Math Crash Course",
                        "SSC Higher Math Crash Course",
                        "SSC Bangla Crash Course",
                        "SSC English Crash Course",
                        "University Admission English",
                        "University Admission Math",
                        "University Admission Biology",
                        "University Admission Chemistry",
                        "University Admission Physics",
                        "Medical Admission Biology",
                        "Engineering Admission Math",
                        "Engineering Admission Physics",
                        "Engineering Admission Chemistry",
                        "Admission GK & English"
                    )
                } else {
                    listOf(
                        "Class 6", "Class 7", "Class 8", "Class 9", "Class 10",
                        "SSC Science", "HSC Science", "HSC 2027", "HSC 2028"
                    )
                },
                onSelected = { name = it; nameError = false }
            )
            Spacer(Modifier.height(8.dp))
            DarkTextField(
                value = name,
                onValueChange = { name = it; nameError = false },
                isError = nameError,
                supportingText = if (nameError) "Batch name is required" else null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = if (isCourse) "e.g. Spoken English – 3 Months" else "e.g. Class 10 Science"
            )

            Spacer(Modifier.height(12.dp))

            // ── Monthly Fee ─────────────────────────────────
            SectionLabel(if (isCourse) "Course Fee (BDT) *" else "Monthly Fee (BDT) *")
            Row(verticalAlignment = Alignment.CenterVertically) {
                DarkTextField(
                    value = feeString,
                    onValueChange = { feeString = it; feeError = false },
                    isError = feeError,
                    supportingText = if (feeError) "Amount must be greater than 0" else null,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = if (isCourse) "e.g. 5000" else "e.g. 1500"
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBgAlt)
                        .border(1.dp, BorderSub, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("BDT", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Admission Fee (one-time) ───────────────────
            SectionLabel(if (isCourse) "Admission Fee (BDT) — one-time" else "Admission Fee / One-Time Fee (BDT) *")
            Row(verticalAlignment = Alignment.CenterVertically) {
                DarkTextField(
                    value = admissionFeeString,
                    onValueChange = { admissionFeeString = it; admissionFeeError = false },
                    isError = admissionFeeError,
                    supportingText = if (admissionFeeError) "Amount cannot be negative" else null,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = "e.g. 500"
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBgAlt)
                        .border(1.dp, BorderSub, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("BDT", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (isCourse) {
                CourseDateSection(
                    startDateMs = courseStartDateMs,
                    endDateMs = courseEndDateMs,
                    errorMessage = courseDateError,
                    onStartClick = { showCourseStartPicker = true },
                    onEndClick = { showCourseEndPicker = true }
                )
                Spacer(Modifier.height(16.dp))
            }

            // ── Description ─────────────────────────────────
            BatchScheduleSection(
                selectedFrequency = selectedScheduleFrequency,
                selectedDays = selectedScheduleDays,
                startTime = startTime,
                endTime = endTime,
                errorMessage = scheduleError,
                onFrequencySelected = { option ->
                    selectedScheduleFrequency = option
                    scheduleError = null
                    option.dayCount?.let { maximumDays ->
                        if (selectedScheduleDays.size > maximumDays) {
                            selectedScheduleDays = WeeklyScheduleDays
                                .filter { it.shortName in selectedScheduleDays }
                                .take(maximumDays)
                                .map { it.shortName }
                                .toSet()
                        }
                    }
                },
                onDayClicked = { day ->
                    val currentDays = selectedScheduleDays
                    if (day.shortName in currentDays) {
                        selectedScheduleDays = currentDays - day.shortName
                        scheduleError = null
                    } else {
                        val maximumDays = selectedScheduleFrequency?.dayCount
                        if (maximumDays != null && currentDays.size >= maximumDays) {
                            scheduleError = "Select exactly $maximumDays days, or change classes per week."
                        } else {
                            selectedScheduleDays = currentDays + day.shortName
                            scheduleError = null
                        }
                    }
                },
                onStartTimeSelected = {
                    startTime = it
                    scheduleError = null
                },
                onEndTimeSelected = {
                    endTime = it
                    scheduleError = null
                }
            )

            Spacer(Modifier.height(16.dp))

            // ── Info card ───────────────────────────────────
            Spacer(Modifier.height(18.dp))

            // ── Save Button ─────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                    )
                    .clickable(enabled = !isSaving) {
                        if (isSaving) return@clickable
                        // Validate
                        nameError = name.isBlank()
                        val fee = feeString.toDoubleOrNull()
                        feeError = (fee == null || fee <= 0)
                        val admissionFee = admissionFeeString.toDoubleOrNull() ?: 0.0
                        admissionFeeError = admissionFee < 0
                        val isCourseBatch = billingMode == BatchBillingMode.COURSE
                        val scheduleConfigured = selectedScheduleFrequency != null ||
                            selectedScheduleDays.isNotEmpty() || startTime != null || endTime != null
                        val requiredDayCount = selectedScheduleFrequency?.dayCount
                        courseDateError = if (isCourseBatch &&
                            (courseStartDateMs == null || courseEndDateMs == null || courseEndDateMs!! < courseStartDateMs!!)
                        ) "Choose a valid course start and end date." else null
                        scheduleError = when {
                            !scheduleConfigured -> null
                            selectedScheduleFrequency == null -> "Select how many classes run each week."
                            selectedScheduleDays.isEmpty() -> "Select the class days."
                            requiredDayCount != null && selectedScheduleDays.size != requiredDayCount ->
                                "Select exactly $requiredDayCount days for this schedule."
                            startTime == null || endTime == null -> "Choose both a start and end time."
                            !isValidScheduleTimeRange(startTime, endTime) ->
                                "End time must be after start time."
                            else -> null
                        }
                        val savedScheduleDays = selectedScheduleDays
                            .sortedBy { selectedDay -> WeeklyScheduleDays.indexOfFirst { it.shortName == selectedDay } }
                            .joinToString(", ")
                            .takeIf { scheduleConfigured && it.isNotBlank() }

                        if (!nameError && !feeError && !admissionFeeError && courseDateError == null && scheduleError == null && fee != null) {
                            val existing = editingBatch
                            if (isEditMode) {
                                if (existing == null) {
                                    scope.launch { snackbarHostState.showSnackbar("Batch is still loading.") }
                                } else {
                                    isSaving = true
                                    viewModel.updateBatch(
                                        existing.copy(
                                            name = name.trim(),
                                            billingMode = billingMode,
                                            monthlyFeeAmount = if (isCourseBatch) 0.0 else fee,
                                            courseFeeAmount = if (isCourseBatch) fee else 0.0,
                                            admissionFeeAmount = admissionFee,
                                            startDateMs = if (isCourseBatch) courseStartDateMs else existing.startDateMs,
                                            endDateMs = if (isCourseBatch) courseEndDateMs else existing.endDateMs,
                                            scheduleDays = savedScheduleDays,
                                            startTime = if (scheduleConfigured) startTime else null,
                                            endTime = if (scheduleConfigured) endTime else null,
                                            // Keep any existing note from older batches; this simple form
                                            // intentionally has no description field.
                                            description = existing.description
                                        ),
                                        onError = { message ->
                                            isSaving = false
                                            scope.launch { snackbarHostState.showSnackbar(message) }
                                        },
                                        onSuccess = {
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Batch updated successfully")
                                            }
                                            onBack()
                                        }
                                    )
                                }
                            } else {
                                isSaving = true
                                viewModel.addBatch(
                                    name = name.trim(),
                                    feeAmount = fee,
                                    billingMode = billingMode,
                                    courseFeeAmount = if (isCourseBatch) fee else 0.0,
                                    admissionFeeAmount = admissionFee,
                                    startDateMs = if (isCourseBatch) courseStartDateMs else null,
                                    endDateMs = if (isCourseBatch) courseEndDateMs else null,
                                    scheduleDays = savedScheduleDays,
                                    startTime = if (scheduleConfigured) startTime else null,
                                    endTime = if (scheduleConfigured) endTime else null,
                                    batchId = pendingBatchId,
                                    batchCode = pendingBatchCode,
                                    onError = { message ->
                                        isSaving = false
                                        scope.launch { snackbarHostState.showSnackbar(message) }
                                    },
                                    onSuccess = {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Batch saved successfully")
                                        }
                                        onBack()
                                    }
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSaving) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Filled.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isSaving) "Saving..." else if (isEditMode) "Update Batch" else "Save Batch",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    CourseDatePickers(
        showStart = showCourseStartPicker,
        showEnd = showCourseEndPicker,
        startDateMs = courseStartDateMs,
        endDateMs = courseEndDateMs,
        onStartChanged = { selected ->
            courseStartDateMs = selected
            courseDateError = null
        },
        onEndChanged = { selected ->
            courseEndDateMs = selected
            courseDateError = null
        },
        onDismissStart = { showCourseStartPicker = false },
        onDismissEnd = { showCourseEndPicker = false }
    )
}

// ── Helpers ─────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseDatePickers(
    showStart: Boolean,
    showEnd: Boolean,
    startDateMs: Long?,
    endDateMs: Long?,
    onStartChanged: (Long) -> Unit,
    onEndChanged: (Long) -> Unit,
    onDismissStart: () -> Unit,
    onDismissEnd: () -> Unit
) {
    if (showStart) {
        val state = rememberDatePickerState(initialSelectedDateMillis = startDateMs ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = onDismissStart,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let(onStartChanged)
                    onDismissStart()
                }) { Text("OK") }
            }
        ) { DatePicker(state = state) }
    }
    if (showEnd) {
        val state = rememberDatePickerState(initialSelectedDateMillis = endDateMs ?: startDateMs ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = onDismissEnd,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let(onEndChanged)
                    onDismissEnd()
                }) { Text("OK") }
            }
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun BatchTemplateRow(
    label: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
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
                        containerColor = CardBg,
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

@Composable
private fun BatchTypeSelector(
    selectedMode: String,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Batch Type", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BatchTypeChoice("Monthly Batch", "Monthly fee every month", BatchBillingMode.MONTHLY, selectedMode, enabled, onSelected, Modifier.weight(1f))
            BatchTypeChoice("Course", "One-time course fee", BatchBillingMode.COURSE, selectedMode, enabled, onSelected, Modifier.weight(1f))
        }
    }
}

@Composable
private fun BatchTypeChoice(
    title: String,
    subtitle: String,
    mode: String,
    selectedMode: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    modifier: Modifier
) {
    val selected = mode == selectedMode
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Cyan.copy(alpha = 0.13f) else CardBg)
            .border(1.dp, if (selected) Cyan else BorderSub, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onSelected(mode) }
            .padding(12.dp)
    ) {
        Text(title, color = if (selected) Cyan else TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = TextMuted, fontSize = 10.sp, lineHeight = 13.sp)
    }
}

@Composable
private fun CourseDateSection(
    startDateMs: Long?,
    endDateMs: Long?,
    errorMessage: String?,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("Course Duration *")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CourseDateField("Start date", startDateMs, onStartClick, Modifier.weight(1f))
            CourseDateField("End date", endDateMs, onEndClick, Modifier.weight(1f))
        }
        val duration = courseDurationLabel(startDateMs, endDateMs)
        if (duration != null) {
            Text("Duration: $duration", color = Cyan, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
        }
        errorMessage?.let { Text(it, color = Color(0xFFF87171), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
    }
}

@Composable
private fun CourseDateField(label: String, dateMs: Long?, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardBgAlt)
            .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = Cyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, color = TextMuted, fontSize = 10.sp)
            Text(dateMs?.let(::formatCourseDate) ?: "Select date", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun formatCourseDate(dateMs: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(dateMs))

private fun courseDurationLabel(startDateMs: Long?, endDateMs: Long?): String? {
    if (startDateMs == null || endDateMs == null || endDateMs < startDateMs) return null
    val start = Calendar.getInstance().apply { timeInMillis = startDateMs }
    val end = Calendar.getInstance().apply { timeInMillis = endDateMs }
    var months = (end.get(Calendar.YEAR) - start.get(Calendar.YEAR)) * 12 + end.get(Calendar.MONTH) - start.get(Calendar.MONTH)
    var days = end.get(Calendar.DAY_OF_MONTH) - start.get(Calendar.DAY_OF_MONTH)
    if (days < 0) {
        months -= 1
        val previousMonth = (end.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        days += previousMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    return listOfNotNull(
        months.takeIf { it > 0 }?.let { "$it month${if (it == 1) "" else "s"}" },
        days.takeIf { it > 0 }?.let { "$it day${if (it == 1) "" else "s"}" }
    ).ifEmpty { listOf("1 day") }.joinToString(" ")
}

@Composable
private fun BatchScheduleSection(
    selectedFrequency: ScheduleFrequencyOption?,
    selectedDays: Set<String>,
    startTime: String?,
    endTime: String?,
    errorMessage: String?,
    onFrequencySelected: (ScheduleFrequencyOption) -> Unit,
    onDayClicked: (ScheduleDay) -> Unit,
    onStartTimeSelected: (String) -> Unit,
    onEndTimeSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val selectedDayCount = selectedDays.size
    val durationLabel = scheduleDurationLabel(startTime, endTime)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Classes per week", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(ScheduleFrequencyOptions, key = { it.label }) { option ->
                ScheduleChoiceChip(
                    label = option.label,
                    selected = option == selectedFrequency,
                    onClick = { onFrequencySelected(option) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Select class days", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            val requiredDayCount = selectedFrequency?.dayCount
            Text(
                when {
                    selectedFrequency == null -> "Choose frequency first"
                    requiredDayCount == null -> "$selectedDayCount selected"
                    else -> "$selectedDayCount/$requiredDayCount selected"
                },
                color = if (selectedFrequency == null) TextMuted else Cyan,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        val dayRows = WeeklyScheduleDays.chunked(4)
        dayRows.forEachIndexed { rowIndex, rowDays ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                rowDays.forEach { day ->
                    ScheduleChoiceChip(
                        label = day.shortName,
                        selected = day.shortName in selectedDays,
                        enabled = selectedFrequency != null,
                        onClick = { onDayClicked(day) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - rowDays.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
            if (rowIndex < dayRows.lastIndex) {
                Spacer(Modifier.height(7.dp))
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("Class time", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ScheduleTimeField(
                label = "Start time",
                time = startTime,
                modifier = Modifier.weight(1f),
                onClick = {
                    showScheduleTimePicker(context, startTime, onStartTimeSelected)
                }
            )
            ScheduleTimeField(
                label = "End time",
                time = endTime,
                modifier = Modifier.weight(1f),
                onClick = {
                    showScheduleTimePicker(context, endTime ?: startTime, onEndTimeSelected)
                }
            )
        }

        if (durationLabel != null) {
            Text(
                "Class duration: $durationLabel",
                color = Cyan,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (errorMessage != null) {
            Text(
                errorMessage,
                color = Color(0xFFEF4444),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ScheduleChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(
                when {
                    selected -> Color(0xFF0E7490).copy(alpha = 0.42f)
                    enabled -> Color(0xFF111C2F)
                    else -> CardBgAlt.copy(alpha = 0.5f)
                }
            )
            .border(
                1.dp,
                when {
                    selected -> Cyan.copy(alpha = 0.75f)
                    enabled -> Color(0xFF22344D)
                    else -> BorderSub.copy(alpha = 0.45f)
                },
                shape
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = when {
                selected -> Cyan
                enabled -> Color(0xFFCBD5E1)
                else -> TextMuted.copy(alpha = 0.5f)
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun ScheduleTimeField(
    label: String,
    time: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .height(50.dp)
            .clip(shape)
            .background(CardBgAlt)
            .border(1.dp, BorderSub, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Schedule,
            contentDescription = null,
            tint = Cyan,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, color = TextMuted, fontSize = 10.sp)
            Text(
                formatScheduleTime(time) ?: "Select time",
                color = if (time == null) TextMuted.copy(alpha = 0.65f) else TextWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

private fun showScheduleTimePicker(
    context: android.content.Context,
    currentValue: String?,
    onTimeSelected: (String) -> Unit
) {
    val calendar = Calendar.getInstance()
    val (initialHour, initialMinute) = parseScheduleTime(currentValue)
        ?: (calendar.get(Calendar.HOUR_OF_DAY) to calendar.get(Calendar.MINUTE))
    TimePickerDialog(
        context,
        { _, hour, minute -> onTimeSelected(String.format(Locale.US, "%02d:%02d", hour, minute)) },
        initialHour,
        initialMinute,
        false
    ).show()
}

private fun parseScheduleTime(value: String?): Pair<Int, Int>? {
    val parts = value?.split(":") ?: return null
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour to minute else null
}

private fun scheduleTimeInMinutes(value: String?): Int? = parseScheduleTime(value)?.let { (hour, minute) ->
    hour * 60 + minute
}

private fun isValidScheduleTimeRange(startTime: String?, endTime: String?): Boolean {
    val startMinutes = scheduleTimeInMinutes(startTime) ?: return false
    val endMinutes = scheduleTimeInMinutes(endTime) ?: return false
    return endMinutes > startMinutes
}

private fun formatScheduleTime(value: String?): String? = parseScheduleTime(value)?.let { (hour, minute) ->
    val suffix = if (hour < 12) "AM" else "PM"
    val displayHour = when (val normalized = hour % 12) {
        0 -> 12
        else -> normalized
    }
    String.format(Locale.US, "%d:%02d %s", displayHour, minute, suffix)
}

private fun scheduleDurationLabel(startTime: String?, endTime: String?): String? {
    val startMinutes = scheduleTimeInMinutes(startTime) ?: return null
    val endMinutes = scheduleTimeInMinutes(endTime) ?: return null
    if (endMinutes <= startMinutes) return null
    val duration = endMinutes - startMinutes
    val hours = duration / 60
    val minutes = duration % 60
    return when {
        hours == 0 -> "$minutes min"
        minutes == 0 -> "$hours hr"
        else -> "$hours hr $minutes min"
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    placeholder: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        isError = isError,
        singleLine = singleLine,
        maxLines = maxLines,
        modifier = modifier,
        keyboardOptions = keyboardOptions,
        placeholder = { Text(placeholder, color = TextMuted.copy(alpha = 0.5f)) },
        supportingText = if (supportingText != null) {{ Text(supportingText, color = Color(0xFFEF4444), fontSize = 11.sp) }} else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextWhite,
            unfocusedTextColor = TextWhite,
            focusedBorderColor = ElectricBlue,
            unfocusedBorderColor = BorderSub,
            errorBorderColor = Color(0xFFEF4444),
            focusedContainerColor = CardBgAlt,
            unfocusedContainerColor = CardBgAlt,
            errorContainerColor = CardBgAlt,
            cursorColor = Cyan
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

