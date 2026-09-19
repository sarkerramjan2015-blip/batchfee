package com.batchfee.edu.ui.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.ReminderTemplateEntity

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

private data class ReminderTemplateSpec(
    val type: String,
    val title: String,
    val description: String,
    val usedAt: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accent: Color,
    val placeholders: List<String>,
)

private val connectedReminderTemplates = listOf(
    ReminderTemplateSpec(
        "AttendanceAbsent", "Attendance absent", "Send when a student is marked absent",
        "Attendance → send message", Icons.Filled.EventBusy, ReRed,
        listOf("{guardianName}", "{studentName}", "{studentCode}", "{batchName}", "{date}", "{instituteName}", "{instituteContact}")
    ),
    ReminderTemplateSpec(
        "AttendanceUpdate", "Attendance update", "Send for present, late, leave or other attendance status",
        "Attendance → send message", Icons.Filled.FactCheck, ReBlue,
        listOf("{guardianName}", "{studentName}", "{studentCode}", "{attendanceStatus}", "{batchName}", "{date}", "{instituteName}", "{instituteContact}")
    ),
    ReminderTemplateSpec(
        "DueFee", "Due fee reminder", "Send for unpaid or overdue fees",
        "Fees & batch due list", Icons.Filled.Payments, ReCyan,
        listOf("{guardianName}", "{studentName}", "{amount}", "{period}", "{date}", "{instituteName}", "{instituteContact}")
    ),
    ReminderTemplateSpec(
        "PaymentConfirmation", "Payment receipt", "Send after a fee payment is collected",
        "Fee collection & payment history", Icons.Filled.ReceiptLong, ReGreen,
        listOf("{guardianName}", "{studentName}", "{amount}", "{dueAmount}", "{period}", "{receiptNumber}", "{paymentMethod}", "{instituteName}", "{instituteContact}")
    ),
    ReminderTemplateSpec(
        "Birthday", "Birthday greeting", "Send on a student's birthday",
        "Birthdays", Icons.Filled.Cake, Color(0xFFEC4899),
        listOf("{guardianName}", "{studentName}", "{instituteName}", "{instituteContact}")
    ),
    ReminderTemplateSpec(
        "ResultPublished", "Result published", "Send with exam marks, grade and position",
        "Exam results", Icons.Filled.Assessment, ReBlue,
        listOf("{guardianName}", "{studentName}", "{examName}", "{marks}", "{grade}", "{position}", "{instituteName}", "{instituteContact}")
    ),
    ReminderTemplateSpec(
        "MeritListPublished", "Merit list published", "Send the exam merit list to guardians",
        "Exam results → share merit list", Icons.Filled.Leaderboard, ReBlue,
        listOf("{examName}", "{meritList}", "{instituteName}", "{instituteContact}")
    ),
    ReminderTemplateSpec(
        "EnquiryFollowUp", "Enquiry follow-up", "Send to a prospective student or guardian",
        "Enquiries", Icons.Filled.SupportAgent, ReGreen,
        listOf("{guardianName}", "{studentName}", "{instituteName}", "{instituteContact}")
    ),
    ReminderTemplateSpec(
        "WelcomeMessage", "Admission welcome", "Send after a student is admitted",
        "Student admission & profile", Icons.Filled.WavingHand, ReGreen,
        listOf("{guardianName}", "{studentName}", "{studentCode}", "{className}", "{instituteName}", "{instituteContact}")
    ),
    ReminderTemplateSpec(
        "StaffCredentials", "Staff login details", "Share a new staff member's app login details",
        "Staff → share credentials", Icons.Filled.Badge, ReGreen,
        listOf("{staffName}", "{staffCode}", "{password}", "{staffRole}", "{appLink}", "{instituteName}")
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderTemplatesScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: ReminderTemplateViewModel = viewModel(factory = ReminderTemplateViewModelFactory(db))
    val templates by viewModel.templates.collectAsState()
    var editingSpec by remember { mutableStateOf<ReminderTemplateSpec?>(null) }

    Scaffold(
        containerColor = ReBg,
        topBar = {
            TopAppBar(
                title = { Text("Reminder Templates", color = ReWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ReMuted) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ReBg)
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(ReBg)
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ReCard), border = BorderStroke(1.dp, ReStroke),
            ) {
                Column(Modifier.padding(15.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(ReCyan.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.AutoAwesome, null, tint = ReCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column {
                            Text("SMS templates for every fixed workflow", color = ReWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Customize each automatic or transactional SMS. One-off manual SMS stays fully editable while sending.", color = ReMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("${connectedReminderTemplates.size} SMS templates", color = ReMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            connectedReminderTemplates.forEach { spec ->
                val existing = templates.firstOrNull { it.type == spec.type }
                ReminderTemplateCard(spec, existing, onClick = { editingSpec = spec })
                Spacer(Modifier.height(9.dp))
            }
            Spacer(Modifier.height(72.dp))
        }
    }

    editingSpec?.let { spec ->
        TemplateEditorDialog(
            spec = spec,
            existing = templates.firstOrNull { it.type == spec.type },
            onDismiss = { editingSpec = null },
            onSave = { title, message ->
                viewModel.upsertTemplate(title, spec.type, message)
                editingSpec = null
            }
        )
    }
}

@Composable
private fun ReminderTemplateCard(
    spec: ReminderTemplateSpec,
    existing: ReminderTemplateEntity?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = ReCard), border = BorderStroke(1.dp, ReStroke),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(spec.accent.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Icon(spec.icon, null, tint = spec.accent, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.title, color = ReWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.width(7.dp))
                    Text(if (existing == null) "Default" else "Custom", color = if (existing == null) ReMuted else ReGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(3.dp))
                Text(spec.description, color = ReMuted, fontSize = 11.sp, maxLines = 1)
                Text("Used in: ${spec.usedAt}", color = spec.accent, fontSize = 10.sp)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Edit ${spec.title}", tint = ReDim, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TemplateEditorDialog(
    spec: ReminderTemplateSpec,
    existing: ReminderTemplateEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember(existing, spec) { mutableStateOf(existing?.title ?: spec.title) }
    var message by remember(existing, spec) { mutableStateOf(existing?.messageTemplate ?: (com.example.domain.MessageTemplateStore.defaultFor(spec.type) ?: "")) }
    var showPlaceholders by remember { mutableStateOf(false) }
    val preview = remember(message, spec) {
        com.example.domain.MessageTemplateStore.apply(message, templatePreviewValues(spec.type))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ReCard,
        shape = RoundedCornerShape(16.dp),
        title = { Text(spec.title, color = ReWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("This message is used in: ${spec.usedAt}", color = spec.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Template Name", color = ReMuted) }, placeholder = { Text("e.g. Attendance Alert", color = ReDim) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true, colors = reFieldColors())
                OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Message Template", color = ReMuted) }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), shape = RoundedCornerShape(12.dp), minLines = 4, colors = reFieldColors())
                TextButton(onClick = { showPlaceholders = !showPlaceholders }) {
                    Icon(Icons.Filled.Code, null, Modifier.size(14.dp), tint = ReCyan)
                    Spacer(Modifier.width(4.dp))
                    Text(if (showPlaceholders) "Hide placeholders" else "Show placeholders", color = ReCyan, fontSize = 12.sp)
                }
                if (showPlaceholders) {
                    Text("Tap a field to add it to the message", color = ReMuted, fontSize = 11.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        spec.placeholders.forEach { ph ->
                            SuggestionChip(
                                onClick = { message = message.trimEnd() + if (message.isBlank()) ph else " $ph" },
                                label = { Text(ph, fontSize = 11.sp, color = ReCyan) },
                                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = ReBlue.copy(alpha = 0.1f))
                            )
                        }
                    }
                }
                Text("Preview", color = ReWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1424)),
                    border = BorderStroke(1.dp, ReStroke)
                ) {
                    Text(
                        preview.ifBlank { "Your message preview will appear here." },
                        modifier = Modifier.padding(12.dp),
                        color = ReMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, ReMuted.copy(alpha = 0.3f))) { Text("Cancel", color = ReMuted) }
                Button(
                    onClick = { if (title.isNotBlank() && message.isNotBlank()) onSave(title.trim(), message.trim()) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ReBlue),
                    enabled = title.isNotBlank() && message.isNotBlank()
                ) { Text("Save", fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = if (existing != null) {
            {
                TextButton(onClick = {
                    title = spec.title
                    message = com.example.domain.MessageTemplateStore.defaultFor(spec.type).orEmpty()
                }) { Text("Restore default text", color = ReMuted, fontSize = 12.sp) }
            }
        } else null
    )
}

private fun templatePreviewValues(type: String): Map<String, String> {
    val common = mutableMapOf(
        "guardianName" to "Guardian",
        "studentName" to if (type == "EnquiryFollowUp") "Nusrat Jahan" else "Rahim Ahmed",
        "instituteName" to "ABC Coaching",
        "instituteContact" to "+8801712345678",
    )
    common += mapOf(
        "studentCode" to "ST-1025",
        "batchName" to "HSC ICT",
        "className" to "HSC ICT",
        "date" to "11 Sep 2026",
        "amount" to "1500",
        "period" to "Sep 2026",
        "grade" to "A+",
        "position" to "2",
        "marks" to "87 / 100",
        "examName" to "Monthly Exam",
        "meritList" to "1. Rahim Ahmed - 87 (A+)\n2. Nusrat Jahan - 84 (A)",
        "attendanceStatus" to "arrived late",
        "receiptNumber" to "REC-00000125",
        "dueAmount" to "0",
        "paymentMethod" to "CASH",
        "staffName" to "Sadia Rahman",
        "staffCode" to "STF-1025",
        "password" to "••••••••",
        "staffRole" to "Teacher",
        "appLink" to "batchfee.app",
        "message" to "Tomorrow's class will start at 4:00 PM.",
    )
    return common
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun reFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color(0xFF111827), unfocusedContainerColor = Color(0xFF111827),
    focusedBorderColor = ReBlue, unfocusedBorderColor = ReStroke,
    focusedTextColor = ReWhite, unfocusedTextColor = ReWhite,
    cursorColor = ReBlue, focusedLabelColor = ReBlue, unfocusedLabelColor = ReMuted
)
