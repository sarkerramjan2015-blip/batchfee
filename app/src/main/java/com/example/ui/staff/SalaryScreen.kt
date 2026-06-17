package com.example.ui.staff

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.models.StaffEntity
import com.example.data.models.SalaryEntity
import com.example.domain.SessionManager
import java.io.File
import java.text.SimpleDateFormat
import kotlinx.coroutines.runBlocking
import java.util.*

// ── Colors ──────────────────────────────────────────────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val WAGreen       = Color(0xFF25D366)
private val AccentRed     = Color(0xFFEF4444)
private val AccentAmber   = Color(0xFFF59E0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalaryDashboardScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onGenerate: () -> Unit,
    onNavigateToPricing: () -> Unit
) {
    val viewModel: SalaryViewModel = viewModel(factory = SalaryViewModelFactory(db))
    val salaries by viewModel.salaries.collectAsState()
    val staffList by viewModel.activeStaff.collectAsState()
    val isAdmin = remember { SessionManager.isAdmin() }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Salary Management", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
                    onClick = onGenerate,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Generate", tint = Color.White)
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
            Text("${salaries.size} salaries", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            if (salaries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No salaries generated yet.", color = TextMuted, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(salaries, key = { it.id }) { s ->
                        val isPaid = s.status == "paid"
                        val statusColor = if (isPaid) WAGreen else AccentAmber
                        val staff = staffList.firstOrNull { it.id == s.staffId }
                        Card(
                            modifier = Modifier.fillMaxWidth()
                                .shadow(3.dp, RoundedCornerShape(12.dp), spotColor = statusColor.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            border = BorderStroke(1.dp, BorderSub)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(staff?.fullName ?: "Staff", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Text(s.salaryMonth, color = TextMuted, fontSize = 12.sp)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(statusColor.copy(alpha = 0.15f))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(s.status.uppercase(), color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("Net: BDT ${s.netSalary}", color = TextMuted, fontSize = 14.sp)
                                if (!isPaid && isAdmin) {
                                    Spacer(Modifier.height(8.dp))
                                    var showReceipt by remember { mutableStateOf(false) }
                                    if (showReceipt) {
                                        SalaryReceiptDialog(
                                            salary = s,
                                            staffName = staff?.fullName ?: "Staff",
                                            instName = staff?.instituteId?.let { id ->
                                                runBlocking { db.instituteDao().getInstitute(id)?.name }
                                            } ?: "BatchFee Institute",
                                            instCode = staff?.instituteId?.let { id ->
                                                runBlocking { db.instituteDao().getInstitute(id)?.instituteCode }
                                            } ?: "N/A",
                                            instPhone = staff?.instituteId?.let { id ->
                                                runBlocking { db.instituteDao().getInstitute(id)?.phone }
                                            } ?: "",
                                            onDismiss = { showReceipt = false }
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                                            .clickable {
                                                viewModel.markAsPaid(s.id, "cash")
                                                showReceipt = true
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Mark Paid", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateSalaryScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: SalaryViewModel = viewModel(factory = SalaryViewModelFactory(db))
    val activeStaff by viewModel.activeStaff.collectAsState()
    val isAdmin = remember { SessionManager.isAdmin() }

    var selectedStaffId by remember { mutableStateOf<String?>(null) }
    var month by remember { mutableStateOf(SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())) }
    var basic by remember { mutableStateOf("0") }
    var bonus by remember { mutableStateOf("0") }
    var deduction by remember { mutableStateOf("0") }
    var advance by remember { mutableStateOf("0") }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Generate Salary", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
            if (!isAdmin) {
                Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("Only admins can generate salaries.", color = TextMuted, fontSize = 14.sp)
                }
                return@Scaffold
            }

            SectionLabel("Select Staff Member")
            Spacer(Modifier.height(4.dp))
            LazyRow {
                items(activeStaff) { s ->
                    FilterChip(
                        selected = selectedStaffId == s.id,
                        onClick = { selectedStaffId = s.id; basic = s.monthlySalary.toString() },
                        label = { Text(s.fullName, fontSize = 12.sp) },
                        modifier = Modifier.padding(end = 6.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricBlue.copy(alpha = 0.2f),
                            selectedLabelColor = Cyan
                        )
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            SectionLabel("Month")
            var monthExpanded by remember { mutableStateOf(false) }
            val monthOptions = remember {
                val cal = Calendar.getInstance()
                val list = mutableListOf<Pair<String, String>>() // label → value
                for (i in -6..6) {
                    val c = cal.clone() as Calendar
                    c.add(Calendar.MONTH, i)
                    val label = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(c.time)
                    val value = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(c.time)
                    list.add(label to value)
                }
                list
            }
            val selectedMonthLabel = monthOptions.firstOrNull { it.second == month }?.first
                ?: SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date())

            Box {
                OutlinedTextField(
                    value = selectedMonthLabel,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { monthExpanded = true }) {
                            Icon(Icons.Filled.ArrowDropDown, null, tint = TextMuted)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = darkFieldColors(),
                    shape = RoundedCornerShape(12.dp)
                )
                DropdownMenu(
                    expanded = monthExpanded,
                    onDismissRequest = { monthExpanded = false },
                    modifier = Modifier.background(CardBgAlt)
                ) {
                    monthOptions.forEach { (label, value) ->
                        DropdownMenuItem(
                            text = {
                                Text(label, color = TextWhite, fontSize = 13.sp)
                            },
                            onClick = {
                                month = value
                                monthExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            SectionLabel("Basic Salary")
            OutlinedTextField(
                value = basic, onValueChange = { basic = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            SectionLabel("Bonus")
            OutlinedTextField(
                value = bonus, onValueChange = { bonus = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            SectionLabel("Deduction")
            OutlinedTextField(
                value = deduction, onValueChange = { deduction = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            SectionLabel("Advance")
            OutlinedTextField(
                value = advance, onValueChange = { advance = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(14.dp))
            val net = (basic.toDoubleOrNull() ?: 0.0) + (bonus.toDoubleOrNull() ?: 0.0) - (deduction.toDoubleOrNull() ?: 0.0) - (advance.toDoubleOrNull() ?: 0.0)
            Text("Net Salary: BDT ${net.toLong()}", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(16.dp))
            val canGenerate = selectedStaffId != null && month.isNotBlank() && net >= 0
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (canGenerate) Modifier.background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                        else Modifier.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(14.dp))
                    ),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = {
                        if (selectedStaffId != null && month.isNotBlank() && net >= 0) {
                            viewModel.generateSalary(selectedStaffId!!, month,
                                basic.toDoubleOrNull() ?: 0.0, bonus.toDoubleOrNull() ?: 0.0,
                                deduction.toDoubleOrNull() ?: 0.0, advance.toDoubleOrNull() ?: 0.0,
                                onSuccess = onBack)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    enabled = canGenerate,
                    colors = ButtonDefaults.textButtonColors(contentColor = if (canGenerate) Color.White else TextMuted)
                ) {
                    Text("Generate Salary", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SalaryReceiptDialog(
    salary: com.example.data.models.SalaryEntity,
    staffName: String,
    instName: String,
    instCode: String,
    instPhone: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showPrintDialog by remember { mutableStateOf(true) }

    if (showPrintDialog) {
        AlertDialog(
            onDismissRequest = { showPrintDialog = false; onDismiss() },
            title = {
                Text("Salary Receipt Ready", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.padding(4.dp)) {
                    Text("$staffName", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("BDT ${salary.netSalary}", color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(salary.salaryMonth, color = TextMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("You can print the receipt or share it via WhatsApp.", color = TextMuted, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPrintDialog = false
                    try {
                        val file = generateSalaryReceiptPdf(context, salary, staffName, instName, instCode, instPhone)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (_: Exception) { }
                    onDismiss()
                }) {
                    Icon(Icons.Filled.Print, null, modifier = Modifier.size(18.dp), tint = ElectricBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("Print", color = ElectricBlue)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showPrintDialog = false
                        var handled = false
                        try {
                            val file = generateSalaryReceiptPdf(context, salary, staffName, instName, instCode, instPhone)
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val intent = Intent(Intent.ACTION_SEND)
                            intent.type = "application/pdf"
                            intent.putExtra(Intent.EXTRA_STREAM, uri)
                            intent.putExtra(Intent.EXTRA_TEXT, "Salary Receipt - $staffName - ${salary.salaryMonth}")
                            intent.setPackage("com.whatsapp")
                            if (intent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(intent)
                                handled = true
                            }
                        } catch (_: Exception) { }
                        if (!handled) Toast.makeText(context, "WhatsApp not installed. Use Print to share.", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }) {
                        Text("WhatsApp", color = Color(0xFF25D366))
                    }
                    TextButton(onClick = {
                        showPrintDialog = false
                        onDismiss()
                    }) {
                        Text("Close", color = TextMuted)
                    }
                }
            },
            containerColor = CardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

private fun generateSalaryReceiptPdf(context: Context, salary: com.example.data.models.SalaryEntity, staffName: String, instName: String, instCode: String, instPhone: String): File {
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(340, 544, 1).create())
    val canvas = page.canvas
    val white = AndroidColor.WHITE
    val darkBlue = AndroidColor.rgb(30, 58, 95)
    val blue = AndroidColor.rgb(37, 99, 235)
    val textDark = AndroidColor.rgb(30, 41, 59)
    val textMuted = AndroidColor.rgb(71, 85, 105)

    val fill = Paint().apply { style = Paint.Style.FILL }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = textDark }
    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; color = textDark; isFakeBoldText = true }
    val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; color = white; isFakeBoldText = true }

    // Header
    fill.color = darkBlue
    canvas.drawRect(0f, 0f, 340f, 130f, fill)
    canvas.drawText(instName.uppercase(), 20f, 50f, whiteText)
    whiteText.textSize = 10f
    canvas.drawText("STAFF SALARY RECEIPT", 20f, 70f, whiteText)
    whiteText.textSize = 9f
    canvas.drawText("$instCode  •  $instPhone", 20f, 90f, whiteText)
    fill.color = white

    // Staff info section
    var y = 155f
    bold.textSize = 15f
    canvas.drawText(staffName, 20f, y, bold)
    y += 20
    text.textSize = 12f
    canvas.drawText(salary.salaryMonth, 20f, y, text)
    y += 30

    // Divider
    fill.color = android.graphics.Color.rgb(226, 232, 240)
    canvas.drawRect(20f, y, 320f, y + 1f, fill)
    y += 20

    // Salary breakdown
    val rows = listOf(
        "Basic Salary" to "BDT ${salary.basicSalary}",
        "Bonus" to "BDT ${salary.bonusAmount}",
        "Deduction" to "BDT ${salary.deductionAmount}",
        "Advance" to "BDT ${salary.advanceAmount}",
        "Net Salary" to "BDT ${salary.netSalary}"
    )
    rows.forEach { (label, value) ->
        val isNet = label == "Net Salary"
        if (isNet) {
            y += 5
            fill.color = android.graphics.Color.rgb(226, 232, 240)
            canvas.drawRect(20f, y - 2, 320f, y + 17f, fill)
        }
        text.textSize = if (isNet) 13f else 11f
        val labelColor = if (isNet) darkBlue else textMuted
        text.color = labelColor
        canvas.drawText(label, 20f, y + 10f, text)
        val valColor = if (isNet) darkBlue else textDark
        text.color = valColor
        text.isFakeBoldText = isNet
        canvas.drawText(value, 300f, y + 10f, text)
        text.isFakeBoldText = false
        y += if (isNet) 26 else 20
    }

    // Footer
    y += 20
    text.textSize = 9f; text.color = textMuted
    canvas.drawText("Thank you  •  $instName", 20f, y, text)

    document.finishPage(page)
    val file = File(context.cacheDir, "salary_receipt_${salary.salaryMonth.replace(" ", "_")}.pdf")
    file.outputStream().use { document.writeTo(it) }
    document.close()
    return file
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
