package com.batchfee.edu.ui.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.ReminderTemplateSyncHelper
import com.batchfee.edu.data.models.ReminderTemplateEntity
import java.util.UUID

private val ReBg     = Color(0xFF07111F)
private val ReCard   = Color(0xFF0F172A)
private val ReStroke = Color(0xFF1E293B)
private val ReCyan   = Color(0xFF22D3EE)
private val ReGreen  = Color(0xFF22C55E)
private val ReRed    = Color(0xFFEF4444)
private val ReBlue   = Color(0xFF3B82F6)
private val ReWhite  = Color(0xFFF8FAFC)
private val ReMuted  = Color(0xFF94A3B8)
private val ReDim    = Color(0xFF64748B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderTemplatesScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: ReminderTemplateViewModel = viewModel(factory = ReminderTemplateViewModelFactory(db))
    val templates by viewModel.templates.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<ReminderTemplateEntity?>(null) }

    Scaffold(
        containerColor = ReBg,
        topBar = {
            TopAppBar(
                title = { Text("Reminder Templates", color = ReWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ReMuted) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ReBg)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = ReBlue, contentColor = Color.White, shape = CircleShape
            ) { Icon(Icons.Filled.Add, "Add Template") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(ReBg)) {
            if (templates.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Notifications, null, Modifier.size(48.dp), tint = ReDim)
                        Spacer(Modifier.height(12.dp))
                        Text("No reminder templates yet", color = ReMuted, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap + to create your first template", color = ReDim, fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(templates, key = { it.id }) { t ->
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = ReCard), border = BorderStroke(1.dp, ReStroke)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(ReBlue.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Notifications, null, tint = ReBlue, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(t.title, Modifier.weight(1f), color = ReWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    if (t.isDefault) {
                                        Surface(shape = RoundedCornerShape(6.dp), color = ReGreen.copy(alpha = 0.15f)) {
                                            Text("Default", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = ReGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(t.type.replaceFirstChar { it.uppercase() }, color = ReCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Text(t.messageTemplate, color = ReMuted, fontSize = 13.sp, maxLines = 3, lineHeight = 18.sp)
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { editingTemplate = t }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.Edit, null, tint = ReCyan, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = {
                                        viewModel.deleteTemplate(t)
                                    }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.Delete, null, tint = ReRed, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    // Add / Edit dialog
    if (showAddDialog || editingTemplate != null) {
        TemplateEditorDialog(
            existing = editingTemplate,
            onDismiss = { showAddDialog = false; editingTemplate = null },
            onSave = { title, type, message ->
                viewModel.upsertTemplate(title, type, message)
                showAddDialog = false; editingTemplate = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TemplateEditorDialog(
    existing: ReminderTemplateEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var title by remember(existing) { mutableStateOf(existing?.title ?: "") }
    var type by remember(existing) { mutableStateOf(existing?.type ?: "AttendanceAbsent") }
    var message by remember(existing) { mutableStateOf(existing?.messageTemplate ?: "") }
    var showPlaceholders by remember { mutableStateOf(false) }

    val types = listOf(
        "AttendanceAbsent" to "Attendance Absent",
        "DueFee" to "Due Fee Reminder",
        "Birthday" to "Birthday Greeting",
        "PaymentConfirmation" to "Payment Confirmation",
        "EnquiryFollowUp" to "Enquiry Follow-Up",
        "ResultPublished" to "Result Published",
        "WelcomeMessage" to "Welcome Message",
        "Custom" to "Custom"
    )
    val placeholders = listOf("{guardianName}", "{studentName}", "{studentCode}", "{batchName}", "{date}", "{instituteName}", "{amount}", "{period}", "{grade}", "{rank}")
    val typeDefaults = mapOf(
        "AttendanceAbsent" to (listOf("Attendance Alert") to "Dear {guardianName},\n\n{studentName} ({batchName}) was absent today ({date}).\n\n\u2014 {instituteName}"),
        "DueFee" to (listOf("Due Fee Reminder") to "Dear {guardianName},\n\n{studentName}'s fee of {amount} for {period} is due. Please pay by {date}.\n\n\u2014 {instituteName}"),
        "Birthday" to (listOf("Happy Birthday!") to "Happy Birthday, {studentName}!\n\nWishing you a wonderful day from all of us at {instituteName}."),
        "PaymentConfirmation" to (listOf("Payment Received") to "Dear {guardianName},\n\nPayment of {amount} for {studentName} has been received for {period}. Thank you!\n\n\u2014 {instituteName}"),
        "EnquiryFollowUp" to (listOf("Follow-Up") to "Dear {guardianName},\n\nFollowing up on your enquiry for {studentName}. Feel free to contact us at {instituteName}.\n\nWe'd love to have you on board!"),
        "ResultPublished" to (listOf("Results Out!") to "Dear {guardianName},\n\n{studentName}'s result for the recent exam is out! Grade: {grade}, Rank: {rank}.\n\n\u2014 {instituteName}"),
        "WelcomeMessage" to (listOf("Welcome!") to "Welcome to {instituteName}, {studentName}!\n\nWe're excited to have you in {batchName}. Your student code is {studentCode}.\n\nLet's make this a great journey!"),
        "Custom" to (listOf("Custom Reminder") to "")
    )

    fun pickDefault(key: String) { typeDefaults[key]?.let { title = it.first.first(); message = it.second } }
    if (existing == null) LaunchedEffect(Unit) { pickDefault(type) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ReCard,
        shape = RoundedCornerShape(16.dp),
        title = { Text(if (existing != null) "Edit Template" else "New Template", color = ReWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Template Name", color = ReMuted) }, placeholder = { Text("e.g. Attendance Alert", color = ReDim) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true, colors = reFieldColors())
                var typeExp by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = typeExp, onExpandedChange = { typeExp = it }) {
                    OutlinedTextField(value = types.first { it.first == type }.second, onValueChange = {}, readOnly = true, label = { Text("Type", color = ReMuted) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExp) }, modifier = Modifier.fillMaxWidth().menuAnchor(), shape = RoundedCornerShape(12.dp), colors = reFieldColors())
                    ExposedDropdownMenu(expanded = typeExp, onDismissRequest = { typeExp = false }, containerColor = ReCard) {
                        types.forEach { (key, label) -> DropdownMenuItem(text = { Text(label, color = ReWhite) }, onClick = { type = key; pickDefault(key); typeExp = false }) }
                    }
                }
                OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Message Template", color = ReMuted) }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), shape = RoundedCornerShape(12.dp), minLines = 4, colors = reFieldColors())
                TextButton(onClick = { showPlaceholders = !showPlaceholders }) {
                    Icon(Icons.Filled.Code, null, Modifier.size(14.dp), tint = ReCyan)
                    Spacer(Modifier.width(4.dp))
                    Text(if (showPlaceholders) "Hide placeholders" else "Show placeholders", color = ReCyan, fontSize = 12.sp)
                }
                if (showPlaceholders) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        placeholders.forEach { ph ->
                            SuggestionChip(
                                onClick = { message += " $ph" },
                                label = { Text(ph, fontSize = 11.sp, color = ReCyan) },
                                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = ReBlue.copy(alpha = 0.1f))
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, ReMuted.copy(alpha = 0.3f))) { Text("Cancel", color = ReMuted) }
                Button(
                    onClick = {
                        if (title.isNotBlank() && message.isNotBlank()) onSave(title.trim(), type, message.trim())
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ReBlue),
                    enabled = title.isNotBlank() && message.isNotBlank()
                ) { Text("Save", fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = null
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun reFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color(0xFF111827), unfocusedContainerColor = Color(0xFF111827),
    focusedBorderColor = ReBlue, unfocusedBorderColor = ReStroke,
    focusedTextColor = ReWhite, unfocusedTextColor = ReWhite,
    cursorColor = ReBlue, focusedLabelColor = ReBlue, unfocusedLabelColor = ReMuted
)
