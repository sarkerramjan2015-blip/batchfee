package com.batchfee.edu.ui.staff

import android.content.Intent
import android.net.Uri
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.StaffPermissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgColor = Color(0xFF07111F)
private val CardBg = Color(0xFF0F172A)
private val CardBgAlt = Color(0xFF111827)
private val BorderSub = Color(0xFF1E293B)
private val Cyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val TextWhite = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val AccentGreen = Color(0xFF10B981)
private val AccentRed = Color(0xFFEF4444)
private val WAGreen = Color(0xFF25D366)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffProfileScreen(
    db: AppDatabase,
    staffId: String,
    onBack: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onTeachingSessions: (() -> Unit)? = null,
) {
    val viewModel: StaffViewModel = viewModel(factory = StaffViewModelFactory(db))
    val staff by viewModel.selectedStaff.collectAsState()
    val batches by viewModel.batches.collectAsState()
    val activityLogs by viewModel.activityLogs.collectAsState()
    val isAdmin = remember { SessionManager.isAdmin() }
    val context = LocalContext.current
    var showArchiveDialog by remember { mutableStateOf(false) }
    var showShareCredentials by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("dd MMM · hh:mm a", Locale.getDefault()) }

    LaunchedEffect(staffId) {
        viewModel.loadStaffById(staffId)
        viewModel.loadActivityLogs(staffId)
    }

    val s = staff
    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text(s?.fullName ?: "Profile", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextWhite) } },
                actions = {
                    if (isAdmin) {
                        IconButton(onClick = { showShareCredentials = true }) { Icon(Icons.Filled.Share, null, tint = AccentGreen) }
                        if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, null, tint = Cyan) }
                    }
                    Box {
                        IconButton(onClick = { showArchiveDialog = true }) { Icon(Icons.Filled.MoreVert, null, tint = TextMuted) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        if (s == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cyan) }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header card
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = CardBg), border = BorderStroke(1.dp, BorderSub)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(ElectricBlue, Cyan))), contentAlignment = Alignment.Center) {
                                    Text(s.fullName.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.staffCode, color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(s.fullName, color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(s.roleTitle, color = TextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (s.status == "active") AccentGreen.copy(alpha = 0.12f) else AccentRed.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                                    Text(s.status.replaceFirstChar { it.uppercase() }, color = if (s.status == "active") AccentGreen else AccentRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionChip(Icons.Filled.Call, "Call", AccentGreen, Modifier.weight(1f)) { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${s.phone ?: ""}"))) }
                                ActionChip(Icons.Filled.Sms, "SMS", ElectricBlue, Modifier.weight(1f)) { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${s.phone ?: ""}"))) }
                                ActionChip(Icons.Filled.Whatsapp, "WA", WAGreen, Modifier.weight(1f)) {
                                    val enc = java.net.URLEncoder.encode("Hello ${s.fullName}, ", "UTF-8")
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${s.phone?.replace("+","")?.replace(" ","") ?: ""}?text=$enc")))
                                }
                            }
                        }
                    }
                }

                // Info card
                item {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg), border = BorderStroke(1.dp, BorderSub)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Staff Info", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            InfoRow("Phone", s.phone ?: "N/A")
                            InfoRow("Email", s.email ?: "N/A")
                            val compensation = when (s.salaryType) {
                                "per_class" -> "BDT ${s.perClassRate.toLong()} per class"
                                "per_hour" -> "BDT ${s.perHourRate.toLong()} per hour"
                                else -> "BDT ${s.monthlySalary.toLong()} monthly"
                            }
                            InfoRow("Salary", compensation)
                            if (s.staffCategory == "teacher") InfoRow("Subjects", s.subjects ?: "Not set")
                            InfoRow("Joined", s.joiningDateMs?.let { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it)) } ?: "N/A")
                            InfoRow("Address", s.address ?: "N/A")
                        }
                    }
                }

                // Assigned batches
                item {
                    val ids = s.assignedBatchIds?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                    val assigned = batches.filter { it.id in ids }
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg), border = BorderStroke(1.dp, BorderSub)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Assigned Batches", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            if (assigned.isEmpty()) Text("None", color = TextMuted, fontSize = 13.sp)
                            else assigned.forEach { b ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                    Icon(Icons.Filled.Class, null, tint = Cyan, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(listOfNotNull(b.name, b.subject).joinToString(" - "), color = TextWhite, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }

                if (s.staffCategory == "teacher" && isAdmin && onTeachingSessions != null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onTeachingSessions),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            border = BorderStroke(1.dp, Cyan.copy(alpha = .55f)),
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.DoneAll, null, tint = Cyan)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Teacher Classes", color = TextWhite, fontWeight = FontWeight.SemiBold)
                                    Text("Confirm completed classes and calculate salary", color = TextMuted, fontSize = 12.sp)
                                }
                                Icon(Icons.Filled.ChevronRight, null, tint = Cyan)
                            }
                        }
                    }
                }

                // Permissions
                item {
                    val perms = StaffPermissions.parse(s.permissions).toList()
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg), border = BorderStroke(1.dp, BorderSub)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Permissions (${perms.size})", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            if (perms.isEmpty()) Text("None assigned", color = TextMuted, fontSize = 13.sp)
                            else {
                                perms.chunked(2).forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        row.forEach { p ->
                                            Box(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(Cyan.copy(alpha = 0.1f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                                Text(StaffPermissions.labelFor(p), color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        if (row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }

                // Activity log section
                item {
                    Text("Recent Activity", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                if (activityLogs.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg), border = BorderStroke(1.dp, BorderSub)) {
                            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.History, null, tint = TextMuted.copy(alpha = 0.4f), modifier = Modifier.size(40.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text("No activity logged yet.", color = TextMuted, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                } else {
                    items(activityLogs.take(20), key = { it.id }) { log ->
                        ActivityLogRow(log, timeFmt)
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // Share credentials
    if (showShareCredentials && s != null) {
        val staffData = s!!
        var sharePassword by remember { mutableStateOf("") }
        var passwordVisible by remember { mutableStateOf(false) }
        val appLink = "https://play.google.com/store/apps/details?id=com.batchfee.edu&hl=en"
        val loginMsg = if (sharePassword.isNotBlank()) {
            "Staff Login Details:\n\nID: ${staffData.staffCode}\nPassword: $sharePassword\nName: ${staffData.fullName}\nRole: ${staffData.roleTitle}\n\nDownload the app: $appLink"
        } else {
            "Staff Login Details:\n\nID: ${staffData.staffCode}\nName: ${staffData.fullName}\nRole: ${staffData.roleTitle}\n\nDownload the app: $appLink"
        }

        AlertDialog(
            onDismissRequest = { showShareCredentials = false; sharePassword = "" },
            containerColor = CardBg,
            title = { Text("Share Credentials", color = TextWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Include the staff password so they can login.", color = TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = sharePassword,
                        onValueChange = { sharePassword = it },
                        label = { Text("Staff Password", color = TextMuted) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null,
                                    tint = TextMuted
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            cursorColor = Cyan
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(loginMsg, color = TextMuted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Staff Credentials", loginMsg))
                    showShareCredentials = false
                    sharePassword = ""
                }) { Text("Copy", color = Cyan, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val enc = java.net.URLEncoder.encode(loginMsg, "UTF-8")
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=$enc")))
                        showShareCredentials = false
                        sharePassword = ""
                    }) { Text("WhatsApp", color = WAGreen, fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = {
                        showShareCredentials = false
                        sharePassword = ""
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${staffData.phone ?: ""}")).apply { putExtra("sms_body", loginMsg) })
                    }) { Text("SMS", color = ElectricBlue, fontWeight = FontWeight.SemiBold) }
                }
            }
        )
    }

    // Archive
    if (showArchiveDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            containerColor = CardBg,
            title = { Text("Archive Staff?", color = TextWhite, fontWeight = FontWeight.Bold) },
            text = { Text("${s?.fullName} will be archived and no longer appear in active lists.", color = TextMuted) },
            confirmButton = { TextButton(onClick = { viewModel.archiveStaff(staffId) { showArchiveDialog = false; onBack() } }) { Text("Archive", color = AccentRed, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showArchiveDialog = false }) { Text("Cancel", color = TextMuted) } }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 13.sp)
        Text(value, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.1f)).border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActivityLogRow(log: com.batchfee.edu.data.models.AuditLogEntity, timeFmt: SimpleDateFormat) {
    val icon = when {
        log.action.contains("login", ignoreCase = true) -> Icons.Filled.Login
        log.action.contains("payment", ignoreCase = true) || log.action.contains("fee", ignoreCase = true) || log.action.contains("collect", ignoreCase = true) -> Icons.Filled.Payments
        log.action.contains("attendance", ignoreCase = true) -> Icons.Filled.CheckCircle
        log.action.contains("exam", ignoreCase = true) -> Icons.Filled.School
        log.action.contains("salary", ignoreCase = true) -> Icons.Filled.MonetizationOn
        else -> Icons.Filled.Circle
    }
    val color = when {
        log.action.contains("login", ignoreCase = true) -> Cyan
        log.action.contains("payment", ignoreCase = true) || log.action.contains("fee", ignoreCase = true) || log.action.contains("collect", ignoreCase = true) -> AccentGreen
        log.action.contains("attendance", ignoreCase = true) -> Cyan
        else -> TextMuted
    }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = CardBgAlt), border = BorderStroke(1.dp, BorderSub)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(log.description, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(timeFmt.format(Date(log.createdAtMs)), color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}
