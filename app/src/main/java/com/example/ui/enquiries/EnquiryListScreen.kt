package com.batchfee.edu.ui.enquiries

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.EnquiryEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

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
private val WAGreen = Color(0xFF25D366)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnquiryListScreen(db: AppDatabase, onBack: () -> Unit, onAddEnquiry: () -> Unit) {
    val viewModel: EnquiryViewModel = viewModel(factory = EnquiryViewModelFactory(db))
    val allEnquiries by viewModel.allEnquiries.collectAsState()
    val filterStatus by viewModel.filterStatus.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val todayCount by viewModel.todayCount.collectAsState()
    val followUpCount by viewModel.followUpCount.collectAsState()
    val todayFollowUpCount by viewModel.todayFollowUpCount.collectAsState()
    val overdueFollowUpCount by viewModel.overdueFollowUpCount.collectAsState()

    var selectedEnquiry by remember { mutableStateOf<EnquiryEntity?>(null) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    var newSubject by remember { mutableStateOf("") }
    var newAddress by remember { mutableStateOf("") }
    var isSavingEnquiry by remember { mutableStateOf(false) }
    val pendingEnquiryId = remember(showAddDialog) { UUID.randomUUID().toString() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    val filteredEnquiries = remember(allEnquiries, filterStatus) {
        when (filterStatus) {
            "follow_up" -> allEnquiries
                .filter { isFollowUp(it.status) }
                .sortedWith(compareBy<EnquiryEntity> { it.followUpDateMs ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() })
            "active" -> allEnquiries.filter { it.status.equals("active", ignoreCase = true) }
            "close" -> allEnquiries.filter { it.status.equals("close", ignoreCase = true) || it.status.equals("closed", ignoreCase = true) }
            else -> allEnquiries
        }
    }
    val followUpGroups = remember(filteredEnquiries, filterStatus) {
        if (filterStatus == "follow_up") {
            filteredEnquiries
                .groupBy { it.followUpDateMs }
                .toList()
                .sortedBy { (date, _) -> date ?: Long.MAX_VALUE }
        } else emptyList()
    }

    Scaffold(
        containerColor = BgColor, snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Enquiries", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = Color.Transparent, contentColor = Color.White, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
            ) { Icon(Icons.Default.Add, "Add", tint = Color.White) }
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cyan, strokeWidth = 3.dp) }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryChip("Today's contacts", "$todayFollowUpCount", AccentAmber, Modifier.weight(1f))
                        SummaryChip("All follow-ups", "$followUpCount", Cyan, Modifier.weight(1f))
                    }
                    if (overdueFollowUpCount > 0) {
                        Spacer(Modifier.height(7.dp))
                        Text("$overdueFollowUpCount follow-up${if (overdueFollowUpCount == 1) " is" else "s are"} overdue", color = AccentRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("all" to "All", "active" to "Active", "follow_up" to "Follow Up", "close" to "Closed").forEach { (key, label) ->
                            val isSel = filterStatus == key
                            FilterChip(selected = isSel, onClick = { viewModel.setFilter(key) },
                                label = { Text(label, fontSize = 12.sp, color = if (isSel) Color.White else TextMuted) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ElectricBlue.copy(alpha = 0.25f), containerColor = CardBg),
                                border = FilterChipDefaults.filterChipBorder(borderColor = BorderSub, selectedBorderColor = Cyan, enabled = true, selected = isSel))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${filteredEnquiries.size} enquiry${if (filteredEnquiries.size != 1) "ies" else ""}", color = TextMuted, fontSize = 12.sp)
                }

                if (filteredEnquiries.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.ContactPhone, null, tint = TextMuted.copy(alpha = 0.4f), modifier = Modifier.size(56.dp))
                                Spacer(Modifier.height(12.dp))
                                Text("No enquiries found.", color = TextMuted, fontSize = 15.sp)
                            }
                        }
                    }
                } else {
                    if (filterStatus == "follow_up") {
                        followUpGroups.forEach { (date, enquiries) ->
                            item(key = "follow-up-date-${date ?: "unscheduled"}") {
                                FollowUpDateHeader(dateMs = date, count = enquiries.size, dateFormat = dateFormat)
                            }
                            items(enquiries, key = { it.id }) { enquiry ->
                                EnquiryCard(enquiry = enquiry, dateFormat = dateFormat, onClick = { selectedEnquiry = enquiry; showDetailDialog = true },
                                    onCall = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${enquiry.phone}"))) },
                                    onSms = { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${enquiry.phone}")).apply { putExtra("sms_body", "Hello ${enquiry.name}, ") }) },
                                    onWhatsApp = {
                                        val enc = java.net.URLEncoder.encode("Hello ${enquiry.name}, ", "UTF-8")
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${enquiry.phone.replace("+","").replace(" ","")}?text=$enc")))
                                    })
                            }
                        }
                    } else {
                        items(filteredEnquiries, key = { it.id }) { enquiry ->
                            EnquiryCard(enquiry = enquiry, dateFormat = dateFormat, onClick = { selectedEnquiry = enquiry; showDetailDialog = true },
                                onCall = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${enquiry.phone}"))) },
                                onSms = { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${enquiry.phone}")).apply { putExtra("sms_body", "Hello ${enquiry.name}, ") }) },
                                onWhatsApp = {
                                    val enc = java.net.URLEncoder.encode("Hello ${enquiry.name}, ", "UTF-8")
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${enquiry.phone.replace("+","").replace(" ","")}?text=$enc")))
                                })
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    // Add dialog (unchanged)
    if (showAddDialog) {
        Dialog(onDismissRequest = { if (!isSavingEnquiry) showAddDialog = false }) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = CardBg), border = BorderStroke(1.dp, BorderSub)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("New Enquiry", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name *") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors(), shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = newPhone, onValueChange = { newPhone = it }, label = { Text("Phone *") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), colors = fieldColors(), shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = newSubject, onValueChange = { newSubject = it }, label = { Text("Subject *") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors(), shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = newAddress, onValueChange = { newAddress = it }, label = { Text("Address (optional)") }, maxLines = 2, modifier = Modifier.fillMaxWidth(), colors = fieldColors(), shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { showAddDialog = false }, enabled = !isSavingEnquiry, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderSub)) { Text("Cancel", color = TextMuted) }
                        Button(onClick = {
                            if (isSavingEnquiry) return@Button
                            isSavingEnquiry = true
                            viewModel.addEnquiry(newName, newPhone, newAddress, newSubject, System.currentTimeMillis(),
                                enquiryId = pendingEnquiryId,
                                onSuccess = { isSavingEnquiry = false; showAddDialog = false; newName = ""; newPhone = ""; newSubject = ""; newAddress = ""; scope.launch { snackbarHostState.showSnackbar("Enquiry saved.") } },
                                onError = { isSavingEnquiry = false; scope.launch { snackbarHostState.showSnackbar(it) } })
                        }, enabled = !isSavingEnquiry, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)) { Text(if (isSavingEnquiry) "Saving..." else "Save", color = Color.White) }
                    }
                }
            }
        }
    }

    // Detail dialog — status, note, contacts, delete
    if (showDetailDialog && selectedEnquiry != null) {
        val e = selectedEnquiry!!
        val statusLabel = when { e.status.equals("follow_up", ignoreCase = true) || e.status.equals("follow up", ignoreCase = true) -> "Follow Up"; e.status.equals("active", ignoreCase = true) -> "Active"; else -> "Closed" }
        val statusColor = when { e.status.equals("follow_up", ignoreCase = true) || e.status.equals("follow up", ignoreCase = true) -> AccentAmber; e.status.equals("active", ignoreCase = true) -> Cyan; else -> TextMuted }
        var editNote by remember { mutableStateOf(e.note ?: "") }
        var showFollowUpDatePicker by remember(e.id) { mutableStateOf(false) }
        val followUpDatePickerState = rememberDatePickerState(
            initialSelectedDateMillis = e.followUpDateMs ?: System.currentTimeMillis()
        )

        Dialog(onDismissRequest = { showDetailDialog = false }) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = CardBg), border = BorderStroke(1.dp, BorderSub)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(ElectricBlue.copy(0.3f), Cyan.copy(0.15f)))), contentAlignment = Alignment.Center) { Text(e.name.firstOrNull()?.uppercase() ?: "?", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${e.phone}  ·  ${e.subjectName}", color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("Date: ${dateFormat.format(Date(e.enquiryDateMs))}", color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Status: ", color = TextMuted, fontSize = 13.sp)
                        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(statusColor.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 3.dp)) { Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }

                    // Contact row
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ContactButton(Icons.Filled.Call, "Call", AccentGreen, Modifier.weight(1f)) { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${e.phone}"))) }
                        ContactButton(Icons.Filled.Sms, "SMS", ElectricBlue, Modifier.weight(1f)) { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${e.phone}")).apply { putExtra("sms_body", "Hello ${e.name}, ") }) }
                        ContactButton(Icons.Filled.Whatsapp, "WA", WAGreen, Modifier.weight(1f)) {
                            val enc = java.net.URLEncoder.encode("Hello ${e.name}, ", "UTF-8")
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${e.phone.replace("+","").replace(" ","")}?text=$enc")))
                        }
                    }

                    // Note
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = BorderSub)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = editNote, onValueChange = { editNote = it }, label = { Text("Follow-up Note") }, maxLines = 3, modifier = Modifier.fillMaxWidth(), colors = fieldColors(), shape = RoundedCornerShape(10.dp))
                    TextButton(onClick = { viewModel.updateNote(e, editNote); scope.launch { snackbarHostState.showSnackbar("Note saved") } }, modifier = Modifier.align(Alignment.End)) { Text("Save Note", color = Cyan, fontSize = 12.sp) }

                    // Status change
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = BorderSub)
                    Spacer(Modifier.height(10.dp))
                    Text("Change Status", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusBtn("Active", Icons.Filled.FiberManualRecord, Cyan, Modifier.weight(1f)) { viewModel.updateStatus(e, "active"); showDetailDialog = false }
                        StatusBtn("Follow Up", Icons.Filled.Refresh, AccentAmber, Modifier.weight(1f)) { viewModel.updateStatus(e, "follow_up"); showDetailDialog = false }
                        StatusBtn("Closed", Icons.Filled.CheckCircle, AccentGreen, Modifier.weight(1f)) { viewModel.updateStatus(e, "close"); showDetailDialog = false }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showFollowUpDatePicker = true },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.45f)),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Filled.Event, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (e.followUpDateMs == null) "Schedule follow-up date" else "Follow-up: ${dateFormat.format(Date(e.followUpDateMs))}",
                            color = AccentAmber,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { showDetailDialog = false; selectedEnquiry = e; showDeleteDialog = true }) { Text("Delete", color = AccentRed, fontWeight = FontWeight.SemiBold) }
                        TextButton(onClick = { showDetailDialog = false }) { Text("Close", color = TextMuted) }
                    }
                }
            }
        }

        if (showFollowUpDatePicker) {
            DatePickerDialog(
                onDismissRequest = { showFollowUpDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        followUpDatePickerState.selectedDateMillis?.let { selectedDate ->
                            viewModel.scheduleFollowUp(
                                enquiry = e,
                                dateMs = selectedDate,
                                onError = { scope.launch { snackbarHostState.showSnackbar(it) } }
                            )
                            scope.launch { snackbarHostState.showSnackbar("Follow-up scheduled") }
                        }
                        showFollowUpDatePicker = false
                        showDetailDialog = false
                    }) { Text("Schedule", color = AccentAmber, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { showFollowUpDatePicker = false }) { Text("Cancel", color = TextMuted) } },
                colors = DatePickerDefaults.colors(containerColor = CardBg)
            ) {
                DatePicker(state = followUpDatePickerState, colors = DatePickerDefaults.colors(
                    containerColor = CardBg,
                    titleContentColor = TextWhite,
                    headlineContentColor = AccentAmber,
                    weekdayContentColor = TextMuted,
                    selectedDayContainerColor = AccentAmber,
                    selectedDayContentColor = BgColor,
                    todayDateBorderColor = AccentAmber
                ))
            }
        }
    }

    // Delete confirmation
    if (showDeleteDialog && selectedEnquiry != null) {
        val e = selectedEnquiry!!
        AlertDialog(onDismissRequest = { showDeleteDialog = false }, containerColor = CardBg,
            title = { Text("Delete Enquiry", color = TextWhite, fontWeight = FontWeight.Bold) },
            text = { Text("Remove \"${e.name}\"? This cannot be undone.", color = TextMuted) },
            confirmButton = { TextButton(onClick = { viewModel.deleteEnquiry(e); showDeleteDialog = false; selectedEnquiry = null; scope.launch { snackbarHostState.showSnackbar("Enquiry deleted") } }) { Text("Delete", color = AccentRed, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = TextMuted) } })
    }
}

