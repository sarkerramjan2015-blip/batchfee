package com.batchfee.edu.ui.batches

import android.app.TimePickerDialog
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
import com.batchfee.edu.domain.SessionManager
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

// ── Colors (matching PricingScreen) ─────────────────────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val SkyBlue       = Color(0xFF38BDF8)
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
    var description by remember { mutableStateOf("") }
    var selectedScheduleFrequency by remember { mutableStateOf<ScheduleFrequencyOption?>(null) }
    var selectedScheduleDays by remember { mutableStateOf<Set<String>>(emptySet()) }
    var startTime by remember { mutableStateOf<String?>(null) }
    var endTime by remember { mutableStateOf<String?>(null) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    var editingBatch by remember(batchId) { mutableStateOf<BatchEntity?>(null) }
    var loadedBatchId by remember(batchId) { mutableStateOf<String?>(null) }

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
                    feeString = if (batch.monthlyFeeAmount % 1.0 == 0.0) {
                        batch.monthlyFeeAmount.toLong().toString()
                    } else {
                        batch.monthlyFeeAmount.toString()
                    }
                    admissionFeeString = if (batch.admissionFeeAmount > 0.0) {
                        if (batch.admissionFeeAmount % 1.0 == 0.0) {
                            batch.admissionFeeAmount.toLong().toString()
                        } else {
                            batch.admissionFeeAmount.toString()
                        }
                    } else ""
                    description = batch.description.orEmpty()
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
                    IconButton(onClick = onBack) {
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
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // ── Header ──────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(if (isEditMode) "Edit Batch Details" else "Create New Batch", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (isEditMode) "Update the batch name, fee, and note"
                        else "Set up a class batch with its monthly fee",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Batch Name ──────────────────────────────────
            SectionLabel("Batch Name *")
            BatchTemplateRow(
                options = listOf(
                    "Class 6", "Class 7", "Class 8", "Class 9", "Class 10",
                    "SSC Science", "HSC Science", "HSC 2027", "HSC 2028"
                ),
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
                placeholder = "e.g. Class 10 Science"
            )

            Spacer(Modifier.height(16.dp))

            // ── Monthly Fee ─────────────────────────────────
            SectionLabel("Monthly Fee (BDT) *")
            Row(verticalAlignment = Alignment.CenterVertically) {
                DarkTextField(
                    value = feeString,
                    onValueChange = { feeString = it; feeError = false },
                    isError = feeError,
                    supportingText = if (feeError) "Amount must be greater than 0" else null,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = "e.g. 1500"
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

            Spacer(Modifier.height(16.dp))

            // ── Admission Fee (one-time) ───────────────────
            SectionLabel("Admission Fee / One-Time Fee (BDT) *")
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

            Spacer(Modifier.height(16.dp))

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

            SectionLabel("Description (optional)")
            DarkTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                placeholder = "e.g. Evening batch, Monday through Friday"
            )

            Spacer(Modifier.height(28.dp))

            // ── Info card ───────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = SkyBlue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (isEditMode) "Changes apply to this batch profile and future collection screens."
                        else "Batch ID will be auto-generated.\nStart date set to today. You can edit details later.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Save Button ─────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                    )
                    .clickable {
                        // Validate
                        nameError = name.isBlank()
                        val fee = feeString.toDoubleOrNull()
                        feeError = (fee == null || fee <= 0)
                        val admissionFee = admissionFeeString.toDoubleOrNull() ?: 0.0
                        admissionFeeError = admissionFee < 0
                        val scheduleConfigured = selectedScheduleFrequency != null ||
                            selectedScheduleDays.isNotEmpty() || startTime != null || endTime != null
                        val requiredDayCount = selectedScheduleFrequency?.dayCount
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

                        if (!nameError && !feeError && !admissionFeeError && scheduleError == null && fee != null) {
                            val cleanDescription = description.trim().takeIf { it.isNotEmpty() }
                            val existing = editingBatch
                            if (isEditMode) {
                                if (existing == null) {
                                    scope.launch { snackbarHostState.showSnackbar("Batch is still loading.") }
                                } else {
                                    viewModel.updateBatch(
                                        existing.copy(
                                            name = name.trim(),
                                            monthlyFeeAmount = fee,
                                            admissionFeeAmount = admissionFee,
                                            scheduleDays = savedScheduleDays,
                                            startTime = if (scheduleConfigured) startTime else null,
                                            endTime = if (scheduleConfigured) endTime else null,
                                            description = cleanDescription
                                        ),
                                        onError = { message ->
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
                                viewModel.addBatch(
                                    name = name.trim(),
                                    feeAmount = fee,
                                    admissionFeeAmount = admissionFee,
                                    scheduleDays = savedScheduleDays,
                                    startTime = if (scheduleConfigured) startTime else null,
                                    endTime = if (scheduleConfigured) endTime else null,
                                    description = cleanDescription,
                                    onError = { message ->
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
                    Icon(Icons.Filled.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isEditMode) "Update Batch" else "Save Batch", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────
@Composable
private fun BatchTemplateRow(options: List<String>, onSelected: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Quick templates (optional)", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
        SectionLabel("Class Schedule (optional)")
        Text(
            "Choose the weekly class days and time. You can update it later.",
            color = TextMuted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))

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

        Spacer(Modifier.height(14.dp))
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

        Spacer(Modifier.height(14.dp))
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
            .height(38.dp)
            .clip(shape)
            .background(
                when {
                    selected -> ElectricBlue.copy(alpha = 0.22f)
                    enabled -> CardBgAlt
                    else -> CardBgAlt.copy(alpha = 0.5f)
                }
            )
            .border(
                1.dp,
                when {
                    selected -> Cyan.copy(alpha = 0.75f)
                    enabled -> BorderSub
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
                enabled -> TextWhite
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
            .height(54.dp)
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

