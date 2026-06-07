package com.example.ui.staff

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.domain.SessionManager
import com.example.domain.StaffPermissions
import com.example.ui.components.PhoneInputField

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditStaffScreen(
    db: AppDatabase,
    staffId: String? = null,
    onBack: () -> Unit
) {
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
            val canSave = fullName.isNotBlank() &&
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
                        if (isEdit && staffId != null) {
                            viewModel.updateStaff(
                                staffId = staffId,
                                fullName = fullName,
                                staffCode = loginId,
                                roleTitle = roleTitle,
                                phone = phone,
                                email = email,
                                monthlySalary = salary,
                                permissions = selectedPermissions,
                                assignedBatchIds = selectedBatchIds,
                                status = status,
                                password = password.takeIf { it.isNotBlank() },
                                onSuccess = onBack,
                                onError = { errorMessage = it }
                            )
                        } else {
                            viewModel.addStaff(
                                fullName = fullName,
                                staffCode = loginId,
                                roleTitle = roleTitle,
                                phone = phone,
                                email = email,
                                monthlySalary = salary,
                                permissions = selectedPermissions,
                                assignedBatchIds = selectedBatchIds,
                                password = password,
                                status = status,
                                onSuccess = onBack,
                                onError = { errorMessage = it }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    enabled = canSave,
                    colors = ButtonDefaults.textButtonColors(contentColor = if (canSave) Color.White else TextMuted, disabledContentColor = TextMuted)
                ) {
                    Text(if (isEdit) "Update Staff Account" else "Create Staff Account", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
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
