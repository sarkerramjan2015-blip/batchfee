package com.example.ui.enquiries

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.models.EnquiryEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
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
private val AccentAmber = Color(0xFFF59E0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnquiryListScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onAddEnquiry: () -> Unit
) {
    val viewModel: EnquiryViewModel = viewModel(factory = EnquiryViewModelFactory(db))
    val allEnquiries by viewModel.allEnquiries.collectAsState()
    val filterStatus by viewModel.filterStatus.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val todayCount by viewModel.todayCount.collectAsState()
    val followUpCount by viewModel.followUpCount.collectAsState()

    var selectedEnquiry by remember { mutableStateOf<EnquiryEntity?>(null) }
    var showStatusDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    var newSubject by remember { mutableStateOf("") }
    var newAddress by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    val filteredEnquiries = remember(allEnquiries, filterStatus) {
        when (filterStatus) {
            "follow_up" -> allEnquiries.filter {
                it.status.equals("follow_up", ignoreCase = true) ||
                it.status.equals("follow up", ignoreCase = true)
            }
            "active" -> allEnquiries.filter { it.status.equals("active", ignoreCase = true) }
            "close" -> allEnquiries.filter {
                it.status.equals("close", ignoreCase = true) ||
                it.status.equals("closed", ignoreCase = true)
            }
            else -> allEnquiries
        }
    }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Enquiries", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite)
                    }
                },
                actions = {
                    IconButton(onClick = onAddEnquiry) {
                        Icon(Icons.Filled.Add, "Add Enquiry", tint = Cyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = Color.Transparent,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
            ) {
                Icon(Icons.Default.Add, "Add", tint = Color.White)
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cyan, strokeWidth = 3.dp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── Summary row ────────────────────────────
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SummaryChip("Today", "$todayCount", Cyan, filterStatus == "today", Modifier.weight(1f)) {
                            // Today filter just shows count, no separate filter
                        }
                        SummaryChip("Follow Up", "$followUpCount", AccentAmber, filterStatus == "follow_up", Modifier.weight(1f)) {
                            viewModel.setFilter(if (filterStatus == "follow_up") "all" else "follow_up")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // ── Filter chips ────────────────────────────
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("all" to "All", "active" to "Active", "follow_up" to "Follow Up", "close" to "Closed").forEach { (key, label) ->
                            val isSel = filterStatus == key
                            FilterChip(
                                selected = isSel,
                                onClick = { viewModel.setFilter(key) },
                                label = {
                                    Text(
                                        label,
                                        fontSize = 12.sp,
                                        color = if (isSel) Color.White else TextMuted
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricBlue.copy(alpha = 0.25f),
                                    selectedLabelColor = Color.White,
                                    containerColor = CardBg,
                                    labelColor = TextMuted
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = BorderSub,
                                    selectedBorderColor = Cyan,
                                    enabled = true,
                                    selected = isSel
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${filteredEnquiries.size} enquiry${if (filteredEnquiries.size != 1) "ies" else ""}",
                        color = TextMuted, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(2.dp))
                }

                if (filteredEnquiries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.ContactPhone, null,
                                    tint = TextMuted.copy(alpha = 0.4f), modifier = Modifier.size(56.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text("No enquiries found.", color = TextMuted, fontSize = 15.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Tap + to add a new enquiry.",
                                    color = TextMuted.copy(alpha = 0.6f), fontSize = 12.sp
                                )
                            }
                        }
                    }
                } else {
                    items(filteredEnquiries, key = { it.id }) { enquiry ->
                        EnquiryCard(
                            enquiry = enquiry,
                            dateFormat = dateFormat,
                            onClick = {
                                selectedEnquiry = enquiry
                                showStatusDialog = true
                            }
                        )
                    }
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    // ── Add enquiry dialog ─────────────────────────────
    if (showAddDialog) {
        Dialog(onDismissRequest = { showAddDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("New Enquiry", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))

                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("Name *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newPhone, onValueChange = { newPhone = it },
                        label = { Text("Phone *") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newSubject, onValueChange = { newSubject = it },
                        label = { Text("Subject *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newAddress, onValueChange = { newAddress = it },
                        label = { Text("Address (optional)") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAddDialog = false },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderSub)
                        ) { Text("Cancel", color = TextMuted) }

                        Button(
                            onClick = {
                                viewModel.addEnquiry(
                                    name = newName, phone = newPhone,
                                    address = newAddress, subjectName = newSubject,
                                    enquiryDateMs = System.currentTimeMillis(),
                                    onSuccess = {
                                        showAddDialog = false
                                        newName = ""; newPhone = ""; newSubject = ""; newAddress = ""
                                        scope.launch { snackbarHostState.showSnackbar("Enquiry saved.") }
                                    },
                                    onError = { msg ->
                                        scope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                )
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                        ) { Text("Save", color = Color.White) }
                    }
                }
            }
        }
    }

    // ── Status change dialog ────────────────────────────
    if (showStatusDialog && selectedEnquiry != null) {
        val e = selectedEnquiry!!
        val statusLabel = when {
            e.status.equals("follow_up", ignoreCase = true) ||
            e.status.equals("follow up", ignoreCase = true) -> "Follow Up"
            e.status.equals("active", ignoreCase = true) -> "Active"
            else -> "Closed"
        }
        val statusColor = when {
            e.status.equals("follow_up", ignoreCase = true) ||
            e.status.equals("follow up", ignoreCase = true) -> AccentAmber
            e.status.equals("active", ignoreCase = true) -> Cyan
            else -> TextMuted
        }

        Dialog(onDismissRequest = { showStatusDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Header
                    Text(
                        e.name,
                        color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Phone, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(e.phone, color = TextMuted, fontSize = 13.sp)
                    }
                    if (!e.subjectName.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text("Subject: ${e.subjectName}", color = TextMuted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Date: ${dateFormat.format(Date(e.enquiryDateMs))}",
                        color = TextMuted, fontSize = 12.sp
                    )

                    Spacer(Modifier.height(14.dp))

                    // Current status badge
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Status: ", color = TextMuted, fontSize = 14.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(statusColor.copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = BorderSub)
                    Spacer(Modifier.height(12.dp))

                    Text("Change Status", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusButton("Active", Icons.Filled.FiberManualRecord, Cyan, Modifier.weight(1f)) {
                            viewModel.updateStatus(e, "active") { msg ->
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                            showStatusDialog = false
                        }
                        StatusButton("Follow Up", Icons.Filled.Refresh, AccentAmber, Modifier.weight(1f)) {
                            viewModel.updateStatus(e, "follow_up") { msg ->
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                            showStatusDialog = false
                        }
                        StatusButton("Closed", Icons.Filled.CheckCircle, AccentGreen, Modifier.weight(1f)) {
                            viewModel.updateStatus(e, "close") { msg ->
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                            showStatusDialog = false
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = { showStatusDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel", color = TextMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(
    label: String,
    value: String,
    accent: Color,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(12.dp), spotColor = accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) accent.copy(alpha = 0.12f) else CardBg
        ),
        border = BorderStroke(1.dp, if (isActive) accent.copy(alpha = 0.5f) else BorderSub)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(label, color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EnquiryCard(
    enquiry: EnquiryEntity,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    val statusColor = when {
        enquiry.status.equals("follow_up", ignoreCase = true) ||
        enquiry.status.equals("follow up", ignoreCase = true) -> AccentAmber
        enquiry.status.equals("active", ignoreCase = true) -> Cyan
        else -> TextMuted
    }
    val statusLabel = when {
        enquiry.status.equals("follow_up", ignoreCase = true) ||
        enquiry.status.equals("follow up", ignoreCase = true) -> "Follow Up"
        enquiry.status.equals("active", ignoreCase = true) -> "Active"
        else -> "Closed"
    }

    Card(
        modifier = Modifier.fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp), spotColor = statusColor.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    enquiry.name.take(1).uppercase(),
                    color = statusColor, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    enquiry.name,
                    color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${enquiry.phone} · ${enquiry.subjectName}",
                    color = TextMuted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    dateFormat.format(Date(enquiry.enquiryDateMs)),
                    color = TextMuted.copy(alpha = 0.6f), fontSize = 11.sp
                )
            }

            Spacer(Modifier.width(8.dp))

            // Status badge
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Icon(
                    Icons.Filled.ChevronRight, null,
                    tint = TextMuted.copy(alpha = 0.3f), modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
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
