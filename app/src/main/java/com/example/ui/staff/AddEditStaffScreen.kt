package com.batchfee.edu.ui.staff

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Whatsapp
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.StaffPermissions
import com.batchfee.edu.ui.components.PhoneInputField
import com.batchfee.edu.ui.components.SquarePhotoCropDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val CardBgAlt = Color(0xFF111827)
private val BorderSub = Color(0xFF1E293B)
private val Cyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val AccentRed = Color(0xFFEF4444)
private val AccentGreen = Color(0xFF22C55E)
private val AccentAmber = Color(0xFFF59E0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditStaffScreen(
    db: AppDatabase,
    staffId: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: StaffViewModel = viewModel(factory = StaffViewModelFactory(db))
    val isEdit = staffId != null
    val isAdmin = remember { SessionManager.isAdmin() }

    val generatedLoginId = remember { "STF${System.currentTimeMillis().toString().takeLast(6)}" }
    var fullName by remember { mutableStateOf("") }
    var loginId by remember { mutableStateOf(generatedLoginId) }
    var roleTitle by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var monthlySalary by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var selectedPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedBatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loadedStaff by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("active") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCredentialShare by remember { mutableStateOf(false) }
    var savedCredentials by remember { mutableStateOf(CredentialInfo("", "")) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var originalPhotoReference by remember { mutableStateOf<String?>(null) }
    var cropSourceUri by remember { mutableStateOf<Uri?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val photoSaveScope = rememberCoroutineScope()

    val tempPhotoFile = remember {
        File(context.cacheDir, "staff_photo_${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }
    }
    val tempPhotoUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempPhotoFile)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cropSourceUri = Uri.fromFile(tempPhotoFile)
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            photoSaveScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        FirebaseStorageImageUploadHelper.cacheSelectedImage(context, uri, "staff_photo")
                    }
                }.onSuccess { cropSourceUri = it }
                    .onFailure {
                        errorMessage = it.message ?: "Could not read this image. Please choose it again."
                    }
            }
        }
    }

    val staff by viewModel.selectedStaff.collectAsState()
    val batches by viewModel.batches.collectAsState()

    LaunchedEffect(staffId) {
        if (staffId != null) {
            viewModel.loadStaffById(staffId)
            loadedStaff = false
        }
    }

    LaunchedEffect(staff) {
        if (isEdit && staff != null && !loadedStaff) {
            val s = staff!!
            fullName = s.fullName
            loginId = s.staffCode
            roleTitle = s.roleTitle
            phone = s.phone ?: ""
            email = s.email ?: ""
            monthlySalary = if (s.monthlySalary % 1.0 == 0.0) s.monthlySalary.toLong().toString() else s.monthlySalary.toString()
            selectedPermissions = StaffPermissions.parse(s.permissions)
            selectedBatchIds = s.assignedBatchIds?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            status = s.status
            originalPhotoReference = s.photoUri
            photoUri = s.photoUri?.let(Uri::parse)
            loadedStaff = true
        }
    }

    if (!isAdmin) {
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            Text("Only admins can manage staff.", color = TextMuted, fontSize = 14.sp)
        }
        return
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit Staff" else "Add Staff", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            CredentialNotice(isEdit)

            Spacer(Modifier.height(14.dp))
            StaffPhotoPicker(
                photoUri = photoUri,
                onCameraClick = { cameraLauncher.launch(tempPhotoUri) },
                onGalleryClick = { galleryLauncher.launch("image/*") }
            )
            Spacer(Modifier.height(16.dp))
            SectionLabel("Staff Login ID")
            DarkTextField(
                value = loginId,
                onValueChange = { loginId = it.trim().uppercase() },
                placeholder = "e.g. STF001",
                leadingIcon = { Icon(Icons.Filled.Badge, null, tint = TextMuted) }
            )
            Spacer(Modifier.height(12.dp))

            SectionLabel(if (isEdit) "New Password (Optional)" else "Temporary Password")
            DarkTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = if (isEdit) "Leave blank to keep old password" else "Minimum 4 characters",
                leadingIcon = { Icon(Icons.Filled.Lock, null, tint = TextMuted) },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardType = KeyboardType.Password,
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, tint = TextMuted)
                    }
                }
            )
            Spacer(Modifier.height(14.dp))

            SectionLabel("Full Name")
            DarkTextField(
                value = fullName,
                onValueChange = { fullName = it },
                placeholder = "Enter full name",
                leadingIcon = { Icon(Icons.Filled.Person, null, tint = TextMuted) }
            )
            Spacer(Modifier.height(12.dp))

            SectionLabel("Role Title")
            DarkTextField(
                value = roleTitle,
                onValueChange = { roleTitle = it },
                placeholder = "Teacher, Accountant, Manager",
                leadingIcon = { Icon(Icons.Filled.Key, null, tint = TextMuted) }
            )
            Spacer(Modifier.height(12.dp))

            SectionLabel("Phone")
            PhoneInputField(
                value = phone,
                onValueChange = { phone = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            SectionLabel("Email (Required)")
            DarkTextField(
                value = email,
                onValueChange = { email = it },
                placeholder = "staff@example.com",
                leadingIcon = { Icon(Icons.Filled.Email, null, tint = TextMuted) },
                keyboardType = KeyboardType.Email
            )
            Spacer(Modifier.height(12.dp))

            SectionLabel("Monthly Salary (BDT)")
            DarkTextField(
                value = monthlySalary,
                onValueChange = { monthlySalary = it },
                placeholder = "0",
                keyboardType = KeyboardType.Number
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("Account Status")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatusChip("active", "Active", status == "active", AccentGreen, Modifier.weight(1f)) { status = "active" }
                StatusChip("inactive", "Inactive", status != "active", AccentRed, Modifier.weight(1f)) { status = "inactive" }
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("Assigned Batches")
            Spacer(Modifier.height(6.dp))
            if (batches.isEmpty()) {
                EmptyPanel("No batches available yet.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    batches.forEach { batch ->
                        val selected = batch.id in selectedBatchIds
                        SelectRow(
                            title = batch.name,
                            subtitle = listOfNotNull(batch.subject, batch.className).joinToString(" • "),
                            selected = selected,
                            onClick = {
                                selectedBatchIds = if (selected) selectedBatchIds - batch.id else selectedBatchIds + batch.id
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("Feature Permissions")
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                StaffPermissions.options.forEach { option ->
                    val selected = option.key in selectedPermissions
                    SelectRow(
                        title = option.label,
                        subtitle = option.description,
                        selected = selected,
                        onClick = {
                            selectedPermissions = if (selected) selectedPermissions - option.key else selectedPermissions + option.key
                        }
                    )
                }
            }

            errorMessage?.let {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentRed.copy(alpha = 0.12f))
                        .border(1.dp, AccentRed.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(it, color = Color(0xFFFCA5A5), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(20.dp))
            val salary = monthlySalary.toDoubleOrNull()
            val canSave = !isSaving && fullName.isNotBlank() &&
                loginId.isNotBlank() &&
                email.isNotBlank() &&
                roleTitle.isNotBlank() &&
                salary != null &&
                salary >= 0 &&
                (isEdit || password.length >= 4)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (canSave) Modifier.background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                        else Modifier.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(14.dp))
                    ),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = {
                        errorMessage = null
                        if (salary == null) {
                            errorMessage = "Enter a valid monthly salary."
                            return@TextButton
                        }
                        isSaving = true
                        photoSaveScope.launch {
                            try {
                                if (isEdit && staffId != null) {
                                    val cloudPhotoUrl = photoUri?.let { selectedUri ->
                                        if (selectedUri.scheme == "https" || selectedUri.scheme == "http") {
                                            selectedUri.toString()
                                        } else {
                                            FirebaseStorageImageUploadHelper.uploadStaffPhoto(
                                                context = context,
                                                sourceUri = selectedUri,
                                                subjectId = staffId,
                                                replacesReference = originalPhotoReference
                                            )
                                        }
                                    }
                                    viewModel.updateStaff(
                                        staffId = staffId,
                                        fullName = fullName,
                                        staffCode = loginId,
                                        photoUri = cloudPhotoUrl,
                                        roleTitle = roleTitle,
                                        phone = phone,
                                        email = email,
                                        monthlySalary = salary,
                                        permissions = selectedPermissions,
                                        assignedBatchIds = selectedBatchIds,
                                        status = status,
                                        password = password.takeIf { it.isNotBlank() },
                                        onSuccess = {
                                            isSaving = false
                                            onBack()
                                        },
                                        onError = {
                                            errorMessage = it
                                            isSaving = false
                                        }
                                    )
                                } else {
                                    viewModel.addStaff(
                                        fullName = fullName,
                                        staffCode = loginId,
                                        // A new staff profile does not have its Firebase UID until this call
                                        // completes. Upload the photo immediately after creation using that UID.
                                        photoUri = null,
                                        roleTitle = roleTitle,
                                        phone = phone,
                                        email = email,
                                        monthlySalary = salary,
                                        permissions = selectedPermissions,
                                        assignedBatchIds = selectedBatchIds,
                                        password = password,
                                        status = status,
                                        onSuccess = { createdStaffId, loginIdResult, staffPassword, _ ->
                                            val showCredentials = {
                                                isSaving = false
                                                savedCredentials = CredentialInfo(loginIdResult, staffPassword)
                                                showCredentialShare = true
                                            }
                                            val selectedPhoto = photoUri
                                            if (selectedPhoto == null) {
                                                showCredentials()
                                            } else {
                                                photoSaveScope.launch {
                                                    try {
                                                        val cloudPhotoUrl = if (
                                                            selectedPhoto.scheme == "https" || selectedPhoto.scheme == "http"
                                                        ) {
                                                            selectedPhoto.toString()
                                                        } else {
                                                            FirebaseStorageImageUploadHelper.uploadStaffPhoto(
                                                                context = context,
                                                                sourceUri = selectedPhoto,
                                                                subjectId = createdStaffId,
                                                                replacesReference = null,
                                                            )
                                                        }
                                                        viewModel.updateStaffPhoto(
                                                            staffId = createdStaffId,
                                                            photoUri = cloudPhotoUrl,
                                                            onSuccess = showCredentials,
                                                            onError = {
                                                                errorMessage = "Staff was created, but photo upload failed: $it"
                                                                isSaving = false
                                                            },
                                                        )
                                                    } catch (error: Exception) {
                                                        errorMessage = "Staff was created, but photo upload failed: ${error.message ?: "Try editing this staff again."}"
                                                        isSaving = false
                                                    }
                                                }
                                            }
                                        },
                                        onError = {
                                            errorMessage = it
                                            isSaving = false
                                        }
                                    )
                                }
                            } catch (error: Exception) {
                                errorMessage = error.message ?: "Photo upload failed. Check your connection and try again."
                                isSaving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    enabled = canSave,
                    colors = ButtonDefaults.textButtonColors(contentColor = if (canSave) Color.White else TextMuted, disabledContentColor = TextMuted)
                ) {
                    Text(
                        if (isSaving) "Optimizing photo..." else if (isEdit) "Update Staff Account" else "Create Staff Account",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // Credential sharing dialog
    if (showCredentialShare) {
        CredentialShareDialog(
            loginId = savedCredentials.loginId,
            password = savedCredentials.password,
            onDismiss = {
                showCredentialShare = false
                onBack()
            }
        )
    }

    cropSourceUri?.let { sourceUri ->
        SquarePhotoCropDialog(
            sourceUri = sourceUri,
            onCropped = { croppedUri ->
                photoUri = croppedUri
                cropSourceUri = null
            },
            onDismiss = { cropSourceUri = null },
        )
    }
}

private data class CredentialInfo(val loginId: String, val password: String)

@Composable
private fun StaffPhotoPicker(
    photoUri: Uri?,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(CardBgAlt)
                .border(1.dp, Cyan.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (photoUri != null) {
                AsyncImage(
                    model = photoUri,
                    contentDescription = "Staff photo preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Icon(Icons.Filled.Person, contentDescription = null, tint = Cyan, modifier = Modifier.size(30.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Staff Photo", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("App ছবিটি optimize করে কম data-তে upload করবে.", color = TextMuted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCameraClick, shape = RoundedCornerShape(9.dp)) {
                    Text("Camera", fontSize = 11.sp)
                }
                OutlinedButton(onClick = onGalleryClick, shape = RoundedCornerShape(9.dp)) {
                    Text("Gallery", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun CredentialNotice(isEdit: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, Cyan.copy(alpha = 0.25f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Cyan.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.CheckCircle, null, tint = Cyan, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (isEdit) "Login ID updates immediately. Set a new password only when you want to reset access."
                else "Admin-created Staff ID and password will be used on the login screen.",
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = TextMuted.copy(alpha = 0.55f)) },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = darkFieldColors(),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun StatusChip(
    key: String,
    label: String,
    selected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) color.copy(alpha = 0.15f) else CardBg)
            .border(1.dp, if (selected) color.copy(alpha = 0.45f) else BorderSub, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) color else TextMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun SelectRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) ElectricBlue.copy(alpha = 0.11f) else CardBg)
            .border(1.dp, if (selected) Cyan.copy(alpha = 0.35f) else BorderSub, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onClick() },
            colors = CheckboxDefaults.colors(checkedColor = Cyan, uncheckedColor = BorderSub)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (selected) TextWhite else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = TextMuted.copy(alpha = 0.78f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun EmptyPanel(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = TextMuted, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedBorderColor = ElectricBlue,
    unfocusedBorderColor = BorderSub,
    focusedContainerColor = CardBgAlt,
    unfocusedContainerColor = CardBgAlt,
    cursorColor = Cyan,
    focusedLabelColor = Cyan,
    unfocusedLabelColor = TextMuted
)

@Composable
private fun CredentialShareDialog(loginId: String, password: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val appLink = "https://play.google.com/store/apps/details?id=com.batchfee.edu&hl=en"
    val message = "Your BatchFee Staff Account:\n\nID: $loginId\nPassword: $password\n\nDownload the app: $appLink"

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, Cyan.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                        .background(Cyan.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Cyan, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("Staff Account Created", color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Share credentials with staff so they can login.", color = TextMuted, fontSize = 13.sp)

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = BorderSub)
                Spacer(Modifier.height(12.dp))

                // Credential card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBgAlt),
                    border = BorderStroke(1.dp, BorderSub)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Badge, null, tint = Cyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Login ID", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(loginId, color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Staff ID", loginId))
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Filled.ContentCopy, "Copy ID", tint = Cyan, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, null, tint = Cyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Password", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(password, color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Staff Password", password))
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Filled.ContentCopy, "Copy Password", tint = Cyan, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                // Copy all button
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Staff Credentials", message))
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Cyan.copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Filled.ContentCopy, null, tint = Cyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy All", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(8.dp))
                // WhatsApp button
                Button(
                    onClick = {
                        val enc = java.net.URLEncoder.encode(message, "UTF-8")
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$enc")))
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                ) {
                    Icon(Icons.Filled.Whatsapp, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send via WhatsApp", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(8.dp))
                // SMS button
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).apply { putExtra("sms_body", message) })
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Filled.Sms, null, tint = ElectricBlue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send via SMS", color = ElectricBlue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Done", color = TextMuted, fontSize = 14.sp)
                }
            }
        }
    }
}

