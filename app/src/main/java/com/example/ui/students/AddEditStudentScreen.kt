package com.example.ui.students

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.database.AppDatabase
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── Colors (matching PricingScreen) ─────────────────────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val SkyBlue       = Color(0xFF38BDF8)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val VioletBlue    = Color(0xFF6366F1)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val WAGreen       = Color(0xFF25D366)
private val Teal          = Color(0xFF14B8A6)

private val GenderOptions = listOf("Male", "Female", "Other")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditStudentScreen(
    db: AppDatabase,
    studentId: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: StudentViewModel = viewModel(factory = StudentViewModelFactory(db))
    val scope = rememberCoroutineScope()
    val isEdit = studentId != null

    // ── Form state ──────────────────────────────────────────
    val studentCode = remember { viewModel.generateStudentCode() }
    var existingId by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var guardianName by remember { mutableStateOf("") }
    var motherName by remember { mutableStateOf("") }
    var whatsappNumber by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf<String?>(null) }
    var dateOfBirthMs by remember { mutableStateOf<Long?>(null) }
    var schoolName by remember { mutableStateOf("") }
    var className by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var admissionDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var notes by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    var nameError by remember { mutableStateOf(false) }
    var phoneError by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(!isEdit) }

    val tempPhotoFile = remember {
        File(context.cacheDir, "student_photo_${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
    }
    val tempPhotoUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempPhotoFile)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            photoUri = tempPhotoUri
        }
    }

    // ── Load existing student ────────────────────────────────
    LaunchedEffect(studentId) {
        if (studentId != null) {
            val student = viewModel.loadStudent(studentId)
            student?.let { s ->
                existingId = s.id
                fullName = s.fullName
                phone = s.phone ?: ""
                guardianName = s.guardianName ?: ""
                motherName = s.emergencyContact ?: ""
                gender = s.gender
                dateOfBirthMs = s.dateOfBirthMs
                schoolName = s.schoolName ?: ""
                className = s.className ?: ""
                address = s.address ?: ""
                admissionDateMs = s.admissionDateMs
                s.notes?.let { rawNotes ->
                    if (rawNotes.startsWith("WhatsApp: ")) {
                        val lines = rawNotes.split("\n", limit = 2)
                        whatsappNumber = lines[0].removePrefix("WhatsApp: ")
                        notes = lines.getOrElse(1) { "" }
                    } else {
                        notes = rawNotes
                    }
                }
                s.photoUri?.let { uriStr ->
                    try { photoUri = Uri.parse(uriStr) } catch (_: Exception) {}
                }
            }
            loaded = true
        }
    }

    // ── Date picker dialog ───────────────────────────────────
    var showDatePicker by remember { mutableStateOf(false) }
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dateOfBirthMs ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateOfBirthMs = datePickerState.selectedDateMillis
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    val admissionDateStr = remember(admissionDateMs) {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(admissionDateMs))
    }

    val dobDisplay = remember(dateOfBirthMs) {
        dateOfBirthMs?.let {
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it))
        } ?: "Not set"
    }

    val title = if (isEdit) "Edit Student" else "Add Student"

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(title, color = TextWhite, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        if (!loaded) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Cyan)
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // ── Photo Section ──────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(CardBgAlt)
                            .border(2.dp, ElectricBlue, CircleShape)
                            .clickable {
                                scope.launch {
                                    try { cameraLauncher.launch(tempPhotoUri) }
                                    catch (_: Exception) {}
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (photoUri != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(photoUri)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Student photo",
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.CameraAlt,
                                    contentDescription = "Add photo",
                                    tint = TextMuted,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    "Photo",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Student ID ─────────────────────────────────
                SectionLabel("Student ID")
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DarkTextField(
                        value = studentCode,
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Cyan.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Auto", fontSize = 10.sp, color = Cyan, fontWeight = FontWeight.SemiBold)
                    }
                }

                // ── Full Name ──────────────────────────────────
                SectionLabel("Full Name *")
                DarkTextField(
                    value = fullName,
                    onValueChange = { fullName = it; nameError = false },
                    isError = nameError,
                    supportingText = if (nameError) "Required" else null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))

                // ── Guardian Name ──────────────────────────────
                SectionLabel("Father / Guardian Name")
                DarkTextField(
                    value = guardianName,
                    onValueChange = { guardianName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))

                // ── Mother Name ────────────────────────────────
                SectionLabel("Mother Name")
                DarkTextField(
                    value = motherName,
                    onValueChange = { motherName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))

                // ── Phone ──────────────────────────────────────
                SectionLabel("Phone Number *")
                DarkTextField(
                    value = phone,
                    onValueChange = { phone = it; phoneError = false },
                    isError = phoneError,
                    supportingText = if (phoneError) "Required" else null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Spacer(Modifier.height(12.dp))

                // ── WhatsApp ───────────────────────────────────
                SectionLabel("WhatsApp Number")
                DarkTextField(
                    value = whatsappNumber,
                    onValueChange = { whatsappNumber = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Spacer(Modifier.height(14.dp))

                // ── Gender ─────────────────────────────────────
                SectionLabel("Gender")
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GenderOptions.forEach { option ->
                        val isSelected = option == gender
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .then(
                                    if (isSelected) Modifier.background(
                                        Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                                    )
                                    else Modifier.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(8.dp))
                                )
                                .clickable { gender = if (isSelected) null else option }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                option,
                                color = if (isSelected) Color.White else TextMuted,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                // ── Date of Birth ──────────────────────────────
                SectionLabel("Date of Birth")
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBgAlt)
                        .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
                        .clickable { showDatePicker = true }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(dobDisplay, color = TextWhite, fontSize = 14.sp)
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = Cyan, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))

                // ── School Name ────────────────────────────────
                SectionLabel("School / Institute Name")
                DarkTextField(
                    value = schoolName,
                    onValueChange = { schoolName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))

                // ── Class ──────────────────────────────────────
                SectionLabel("Class")
                DarkTextField(
                    value = className,
                    onValueChange = { className = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))

                // ── Address ────────────────────────────────────
                SectionLabel("Address")
                DarkTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
                Spacer(Modifier.height(12.dp))

                // ── Admission Date ─────────────────────────────
                SectionLabel("Admission Date")
                DarkTextField(
                    value = admissionDateStr,
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))

                // ── Notes ──────────────────────────────────────
                SectionLabel("Notes")
                DarkTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )

                Spacer(Modifier.height(24.dp))

                // ── Save Button ────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                        )
                        .clickable {
                            nameError = fullName.isBlank()
                            phoneError = phone.isBlank()
                            if (!nameError && !phoneError) {
                                if (isEdit) {
                                    viewModel.updateStudent(
                                        id = existingId,
                                        studentCode = studentCode,
                                        fullName = fullName.trim(),
                                        phone = phone.trim(),
                                        guardianName = guardianName.trim().takeIf { it.isNotEmpty() },
                                        motherName = motherName.trim().takeIf { it.isNotEmpty() },
                                        whatsappNumber = whatsappNumber.trim().takeIf { it.isNotEmpty() },
                                        gender = gender,
                                        dateOfBirthMs = dateOfBirthMs,
                                        schoolName = schoolName.trim().takeIf { it.isNotEmpty() },
                                        className = className.trim().takeIf { it.isNotEmpty() },
                                        address = address.trim().takeIf { it.isNotEmpty() },
                                        notes = notes.trim().takeIf { it.isNotEmpty() },
                                        photoUri = photoUri?.toString(),
                                        onSuccess = onBack
                                    )
                                } else {
                                    viewModel.addStudent(
                                        studentCode = studentCode,
                                        fullName = fullName.trim(),
                                        phone = phone.trim(),
                                        guardianName = guardianName.trim().takeIf { it.isNotEmpty() },
                                        motherName = motherName.trim().takeIf { it.isNotEmpty() },
                                        whatsappNumber = whatsappNumber.trim().takeIf { it.isNotEmpty() },
                                        gender = gender,
                                        dateOfBirthMs = dateOfBirthMs,
                                        schoolName = schoolName.trim().takeIf { it.isNotEmpty() },
                                        className = className.trim().takeIf { it.isNotEmpty() },
                                        address = address.trim().takeIf { it.isNotEmpty() },
                                        notes = notes.trim().takeIf { it.isNotEmpty() },
                                        photoUri = photoUri?.toString(),
                                        onSuccess = onBack
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isEdit) "Update" else "Save",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ── Helper Composables ──────────────────────────────────────────
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp
    )
    Spacer(Modifier.height(4.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        enabled = enabled,
        isError = isError,
        singleLine = singleLine,
        maxLines = maxLines,
        modifier = modifier,
        keyboardOptions = keyboardOptions,
        supportingText = if (supportingText != null) {{ Text(supportingText, color = Color(0xFFEF4444), fontSize = 11.sp) }} else null,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextWhite,
            unfocusedTextColor = TextWhite,
            disabledTextColor = TextWhite.copy(alpha = 0.6f),
            focusedBorderColor = ElectricBlue,
            unfocusedBorderColor = BorderSub,
            disabledBorderColor = BorderSub,
            errorBorderColor = Color(0xFFEF4444),
            focusedContainerColor = CardBgAlt,
            unfocusedContainerColor = CardBgAlt,
            disabledContainerColor = CardBg.copy(alpha = 0.5f),
            errorContainerColor = CardBgAlt,
            cursorColor = Cyan
        ),
        shape = RoundedCornerShape(12.dp)
    )
}
