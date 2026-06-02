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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.domain.SessionManager
import com.example.ui.components.PhoneInputField

// ── Colors ──────────────────────────────────────────────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val AccentRed     = Color(0xFFEF4444)

// Available permission flags for staff role assignment
private val availablePermissions = mapOf(
    "view_student" to "View Students",
    "view_batch" to "View Batches",
    "view_fee_summary" to "View Fee Summary",
    "collect_fee" to "Collect Fees",
    "send_due_message" to "Send Due Messages",
    "manage_staff" to "Manage Staff",
    "view_reports" to "View Reports"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditStaffScreen(
    db: AppDatabase,
    staffId: String? = null, // null = add mode, non-null = edit mode
    onBack: () -> Unit
) {
    val viewModel: StaffViewModel = viewModel(factory = StaffViewModelFactory(db))
    val isEdit = staffId != null
    val isAdmin = remember { SessionManager.isAdmin() }

    var fullName by remember { mutableStateOf("") }
    var roleTitle by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var monthlySalary by remember { mutableStateOf("") }
    var selectedPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loadedStaff by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("active") }

    // Load existing staff data in edit mode
    LaunchedEffect(staffId) {
        if (staffId != null) {
            viewModel.loadStaffById(staffId)
            loadedStaff = false
        }
    }
    val staff by viewModel.selectedStaff.collectAsState()
    LaunchedEffect(staff) {
        if (isEdit && staff != null && !loadedStaff) {
            val s = staff!!
            fullName = s.fullName
            roleTitle = s.roleTitle
            phone = s.phone ?: ""
            monthlySalary = s.monthlySalary.toLong().toString()
            selectedPermissions = s.permissions?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
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
            SectionLabel("Full Name")
            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it },
                placeholder = { Text("Enter full name", color = TextMuted.copy(alpha = 0.5f)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            SectionLabel("Role Title")
            OutlinedTextField(
                value = roleTitle,
                onValueChange = { roleTitle = it },
                placeholder = { Text("e.g. Teacher, Accountant...", color = TextMuted.copy(alpha = 0.5f)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            SectionLabel("Phone")
            PhoneInputField(
                value = phone,
                onValueChange = { phone = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            SectionLabel("Monthly Salary (BDT)")
            OutlinedTextField(
                value = monthlySalary,
                onValueChange = { monthlySalary = it },
                placeholder = { Text("0", color = TextMuted.copy(alpha = 0.5f)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
            )

            // Permission flags
            Spacer(Modifier.height(16.dp))
            SectionLabel("Feature Permissions")
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                availablePermissions.forEach { (flag, label) ->
                    val isChecked = flag in selectedPermissions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isChecked) ElectricBlue.copy(alpha = 0.1f) else CardBg)
                            .border(1.dp, if (isChecked) Cyan.copy(alpha = 0.3f) else BorderSub, RoundedCornerShape(10.dp))
                            .clickable {
                                selectedPermissions = if (isChecked) selectedPermissions - flag else selectedPermissions + flag
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                selectedPermissions = if (checked) selectedPermissions + flag else selectedPermissions - flag
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Cyan, uncheckedColor = BorderSub)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = if (isChecked) TextWhite else TextMuted, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Save button
            val canSave = fullName.isNotBlank() && roleTitle.isNotBlank() && monthlySalary.isNotBlank() && (monthlySalary.toDoubleOrNull() ?: 0.0) >= 0
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (canSave) Modifier.background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                        else Modifier.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(14.dp))
                    ),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = {
                        val salary = monthlySalary.toDoubleOrNull() ?: 0.0
                        val permStr = selectedPermissions.joinToString(",").takeIf { it.isNotEmpty() }
                        if (isEdit && staffId != null) {
                            viewModel.updateStaff(staffId, fullName, roleTitle, phone, salary, permStr, status, onSuccess = onBack)
                        } else {
                            viewModel.addStaff(fullName, roleTitle, phone, salary, permStr, null, onSuccess = onBack)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    enabled = canSave,
                    colors = ButtonDefaults.textButtonColors(contentColor = if (canSave) Color.White else TextMuted, disabledContentColor = TextMuted)
                ) {
                    Text(if (isEdit) "Update Staff" else "Save Staff", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
    Spacer(Modifier.height(4.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
    focusedBorderColor = ElectricBlue, unfocusedBorderColor = BorderSub,
    focusedContainerColor = CardBgAlt, unfocusedContainerColor = CardBgAlt, cursorColor = Cyan,
    focusedLabelColor = Cyan, unfocusedLabelColor = TextMuted
)
