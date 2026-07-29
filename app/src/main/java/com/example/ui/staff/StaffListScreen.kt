package com.batchfee.edu.ui.staff

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.StaffPermissions

// ── Colors ──────────────────────────────────────────────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val SkyBlue       = Color(0xFF38BDF8)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val WAGreen       = Color(0xFF25D366)
private val AccentRed     = Color(0xFFEF4444)
private val AccentAmber   = Color(0xFFF59E0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffListScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onAddStaff: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    onNavigateToPricing: () -> Unit
) {
    val viewModel: StaffViewModel = viewModel(factory = StaffViewModelFactory(db))
    val staffList by viewModel.staffList.collectAsState()
    val archivedStaff by viewModel.archivedStaffList.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf("all") }
    var showArchived by remember { mutableStateOf(false) }
    val isAdmin = remember { SessionManager.isAdmin() }
    var showArchiveConfirm by remember { mutableStateOf<String?>(null) }
    var showRestoreConfirm by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val displayed = remember(staffList, filterStatus, searchQuery, showArchived, archivedStaff) {
        val source = if (showArchived) archivedStaff else staffList
        var list = when (filterStatus) {
            "active" -> source.filter { it.status == "active" }
            "inactive" -> source.filter { it.status != "active" }
            else -> source
        }
        if (searchQuery.isNotBlank()) {
            list = list.filter {
                it.fullName.contains(searchQuery, ignoreCase = true) ||
                    it.roleTitle.contains(searchQuery, ignoreCase = true) ||
                    it.staffCode.contains(searchQuery, ignoreCase = true) ||
                    (it.phone?.contains(searchQuery, ignoreCase = true) == true)
            }
        }
        list
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text(if (showArchived) "Archived Staff" else "Staff Management", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        },
        floatingActionButton = {
            if (isAdmin) {
                FloatingActionButton(
                    onClick = onAddStaff,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Staff", tint = Color.White)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { newQ -> searchQuery = newQ; viewModel.setSearchQuery(newQ) },
                placeholder = { Text("Search name, role, ID, phone...", color = TextMuted.copy(alpha = 0.5f), fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                    focusedBorderColor = ElectricBlue, unfocusedBorderColor = BorderSub,
                    focusedContainerColor = CardBg, unfocusedContainerColor = CardBg, cursorColor = Cyan
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(6.dp))

            // Filter chips
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                listOf("all" to "All", "active" to "Active", "inactive" to "Inactive").forEach { (f, label) ->
                    FilterChip(
                        selected = filterStatus == f,
                        onClick = { filterStatus = f },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricBlue.copy(alpha = 0.2f),
                            selectedLabelColor = Cyan
                        )
                    )
                }
                Spacer(Modifier.weight(1f))
                if (isAdmin && archivedStaff.isNotEmpty()) {
                    TextButton(onClick = { showArchived = !showArchived; filterStatus = "all" }) {
                        Icon(if (showArchived) Icons.Filled.Visibility else Icons.Filled.Archive, null, tint = if (showArchived) Cyan else TextMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (showArchived) "Active" else "Archive", color = if (showArchived) Cyan else TextMuted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            // Count
            val sourceList = if (showArchived) archivedStaff else staffList
            val activeCount = sourceList.count { it.status == "active" }
            Text("${displayed.size} staff - $activeCount active", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))

            if (displayed.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No staff found.", color = TextMuted, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(displayed, key = { it.id }) { staff ->
                        StaffCard(
                            staff = staff,
                            showArchived = showArchived,
                            isAdmin = isAdmin,
                            onClick = { onNavigateToProfile(staff.id) },
                            onCall = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${staff.phone ?: ""}"))) },
                            onSms = { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${staff.phone ?: ""}"))) },
                            onWhatsApp = {
                                val enc = java.net.URLEncoder.encode("Hello ${staff.fullName}, ", "UTF-8")
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${staff.phone?.replace("+","")?.replace(" ","") ?: ""}?text=$enc")))
                            },
                            onArchive = { showArchiveConfirm = staff.id },
                            onRestore = { showRestoreConfirm = staff.id }
                        )
                    }
                }
            }
        }
    }

    // Archive confirmation
    if (showArchiveConfirm != null) {
        AlertDialog(
            onDismissRequest = { showArchiveConfirm = null },
            containerColor = CardBg,
            title = { Text("Archive Staff?", color = TextWhite) },
            text = { Text("The staff will be moved to archive.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = { showArchiveConfirm?.let { viewModel.archiveStaff(it) { showArchiveConfirm = null } } }) { Text("Archive", color = AccentRed) }
            },
            dismissButton = { TextButton(onClick = { showArchiveConfirm = null }) { Text("Cancel", color = TextMuted) } }
        )
    }

    // Restore confirmation
    if (showRestoreConfirm != null) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = null },
            containerColor = CardBg,
            title = { Text("Restore Staff?", color = TextWhite) },
            text = { Text("The staff will be restored to active list.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = { showRestoreConfirm?.let { viewModel.restoreStaff(it) { showRestoreConfirm = null } } }) { Text("Restore", color = Color(0xFF10B981)) }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = null }) { Text("Cancel", color = TextMuted) } }
        )
    }
}

@Composable
private fun StaffCard(
    staff: com.batchfee.edu.data.models.StaffEntity,
    showArchived: Boolean,
    isAdmin: Boolean,
    onClick: () -> Unit,
    onCall: () -> Unit,
    onSms: () -> Unit,
    onWhatsApp: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit
) {
    val statusColor = if (staff.status == "active") Color(0xFF10B981) else Color(0xFFEF4444)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(ElectricBlue, Cyan))), contentAlignment = Alignment.Center) {
                    Text(staff.fullName.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(staff.fullName, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(staff.staffCode, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(staff.roleTitle, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(staff.status.replaceFirstChar { it.uppercase() }, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = BorderSub)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickActionChip(Icons.Filled.Call, "Call", statusColor.copy(alpha = 0.8f), Modifier.weight(1f), onCall)
                QuickActionChip(Icons.Filled.Sms, "SMS", ElectricBlue, Modifier.weight(1f), onSms)
                QuickActionChip(Icons.Filled.Whatsapp, "WA", Color(0xFF25D366), Modifier.weight(1f), onWhatsApp)
                if (isAdmin) {
                    if (showArchived) {
                        QuickActionChip(Icons.Filled.Refresh, "Restore", Color(0xFF10B981), Modifier.weight(1f), onRestore)
                    } else {
                        QuickActionChip(Icons.Filled.Archive, "Archive", Color(0xFFF59E0B), Modifier.weight(1f), onArchive)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier.height(28.dp).clip(RoundedCornerShape(7.dp)).background(color.copy(alpha = 0.1f)).border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(7.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

