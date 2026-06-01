package com.example.ui.staff

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.domain.SessionManager

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
    var searchQuery by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf("all") }
    val isAdmin = remember { SessionManager.isAdmin() }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    val displayed = remember(staffList, filterStatus, searchQuery) {
        var list = when (filterStatus) {
            "active" -> staffList.filter { it.status == "active" }
            "inactive" -> staffList.filter { it.status != "active" }
            else -> staffList
        }
        if (searchQuery.isNotBlank()) {
            list = list.filter { it.fullName.contains(searchQuery, ignoreCase = true) || it.roleTitle.contains(searchQuery, ignoreCase = true) }
        }
        list
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Staff Management", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
                placeholder = { Text("Search by name or role...", color = TextMuted.copy(alpha = 0.5f), fontSize = 13.sp) },
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            }
            Spacer(Modifier.height(4.dp))

            // Count
            Text("${displayed.size} staff", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(3.dp, RoundedCornerShape(12.dp), spotColor = Cyan.copy(alpha = 0.15f))
                                .clickable { onNavigateToProfile(staff.id) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            border = BorderStroke(1.dp, BorderSub)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Avatar
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(staff.fullName.take(1).uppercase(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(staff.fullName, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(staff.roleTitle, color = TextMuted, fontSize = 12.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("BDT ${staff.monthlySalary}", color = TextMuted, fontSize = 12.sp)
                                    }
                                }
                                // Status badge
                                val statusColor = if (staff.status == "active") WAGreen else AccentRed
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(statusColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(staff.status.replaceFirstChar { it.uppercase() }, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            containerColor = CardBg,
            title = { Text("Remove Staff?", color = TextWhite) },
            text = { Text("This staff will be archived.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm?.let { viewModel.archiveStaff(it) { showDeleteConfirm = null } }
                }) { Text("Remove", color = AccentRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel", color = TextMuted) }
            }
        )
    }
}