@Composable
private fun EnquiryCard(enquiry: EnquiryEntity, dateFormat: SimpleDateFormat, onClick: () -> Unit, onCall: () -> Unit, onSms: () -> Unit, onWhatsApp: () -> Unit) {
    val isFollowUp = isFollowUp(enquiry.status)
    val statusColor = when { isFollowUp -> AccentAmber; enquiry.status.equals("active", ignoreCase = true) -> Cyan; else -> TextMuted }
    val statusLabel = when { isFollowUp -> "Follow Up"; enquiry.status.equals("active", ignoreCase = true) -> "Active"; else -> "Closed" }

    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBgAlt), border = BorderStroke(1.dp, BorderSub)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(ElectricBlue.copy(0.3f), Cyan.copy(0.15f)))), contentAlignment = Alignment.Center) { Text(enquiry.name.firstOrNull()?.uppercase() ?: "?", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(enquiry.name, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${enquiry.phone}  ·  ${enquiry.subjectName}", color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(dateFormat.format(Date(enquiry.enquiryDateMs)), color = TextMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(statusColor.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp)) { Text(statusLabel, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
            enquiry.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(6.dp)); HorizontalDivider(color = BorderSub); Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Notes, null, tint = AccentAmber.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(note, color = TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (isFollowUp) {
                Spacer(Modifier.height(8.dp))
                val followUpLabel = enquiry.followUpDateMs?.let { followUpDateLabel(it, dateFormat) } ?: "Set follow-up date"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentAmber.copy(alpha = 0.10f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Event, null, tint = AccentAmber, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(followUpLabel, color = AccentAmber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionChip(Icons.Filled.Call, "Call", AccentGreen, Modifier.weight(1f), onCall)
                ActionChip(Icons.Filled.Sms, "SMS", ElectricBlue, Modifier.weight(1f), onSms)
                ActionChip(Icons.Filled.Whatsapp, "WA", WAGreen, Modifier.weight(1f), onWhatsApp)
            }
        }
    }
}

@Composable
private fun FollowUpDateHeader(dateMs: Long?, count: Int, dateFormat: SimpleDateFormat) {
    val label = dateMs?.let { followUpDateLabel(it, dateFormat) } ?: "No date set"
    val color = if (dateMs == null) TextMuted else AccentAmber
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, bottom = 1.dp)) {
        Icon(Icons.Filled.Event, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text("$count", color = TextMuted, fontSize = 12.sp)
    }
}

private fun isFollowUp(status: String): Boolean =
    status.equals("follow_up", ignoreCase = true) || status.equals("follow up", ignoreCase = true)

private fun followUpDateLabel(dateMs: Long, dateFormat: SimpleDateFormat): String {
    val today = startOfDay(System.currentTimeMillis())
    val tomorrow = Calendar.getInstance().apply {
        timeInMillis = today
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    return when {
        dateMs in today until tomorrow -> "Today · follow-up"
        dateMs < today -> "Overdue · ${dateFormat.format(Date(dateMs))}"
        else -> "Follow-up · ${dateFormat.format(Date(dateMs))}"
    }
}

private fun startOfDay(timeMs: Long): Long = Calendar.getInstance().apply {
    timeInMillis = timeMs
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun ActionChip(icon: ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier = modifier.height(30.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.1f)).border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(8.dp)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)); Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ContactButton(icon: ImageVector, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier = modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(10.dp)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SummaryChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = CardBgAlt), border = BorderStroke(1.dp, BorderSub)) {
        Column(modifier = Modifier.padding(12.dp)) { Text(label, color = TextMuted, fontSize = 11.sp); Spacer(Modifier.height(2.dp)); Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun StatusBtn(label: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(38.dp), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.4f)), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
    focusedBorderColor = ElectricBlue, unfocusedBorderColor = BorderSub,
    cursorColor = Cyan, focusedLabelColor = Cyan, unfocusedLabelColor = TextMuted
)
