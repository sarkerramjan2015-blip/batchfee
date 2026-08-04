package com.batchfee.edu.ui.students

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.cloudinary.CloudinaryImageUploadHelper
import com.batchfee.edu.ui.components.COUNTRY_CODES
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val CardBgAlt = Color(0xFF111827)
private val BorderSub = Color(0xFF1E293B)
private val SkyBlue = Color(0xFF38BDF8)
private val Cyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val AccentRed = Color(0xFFEF4444)

private val GenderOptions = listOf("Male", "Female", "Other")
private const val DefaultCountryCode = "+880"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditStudentScreen(
    db: AppDatabase,
    studentId: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: StudentViewModel = viewModel(factory = StudentViewModelFactory(db))
    val isEdit = studentId != null

    var studentCode by remember { mutableStateOf(viewModel.generateStudentCode()) }
    var existingId by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var guardianName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var whatsappNumber by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf<String?>(null) }
    var dateOfBirthMs by remember { mutableStateOf<Long?>(null) }
    var schoolName by remember { mutableStateOf("") }
    var className by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var admissionDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    var nameError by remember { mutableStateOf(false) }
    var phoneError by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(!isEdit) }
    var showDobPicker by remember { mutableStateOf(false) }
    var showAdmissionDatePicker by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val saveScope = rememberCoroutineScope()

    val tempPhotoFile = remember {
        File(context.cacheDir, "student_photo_${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
    }
    val tempPhotoUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempPhotoFile)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) photoUri = tempPhotoUri
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) photoUri = uri
    }

    LaunchedEffect(studentId) {
        if (studentId != null) {
            val student = viewModel.loadStudent(studentId)
            student?.let { s ->
                existingId = s.id
                studentCode = s.studentCode.filter(Char::isDigit).ifBlank { viewModel.generateStudentCode() }
                fullName = s.fullName
                guardianName = s.guardianName ?: ""
                phone = s.phone ?: ""
                gender = s.gender
                dateOfBirthMs = s.dateOfBirthMs
                schoolName = s.schoolName ?: ""
                className = s.className ?: ""
                address = s.address ?: ""
                admissionDateMs = s.admissionDateMs
                s.notes?.lineSequence()
                    ?.firstOrNull { it.startsWith("WhatsApp: ") }
                    ?.let { whatsappNumber = it.removePrefix("WhatsApp: ").trim() }
                s.photoUri?.let { uriStr ->
                    try {
                        photoUri = Uri.parse(uriStr)
                    } catch (_: Exception) {
                    }
                }
            }
            loaded = true
        }
    }

    if (showDobPicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dateOfBirthMs ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDobPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateOfBirthMs = datePickerState.selectedDateMillis
                    showDobPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDobPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showAdmissionDatePicker) {
        val admissionPickerState = rememberDatePickerState(
            initialSelectedDateMillis = admissionDateMs
        )
        DatePickerDialog(
            onDismissRequest = { showAdmissionDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    admissionDateMs = admissionPickerState.selectedDateMillis ?: admissionDateMs
                    showAdmissionDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showAdmissionDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = admissionPickerState)
        }
    }

    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val admissionDateStr = remember(admissionDateMs) { dateFormatter.format(Date(admissionDateMs)) }
    val dobDisplay = remember(dateOfBirthMs) {
        dateOfBirthMs?.let { dateFormatter.format(Date(it)) } ?: "Not set"
    }
    val title = if (isEdit) "Edit Student" else "Add Student"

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text(title, color = TextWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextWhite
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        if (!loaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Cyan)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .navigationBarsPadding()
        ) {
            StudentPhotoPicker(
                photoUri = photoUri,
                onCameraClick = {
                    try {
                        cameraLauncher.launch(tempPhotoUri)
                    } catch (_: Exception) {
                    }
                },
                onGalleryClick = { galleryLauncher.launch("image/*") }
            )
            Text(
                "JPG, PNG বা WebP দিন — app automatic optimize করে কম data-তে upload করবে.",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(14.dp))

            SectionLabel("Student ID")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DarkTextField(
                    value = studentCode,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Cyan.copy(alpha = 0.14f))
                        .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text("Auto", fontSize = 11.sp, color = Cyan, fontWeight = FontWeight.Bold)
                }
            }

            SectionLabel("Student Name *")
            DarkTextField(
                value = fullName,
                onValueChange = {
                    fullName = it
                    nameError = false
                },
                isError = nameError,
                supportingText = if (nameError) "Required" else null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))

            SectionLabel("Guardian Name")
            DarkTextField(
                value = guardianName,
                onValueChange = { guardianName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))

            SectionLabel("Phone Number *")
            StudentPhoneInputField(
                value = phone,
                onValueChange = {
                    phone = it
                    phoneError = false
                },
                isError = phoneError,
                supportingText = if (phoneError) "Required" else null,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))

            SectionLabel("WhatsApp Number")
            StudentPhoneInputField(
                value = whatsappNumber,
                onValueChange = { whatsappNumber = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            SectionLabel("Gender")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GenderOptions.forEach { option ->
                    val isSelected = option == gender
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (isSelected) {
                                    Modifier.background(Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                                } else {
                                    Modifier
                                        .background(CardBgAlt)
                                        .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
                                }
                            )
                            .clickable { gender = if (isSelected) null else option },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            option,
                            color = if (isSelected) Color.White else TextMuted,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            SectionLabel("Date of Birth")
            DateValueField(
                value = dobDisplay,
                muted = dateOfBirthMs == null,
                onClick = { showDobPicker = true }
            )
            Spacer(Modifier.height(10.dp))

            SectionLabel("Institute Name")
            DarkTextField(
                value = schoolName,
                onValueChange = { schoolName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))

            SectionLabel("Class")
            DarkTextField(
                value = className,
                onValueChange = { className = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))

            SectionLabel("Address")
            DarkTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2
            )
            Spacer(Modifier.height(10.dp))

            SectionLabel("Admission Date")
            DateValueField(
                value = admissionDateStr,
                muted = false,
                onClick = { showAdmissionDatePicker = true }
            )

            Spacer(Modifier.height(20.dp))

            saveError?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(message, color = AccentRed, fontSize = 12.sp)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                    .clickable(enabled = !isSaving) {
                        nameError = fullName.isBlank()
                        phoneError = phone.isBlank()
                        if (nameError || phoneError) return@clickable

                        isSaving = true
                        saveError = null
                        saveScope.launch {
                            try {
                                val cloudPhotoUrl = photoUri?.let { selectedUri ->
                                    if (selectedUri.scheme == "https" || selectedUri.scheme == "http") {
                                        selectedUri.toString()
                                    } else {
                                        CloudinaryImageUploadHelper.uploadStudentPhoto(context, selectedUri)
                                    }
                                }
                                val numericStudentCode = studentCode.filter(Char::isDigit)
                                    .ifBlank { viewModel.generateStudentCode() }
                                if (isEdit) {
                                    viewModel.updateStudent(
                                        id = existingId,
                                        studentCode = numericStudentCode,
                                        fullName = fullName.trim(),
                                        phone = phone.trim(),
                                        guardianName = guardianName.trim().takeIf { it.isNotEmpty() },
                                        motherName = null,
                                        whatsappNumber = whatsappNumber.trim().takeIf { it.isNotEmpty() },
                                        gender = gender,
                                        dateOfBirthMs = dateOfBirthMs,
                                        schoolName = schoolName.trim().takeIf { it.isNotEmpty() },
                                        className = className.trim().takeIf { it.isNotEmpty() },
                                        address = address.trim().takeIf { it.isNotEmpty() },
                                        admissionDateMs = admissionDateMs,
                                        photoUri = cloudPhotoUrl,
                                        onSuccess = {
                                            isSaving = false
                                            onBack()
                                        },
                                        onError = {
                                            saveError = it
                                            isSaving = false
                                        }
                                    )
                                } else {
                                    viewModel.addStudent(
                                        studentCode = numericStudentCode,
                                        fullName = fullName.trim(),
                                        phone = phone.trim(),
                                        guardianName = guardianName.trim().takeIf { it.isNotEmpty() },
                                        motherName = null,
                                        whatsappNumber = whatsappNumber.trim().takeIf { it.isNotEmpty() },
                                        gender = gender,
                                        dateOfBirthMs = dateOfBirthMs,
                                        schoolName = schoolName.trim().takeIf { it.isNotEmpty() },
                                        className = className.trim().takeIf { it.isNotEmpty() },
                                        address = address.trim().takeIf { it.isNotEmpty() },
                                        admissionDateMs = admissionDateMs,
                                        photoUri = cloudPhotoUrl,
                                        onSuccess = {
                                            isSaving = false
                                            onBack()
                                        },
                                        onError = {
                                            saveError = it
                                            isSaving = false
                                        }
                                    )
                                }
                                } catch (error: Exception) {
                                    saveError = error.message ?: "Photo upload failed. Check your connection and try again."
                                    isSaving = false
                                }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                if (isSaving) "Optimizing photo..." else if (isEdit) "Update" else "Save",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StudentPhotoPicker(
    photoUri: Uri?,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, BorderSub, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(82.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(ElectricBlue.copy(alpha = 0.85f), Cyan)))
                .border(2.dp, SkyBlue.copy(alpha = 0.65f), CircleShape)
                .clickable(onClick = onCameraClick),
            contentAlignment = Alignment.Center
        ) {
            if (photoUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(photoUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Student photo",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = "Add photo",
                    tint = BgColor,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Student Photo",
                color = TextWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoActionButton(
                    icon = Icons.Filled.CameraAlt,
                    label = "Camera",
                    onClick = onCameraClick,
                    modifier = Modifier.weight(1f)
                )
                PhotoActionButton(
                    icon = Icons.Filled.PhotoLibrary,
                    label = "Gallery",
                    onClick = onGalleryClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PhotoActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Cyan.copy(alpha = 0.12f))
            .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, tint = Cyan, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DateValueField(
    value: String,
    muted: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBgAlt)
            .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                value,
                color = if (muted) TextMuted else TextWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = Cyan, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp
    )
    Spacer(Modifier.height(4.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudentPhoneInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null
) {
    val initial = remember(value) { parseStudentPhoneNumber(value) }
    var selectedCode by remember { mutableStateOf(initial.first) }
    var localNumber by remember { mutableStateOf(initial.second) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (value.isBlank()) {
            localNumber = ""
        } else {
            val parsed = parseStudentPhoneNumber(value)
            selectedCode = parsed.first
            localNumber = parsed.second
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box {
            Box(
                modifier = Modifier
                    .width(104.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBgAlt)
                    .border(1.dp, if (isError) AccentRed else BorderSub, RoundedCornerShape(12.dp))
                    .clickable { showPicker = true }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selectedCode,
                        color = TextWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "Country code",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = showPicker,
                onDismissRequest = { showPicker = false },
                modifier = Modifier
                    .width(270.dp)
                    .heightIn(max = 330.dp)
                    .background(CardBgAlt),
                containerColor = CardBgAlt
            ) {
                COUNTRY_CODES.forEachIndexed { index, country ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(country.flag, fontSize = 17.sp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    country.name,
                                    color = TextWhite,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(country.code, color = TextMuted, fontSize = 13.sp)
                                if (country.code == selectedCode) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = Cyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            selectedCode = country.code
                            localNumber = cleanStudentLocalNumber(localNumber, country.code)
                            onValueChange(composeStudentPhone(country.code, localNumber))
                            showPicker = false
                        }
                    )
                    if (index != COUNTRY_CODES.lastIndex) {
                        HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
                    }
                }
            }
        }

        OutlinedTextField(
            value = localNumber,
            onValueChange = { input ->
                localNumber = cleanStudentLocalNumber(input, selectedCode)
                onValueChange(composeStudentPhone(selectedCode, localNumber))
            },
            isError = isError,
            supportingText = supportingText?.let { message ->
                { Text(message, color = AccentRed, fontSize = 11.sp) }
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Number", color = TextMuted.copy(alpha = 0.7f), fontSize = 14.sp) },
            textStyle = TextStyle(color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextWhite,
                unfocusedTextColor = TextWhite,
                focusedBorderColor = Cyan,
                unfocusedBorderColor = BorderSub,
                errorBorderColor = AccentRed,
                focusedContainerColor = CardBgAlt,
                unfocusedContainerColor = CardBgAlt,
                errorContainerColor = CardBgAlt,
                cursorColor = Cyan
            ),
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
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
        isError = isError,
        singleLine = singleLine,
        maxLines = maxLines,
        modifier = modifier,
        keyboardOptions = keyboardOptions,
        supportingText = supportingText?.let { message ->
            { Text(message, color = AccentRed, fontSize = 11.sp) }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextWhite,
            unfocusedTextColor = TextWhite,
            disabledTextColor = TextWhite.copy(alpha = 0.6f),
            focusedBorderColor = Cyan,
            unfocusedBorderColor = BorderSub,
            disabledBorderColor = BorderSub,
            errorBorderColor = AccentRed,
            focusedContainerColor = CardBgAlt,
            unfocusedContainerColor = CardBgAlt,
            disabledContainerColor = CardBg.copy(alpha = 0.5f),
            errorContainerColor = CardBgAlt,
            cursorColor = Cyan
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

private fun parseStudentPhoneNumber(full: String): Pair<String, String> {
    if (full.isBlank()) return DefaultCountryCode to ""

    val trimmed = full.trim()
    val explicitCountry = COUNTRY_CODES
        .sortedByDescending { it.code.length }
        .firstOrNull { trimmed.startsWith(it.code) }

    if (explicitCountry != null) {
        return explicitCountry.code to cleanStudentLocalNumber(
            input = trimmed.removePrefix(explicitCountry.code),
            selectedCode = explicitCountry.code
        )
    }

    val digits = trimmed.filter(Char::isDigit)
    val defaultDigits = DefaultCountryCode.filter(Char::isDigit)
    return if (digits.startsWith(defaultDigits)) {
        DefaultCountryCode to cleanStudentLocalNumber(
            input = digits.removePrefix(defaultDigits),
            selectedCode = DefaultCountryCode
        )
    } else {
        DefaultCountryCode to cleanStudentLocalNumber(trimmed, DefaultCountryCode)
    }
}

private fun cleanStudentLocalNumber(input: String, selectedCode: String): String {
    var digits = input.filter(Char::isDigit)
    val codeDigits = selectedCode.filter(Char::isDigit)
    if (digits.startsWith(codeDigits)) {
        digits = digits.removePrefix(codeDigits)
    }
    if (selectedCode == DefaultCountryCode) {
        digits = digits.dropWhile { it == '0' }
    }
    return digits.take(15)
}

private fun composeStudentPhone(countryCode: String, localNumber: String): String {
    val clean = cleanStudentLocalNumber(localNumber, countryCode)
    return if (clean.isBlank()) "" else "$countryCode$clean"
}

