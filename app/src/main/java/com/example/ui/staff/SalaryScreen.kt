package com.batchfee.edu.ui.staff

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.data.models.SalaryEntity
import com.batchfee.edu.data.models.TeachingSessionEntity
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.InstituteContactNumber
import com.batchfee.edu.ui.students.drawLogo
import com.batchfee.edu.ui.students.loadBitmap
import java.io.File
import java.text.SimpleDateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var receiptTarget by remember { mutableStateOf<SalaryEntity?>(null) }
    var receiptStaffName by remember { mutableStateOf("Staff") }
    var paymentTarget by remember { mutableStateOf<SalaryEntity?>(null) }
    var breakdownTarget by remember { mutableStateOf<SalaryEntity?>(null) }

    // Pre-fetch institute info once
    var instName by remember { mutableStateOf("BatchFee Institute") }
    var instCode by remember { mutableStateOf("N/A") }
    var instPhone by remember { mutableStateOf("") }
    var instAddress by remember { mutableStateOf("") }
    var instLogoUri by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val iid = SessionManager.currentInstituteId.value
        if (iid != null) {
            val inst = withContext(Dispatchers.IO) { db.instituteDao().getInstitute(iid) }
            inst?.let {
                instName = it.name.ifBlank { "BatchFee Institute" }
                instCode = it.instituteCode?.takeIf(String::isNotBlank) ?: "N/A"
                instPhone = InstituteContactNumber.primary(it.phone, it.whatsappNumber).orEmpty()
                instAddress = it.address.orEmpty()
                instLogoUri = it.profilePhotoUri
            }
        }
    }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            val payableSalaries = salaries.filter { it.cancelledAtMs == null }
            val totalNet = payableSalaries.sumOf { it.netSalary }
            val totalPaid = payableSalaries.sumOf { it.paidAmount.coerceIn(0.0, it.netSalary) }
            val totalDue = (totalNet - totalPaid).coerceAtLeast(0.0)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Salary overview", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("${payableSalaries.size} records", color = TextMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SalaryMetric("Salary", totalNet, Cyan)
                        SalaryMetric("Paid", totalPaid, WAGreen)
                        SalaryMetric("Due", totalDue, if (totalDue > 0) AccentAmber else WAGreen)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

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
                        val paidAmount = s.paidAmount.coerceIn(0.0, s.netSalary)
                        val dueAmount = (s.netSalary - paidAmount).coerceAtLeast(0.0)
                        val isPaid = dueAmount <= 0.009 && paidAmount > 0.0
                        val isPartial = paidAmount > 0.0 && !isPaid
                        val statusLabel = when {
                            isPaid -> "PAID"
                            isPartial -> "PARTIAL"
                            else -> "UNPAID"
                        }
                        val statusColor = when {
                            isPaid -> WAGreen
                            isPartial -> AccentAmber
                            else -> AccentRed
                        }
                        val staff = staffList.firstOrNull { it.id == s.staffId }
                        Card(
                            modifier = Modifier.fillMaxWidth()
                                .shadow(4.dp, RoundedCornerShape(16.dp), spotColor = statusColor.copy(alpha = 0.14f)),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            border = BorderStroke(1.dp, BorderSub)
                        ) {
                            Column(modifier = Modifier.padding(15.dp)) {
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
                                        Text(statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    SalaryMiniAmount("Salary", s.netSalary, TextMuted, Modifier.weight(1f))
                                    SalaryMiniAmount("Paid", paidAmount, WAGreen, Modifier.weight(1f))
                                    SalaryMiniAmount("Due", dueAmount, if (dueAmount > 0) AccentAmber else WAGreen, Modifier.weight(1f))
                                }
                                s.paymentDateMs?.let { paidAt ->
                                    Spacer(Modifier.height(9.dp))
                                    Text(
                                        "Last payment · ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(paidAt))} · ${s.paymentMethod?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Payment"}",
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                    )
                                }
                                if (s.calculationType == "per_class" || s.calculationType == "per_hour") {
                                    Spacer(Modifier.height(10.dp))
                                    val classCount = s.calculationSessionIds
                                        ?.split(",")?.filter { it.isNotBlank() }?.size ?: 0
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(CardBgAlt)
                                            .clickable {
                                                breakdownTarget = s
                                                viewModel.loadSalaryBreakdown(s.id)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.Schedule, null, tint = Cyan, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text("Class breakdown", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            Text(
                                                "${if (classCount > 0) classCount else "?"} classes • class pay BDT ${salaryAmount(s.basicSalary)}",
                                                color = TextMuted, fontSize = 11.sp
                                            )
                                        }
                                        Icon(Icons.Filled.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                        onClick = {
                                            receiptTarget = s
                                            receiptStaffName = staff?.fullName ?: "Staff"
                                        },
                                        modifier = Modifier.weight(1f).height(40.dp),
                                        shape = RoundedCornerShape(11.dp),
                                        border = BorderStroke(1.dp, Cyan.copy(alpha = 0.55f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                                    ) {
                                        Icon(Icons.Filled.ReceiptLong, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Receipt", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (isAdmin && dueAmount > 0.009) {
                                        Button(
                                            onClick = { paymentTarget = s },
                                            modifier = Modifier.weight(1f).height(40.dp),
                                            shape = RoundedCornerShape(11.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                                        ) {
                                            Icon(Icons.Filled.Payments, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (isPartial) "Pay Due" else "Record Payment", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (isAdmin && paidAmount <= 0.009) {
                                        IconButton(
                                            onClick = {
                                                viewModel.cancelSalary(
                                                    s.id,
                                                    onSuccess = { scope.launch { snackbarHostState.showSnackbar("Salary cancelled") } },
                                                    onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                                                )
                                            },
                                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(AccentRed.copy(alpha = 0.12f)),
                                        ) {
                                            Icon(Icons.Filled.Close, "Cancel unpaid salary", tint = AccentRed, modifier = Modifier.size(18.dp))
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

    receiptTarget?.let { salary ->
        SalaryReceiptDialog(
            salary = salary,
            staffName = receiptStaffName,
            instName = instName,
            instCode = instCode,
            instPhone = instPhone,
            instAddress = instAddress,
            instLogoUri = instLogoUri,
            onDismiss = { receiptTarget = null },
        )
    }
    paymentTarget?.let { salary ->
        SalaryPaymentDialog(
            salary = salary,
            onDismiss = { paymentTarget = null },
            onSubmit = { amount, method, note ->
                viewModel.recordPayment(
                    salaryId = salary.id,
                    amount = amount,
                    paymentMethod = method,
                    note = note,
                    onSuccess = { updated ->
                        paymentTarget = null
                        receiptTarget = updated
                        receiptStaffName = staffList.firstOrNull { it.id == updated.staffId }?.fullName ?: "Staff"
                        scope.launch { snackbarHostState.showSnackbar("Salary payment saved; Institute Expense updated") }
                    },
                    onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                )
            },
        )
    }
    breakdownTarget?.let { salary ->
        val breakdown by viewModel.salaryBreakdown.collectAsState()
        SalaryBreakdownDialog(
            salary = salary,
            breakdown = breakdown?.takeIf { it.salary.id == salary.id },
            onDismiss = {
                breakdownTarget = null
                viewModel.clearSalaryBreakdown()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateSalaryScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: SalaryViewModel = viewModel(factory = SalaryViewModelFactory(db))
    val activeStaff by viewModel.activeStaff.collectAsState()
    val teacherPayPreview by viewModel.teacherPayPreview.collectAsState()
    val isAdmin = remember { SessionManager.isAdmin() }
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedStaffId by remember { mutableStateOf<String?>(null) }
    var month by remember { mutableStateOf(SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())) }
    var basic by remember { mutableStateOf("0") }
    var bonus by remember { mutableStateOf("0") }
    var deduction by remember { mutableStateOf("0") }
    var advance by remember { mutableStateOf("0") }
    var isGenerating by remember { mutableStateOf(false) }
    val selectedStaff = activeStaff.firstOrNull { it.id == selectedStaffId }

    LaunchedEffect(selectedStaffId, month) {
        viewModel.loadTeacherPayPreview(selectedStaffId, month)
    }

    BackHandler(enabled = isGenerating) { }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Generate Salary", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isGenerating) {
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
                        onClick = {
                            selectedStaffId = s.id
                            basic = if (s.salaryType == "monthly") s.monthlySalary.toString() else "0"
                            bonus = "0"; deduction = "0"; advance = "0"
                        },
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
            if (selectedStaff?.salaryType == "per_class" || selectedStaff?.salaryType == "per_hour") {
                val rateLabel = if (selectedStaff?.salaryType == "per_class") "per-class" else "per-hour"
                SectionLabel("Completed Class Salary")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardBgAlt)
                        .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            "${teacherPayPreview.sessionCount} completed classes · $rateLabel rate",
                            color = TextMuted,
                            fontSize = 13.sp,
                        )
                        Text(
                            "BDT ${teacherPayPreview.amount.toLong()}",
                            color = Cyan,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "Calculated automatically from un-paid completed classes.",
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                if (teacherPayPreview.sessions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("Class-wise breakdown")
                    ClassBreakdownList(teacherPayPreview.sessions)
                }
            } else {
                SectionLabel("Basic Salary")
                OutlinedTextField(
                    value = basic, onValueChange = { basic = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = darkFieldColors(), shape = RoundedCornerShape(12.dp)
                )
            }
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
            val baseForPreview = if (selectedStaff?.salaryType == "per_class" || selectedStaff?.salaryType == "per_hour") {
                teacherPayPreview.amount
            } else basic.toDoubleOrNull() ?: 0.0
            val net = baseForPreview + (bonus.toDoubleOrNull() ?: 0.0) - (deduction.toDoubleOrNull() ?: 0.0) - (advance.toDoubleOrNull() ?: 0.0)
            Text("Net Salary: BDT ${net.toLong()}", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(16.dp))
            val scope = rememberCoroutineScope()
            val canGenerate = selectedStaffId != null && month.isNotBlank() && baseForPreview > 0 && net >= 0 && !isGenerating
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
                        if (!isGenerating && selectedStaffId != null && month.isNotBlank() && net >= 0) {
                            isGenerating = true
                            viewModel.generateSalary(selectedStaffId!!, month,
                                basic.toDoubleOrNull() ?: 0.0, bonus.toDoubleOrNull() ?: 0.0,
                                deduction.toDoubleOrNull() ?: 0.0, advance.toDoubleOrNull() ?: 0.0,
                                onSuccess = onBack,
                                onError = { msg ->
                                    isGenerating = false
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    enabled = canGenerate,
                    colors = ButtonDefaults.textButtonColors(contentColor = if (canGenerate) Color.White else TextMuted)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isGenerating) "Generating..." else "Generate Salary", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SalaryMetric(label: String, amount: Double, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("BDT ${salaryAmount(amount)}", color = color, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun SalaryMiniAmount(label: String, amount: Double, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CardBgAlt)
            .padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        Text(label, color = TextMuted, fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        Text("BDT ${salaryAmount(amount)}", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun salaryAmount(value: Double): String = String.format(Locale.US, "%,.0f", value.coerceAtLeast(0.0))

@Composable
private fun SalaryPaymentDialog(
    salary: SalaryEntity,
    onDismiss: () -> Unit,
    onSubmit: (amount: Double, method: String, note: String?) -> Unit,
) {
    val paid = salary.paidAmount.coerceIn(0.0, salary.netSalary)
    val due = (salary.netSalary - paid).coerceAtLeast(0.0)
    var amountText by remember(salary.id, salary.paidAmount) { mutableStateOf(salaryAmount(due)) }
    var method by remember(salary.id) { mutableStateOf("cash") }
    var note by remember(salary.id) { mutableStateOf("") }
    var validationError by remember(salary.id) { mutableStateOf<String?>(null) }
    val amount = amountText.replace(",", "").toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Text("Record salary payment", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(salary.salaryMonth, color = TextMuted, fontSize = 12.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBgAlt).padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column { Text("Paid so far", color = TextMuted, fontSize = 11.sp); Text("BDT ${salaryAmount(paid)}", color = WAGreen, fontWeight = FontWeight.Bold) }
                    Column(horizontalAlignment = Alignment.End) { Text("Remaining due", color = TextMuted, fontSize = 11.sp); Text("BDT ${salaryAmount(due)}", color = AccentAmber, fontWeight = FontWeight.Bold) }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }; validationError = null },
                    label = { Text("Payment amount (BDT)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = darkFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Payment method", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("cash" to "Cash", "bank_transfer" to "Bank", "mobile_banking" to "Mobile").forEach { (key, label) ->
                        FilterChip(
                            selected = method == key,
                            onClick = { method = key },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricBlue.copy(alpha = 0.22f),
                                selectedLabelColor = Cyan,
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    minLines = 1,
                    maxLines = 2,
                    colors = darkFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                validationError?.let { Text(it, color = AccentRed, fontSize = 12.sp) }
                Text("This updates the matching Institute Expense; no duplicate will be created.", color = Cyan, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        amount == null || amount <= 0.0 -> validationError = "Enter a valid payment amount."
                        amount > due + 0.009 -> validationError = "Amount cannot exceed the remaining due."
                        else -> onSubmit(amount, method, note.takeIf { it.isNotBlank() })
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            ) {
                Icon(Icons.Filled.Payments, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save payment", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) } },
    )
}

@Composable
private fun SalaryReceiptDialog(
    salary: SalaryEntity,
    staffName: String,
    instName: String,
    instCode: String,
    instPhone: String,
    instAddress: String,
    instLogoUri: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isPreparingReceipt by remember { mutableStateOf(false) }
    val paid = salary.paidAmount.coerceIn(0.0, salary.netSalary)
    val due = (salary.netSalary - paid).coerceAtLeast(0.0)
    val isPaid = paid > 0.0 && due <= 0.009
    val status = when {
        isPaid -> "PAID"
        paid > 0.0 -> "PARTIAL PAYMENT"
        else -> "PAYMENT PENDING"
    }
    val statusColor = when {
        isPaid -> WAGreen
        paid > 0.0 -> AccentAmber
        else -> AccentRed
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(ElectricBlue.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.ReceiptLong, null, tint = Cyan) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Salary receipt", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(salary.salarySlipNumber, color = TextMuted, fontSize = 11.sp)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(CardBgAlt).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(staffName, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Salary period: ${salary.salaryMonth}", color = TextMuted, fontSize = 11.sp)
                    }
                    Text(
                        status,
                        color = statusColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(statusColor.copy(alpha = 0.14f))
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                    )
                }
                ReceiptLine("Basic salary", salary.basicSalary)
                if (salary.bonusAmount > 0) ReceiptLine("Bonus", salary.bonusAmount, WAGreen)
                if (salary.deductionAmount > 0) ReceiptLine("Deduction", salary.deductionAmount, AccentRed)
                if (salary.advanceAmount > 0) ReceiptLine("Advance", salary.advanceAmount, AccentRed)
                HorizontalDivider(color = BorderSub)
                ReceiptLine("Net salary", salary.netSalary, Cyan, bold = true)
                ReceiptLine("Paid to date", paid, WAGreen, bold = true)
                ReceiptLine("Remaining due", due, if (due > 0) AccentAmber else WAGreen, bold = true)
                Text(
                    if (paid > 0) "Last payment: ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(salary.paymentDateMs ?: System.currentTimeMillis()))}"
                    else "No payment has been recorded yet.",
                    color = TextMuted,
                    fontSize = 11.sp,
                )
                Text("Available anytime from Salary Management.", color = Cyan, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isPreparingReceipt,
                onClick = {
                    scope.launch {
                        isPreparingReceipt = true
                        try {
                            val file = withContext(Dispatchers.IO) {
                                generateSalaryReceiptPdf(
                                    context, salary, staffName, instName, instCode,
                                    instPhone, instAddress, instLogoUri
                                )
                            }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/pdf")
                                clipData = ClipData.newRawUri("Salary receipt", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        } catch (_: Exception) {
                            Toast.makeText(context, "Could not open the receipt.", Toast.LENGTH_SHORT).show()
                        } finally {
                            isPreparingReceipt = false
                        }
                    }
                }
            ) {
                if (isPreparingReceipt) {
                    CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = ElectricBlue)
                } else {
                    Icon(Icons.Filled.Print, null, modifier = Modifier.size(18.dp), tint = ElectricBlue)
                }
                Spacer(Modifier.width(5.dp))
                Text(if (isPreparingReceipt) "Preparing..." else "Print", color = ElectricBlue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !isPreparingReceipt,
                    onClick = {
                        scope.launch {
                            isPreparingReceipt = true
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    generateSalaryReceiptPdf(
                                        context, salary, staffName, instName, instCode,
                                        instPhone, instAddress, instLogoUri
                                    )
                                }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_TEXT, "Salary receipt - $staffName - ${salary.salaryMonth}")
                                    clipData = ClipData.newRawUri("Salary receipt", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Share salary receipt"))
                            } catch (_: Exception) {
                                Toast.makeText(context, "Could not share the receipt.", Toast.LENGTH_SHORT).show()
                            } finally {
                                isPreparingReceipt = false
                            }
                        }
                    }
                ) {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(17.dp), tint = Cyan)
                    Spacer(Modifier.width(4.dp))
                    Text("Share", color = Cyan, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss, enabled = !isPreparingReceipt) { Text("Close", color = TextMuted) }
            }
        },
    )
}

@Composable
private fun ReceiptLine(label: String, amount: Double, color: Color = TextWhite, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            "BDT ${salaryAmount(amount)}",
            color = color,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold,
        )
    }
}

private suspend fun generateSalaryReceiptPdf(
    context: Context,
    salary: SalaryEntity,
    staffName: String,
    instName: String,
    instCode: String,
    instPhone: String,
    instAddress: String,
    instLogoUri: String?,
): File {
    val document = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
    val canvas = page.canvas

    val navy = AndroidColor.rgb(7, 24, 46)
    val blue = AndroidColor.rgb(37, 99, 235)
    val cyan = AndroidColor.rgb(34, 211, 238)
    val white = AndroidColor.WHITE
    val surface = AndroidColor.rgb(248, 250, 252)
    val border = AndroidColor.rgb(226, 232, 240)
    val textDark = AndroidColor.rgb(30, 41, 59)
    val textMuted = AndroidColor.rgb(100, 116, 139)
    val green = AndroidColor.rgb(22, 163, 74)
    val amber = AndroidColor.rgb(217, 119, 6)
    val red = AndroidColor.rgb(220, 38, 38)
    val paid = salary.paidAmount.coerceIn(0.0, salary.netSalary)
    val due = (salary.netSalary - paid).coerceAtLeast(0.0)
    val logo = loadBitmap(context, instLogoUri)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = border
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textDark; textSize = 11f }
    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textDark
        textSize = 13f
        isFakeBoldText = true
    }

    canvas.drawColor(white)

    // Branded header using the institute's saved logo.
    fill.shader = LinearGradient(0f, 0f, pageWidth.toFloat(), 170f, navy, blue, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, pageWidth.toFloat(), 170f, fill)
    fill.shader = null
    fill.color = AndroidColor.argb(28, 255, 255, 255)
    canvas.drawCircle(530f, 25f, 94f, fill)
    canvas.drawCircle(490f, 145f, 54f, fill)
    fill.color = white
    canvas.drawRoundRect(RectF(35f, 31f, 107f, 103f), 16f, 16f, fill)
    drawLogo(canvas, logo, instName, 41f, 37f, 60f, navy, cyan)

    val instituteTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        textSize = 20f
        isFakeBoldText = true
    }
    val upperInstituteName = instName.uppercase(Locale.getDefault())
    fitSalaryPdfText(upperInstituteName, instituteTitle, 365f, 20f, 13f)
    canvas.drawText(upperInstituteName, 124f, 57f, instituteTitle)
    val headerMeta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(207, 250, 254)
        textSize = 9.5f
    }
    val contactLine = listOf(
        instCode.takeIf { it.isNotBlank() && it != "N/A" },
        instPhone.takeIf { it.isNotBlank() },
    ).filterNotNull().joinToString("  |  ").ifBlank { "Official salary document" }
    fitSalaryPdfText(contactLine, headerMeta, 365f, 9.5f, 7.5f)
    canvas.drawText(contactLine, 124f, 77f, headerMeta)
    if (instAddress.isNotBlank()) {
        fitSalaryPdfText(instAddress, headerMeta, 365f, 9f, 7f)
        canvas.drawText(instAddress, 124f, 95f, headerMeta)
    }

    val receiptTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        textSize = 18f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText("STAFF SALARY RECEIPT", pageWidth / 2f, 139f, receiptTitle)
    val slipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(207, 250, 254)
        textSize = 8.5f
        textAlign = Paint.Align.CENTER
    }
    val receiptNumber = "Receipt No: ${salary.salarySlipNumber.ifBlank { salary.id }}"
    fitSalaryPdfText(receiptNumber, slipPaint, 500f, 8.5f, 6.5f)
    canvas.drawText(receiptNumber, pageWidth / 2f, 156f, slipPaint)

    // Staff and salary-period card.
    fill.color = surface
    canvas.drawRoundRect(RectF(35f, 192f, 560f, 270f), 14f, 14f, fill)
    canvas.drawRoundRect(RectF(35f, 192f, 560f, 270f), 14f, 14f, stroke)
    fill.color = AndroidColor.rgb(219, 234, 254)
    canvas.drawCircle(73f, 231f, 23f, fill)
    val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blue
        textSize = 19f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(staffName.trim().take(1).uppercase(Locale.getDefault()).ifBlank { "S" }, 73f, 238f, initialPaint)
    bold.textSize = 16f
    fitSalaryPdfText(staffName, bold, 295f, 16f, 11f)
    canvas.drawText(staffName, 112f, 224f, bold)
    text.color = textMuted
    text.textSize = 10f
    val salaryPeriodText = "Salary month  |  ${salary.salaryMonth}"
    fitSalaryPdfText(salaryPeriodText, text, 295f, 10f, 8f)
    canvas.drawText(salaryPeriodText, 112f, 244f, text)

    val statusText = when {
        due <= 0.009 -> "PAID"
        paid > 0.009 -> "PARTIAL"
        else -> "DUE"
    }
    val statusColor = when (statusText) {
        "PAID" -> green
        "PARTIAL" -> amber
        else -> red
    }
    fill.color = AndroidColor.argb(
        24,
        AndroidColor.red(statusColor),
        AndroidColor.green(statusColor),
        AndroidColor.blue(statusColor),
    )
    canvas.drawRoundRect(RectF(462f, 213f, 535f, 249f), 18f, 18f, fill)
    val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = statusColor
        textSize = 10f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(statusText, 498.5f, 235f, statusPaint)

    // Right-aligned amount column prevents long figures from being cut off.
    bold.color = textDark
    bold.textSize = 13f
    canvas.drawText("SALARY BREAKDOWN", 35f, 310f, bold)
    text.color = textMuted
    text.textSize = 9f
    canvas.drawText("Description", 50f, 337f, text)
    text.textAlign = Paint.Align.RIGHT
    canvas.drawText("Amount (BDT)", 545f, 337f, text)
    text.textAlign = Paint.Align.LEFT
    fill.color = border
    canvas.drawRect(35f, 346f, 560f, 347f, fill)

    var y = 373f
    val rows = listOf(
        Triple("Basic salary", salary.basicSalary, textDark),
        Triple("Bonus", salary.bonusAmount, green),
        Triple("Deduction", salary.deductionAmount, if (salary.deductionAmount > 0) red else textDark),
        Triple("Advance", salary.advanceAmount, if (salary.advanceAmount > 0) amber else textDark),
    )
    rows.forEachIndexed { index, (label, amount, amountColor) ->
        if (index % 2 == 1) {
            fill.color = surface
            canvas.drawRoundRect(RectF(35f, y - 19f, 560f, y + 10f), 5f, 5f, fill)
        }
        text.color = textMuted
        text.textSize = 11f
        canvas.drawText(label, 50f, y, text)
        text.color = amountColor
        text.textSize = 11.5f
        text.isFakeBoldText = amount != 0.0
        text.textAlign = Paint.Align.RIGHT
        val amountText = salaryAmount(amount)
        fitSalaryPdfText(amountText, text, 220f, 11.5f, 8f)
        canvas.drawText(amountText, 545f, y, text)
        text.textAlign = Paint.Align.LEFT
        text.isFakeBoldText = false
        y += 34f
    }

    fill.color = AndroidColor.rgb(239, 246, 255)
    canvas.drawRoundRect(RectF(35f, 505f, 560f, 553f), 10f, 10f, fill)
    bold.color = navy
    bold.textSize = 13f
    canvas.drawText("Net salary", 50f, 535f, bold)
    val rightBold = Paint(bold).apply {
        textAlign = Paint.Align.RIGHT
        color = blue
        textSize = 15f
    }
    val netSalaryText = "BDT ${salaryAmount(salary.netSalary)}"
    fitSalaryPdfText(netSalaryText, rightBold, 240f, 15f, 10f)
    canvas.drawText(netSalaryText, 545f, 535f, rightBold)

    fill.color = surface
    canvas.drawRoundRect(RectF(35f, 570f, 560f, 656f), 12f, 12f, fill)
    canvas.drawRoundRect(RectF(35f, 570f, 560f, 656f), 12f, 12f, stroke)
    listOf(
        Triple("Paid", paid, green),
        Triple("Remaining", due, if (due > 0) amber else green),
    ).forEachIndexed { index, (label, amount, color) ->
        val centerX = if (index == 0) 166f else 430f
        text.color = textMuted
        text.textSize = 9.5f
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(label, centerX, 597f, text)
        val amountText = "BDT ${salaryAmount(amount)}"
        val amountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 15f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        fitSalaryPdfText(amountText, amountPaint, 215f, 15f, 10f)
        canvas.drawText(amountText, centerX, 622f, amountPaint)
    }
    text.textAlign = Paint.Align.CENTER
    text.color = textMuted
    text.textSize = 9f
    val paidOn = salary.paymentDateMs?.takeIf { it > 0L }?.let {
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it))
    } ?: "Not recorded"
    val method = salary.paymentMethod?.trim()?.takeIf { it.isNotBlank() }
        ?.replaceFirstChar { it.uppercase() } ?: "Not recorded"
    val paymentMeta = "Method: $method  |  Payment date: $paidOn"
    fitSalaryPdfText(paymentMeta, text, 500f, 9f, 7f)
    canvas.drawText(paymentMeta, pageWidth / 2f, 644f, text)
    text.textAlign = Paint.Align.LEFT

    var noteBottom = 676f
    salary.note?.trim()?.takeIf { it.isNotBlank() }?.let { note ->
        bold.color = textDark
        bold.textSize = 10.5f
        canvas.drawText("Note", 35f, 681f, bold)
        text.color = textMuted
        text.textSize = 9.5f
        noteBottom = drawSalaryPdfWrappedText(canvas, note, 35f, 699f, 525f, 13f, text, maxLines = 3)
    }

    val signatureY = maxOf(742f, noteBottom + 34f).coerceAtMost(762f)
    fill.color = border
    canvas.drawRect(45f, signatureY, 215f, signatureY + 1f, fill)
    canvas.drawRect(380f, signatureY, 550f, signatureY + 1f, fill)
    text.color = textMuted
    text.textSize = 8f
    text.textAlign = Paint.Align.CENTER
    canvas.drawText("Staff signature", 130f, signatureY + 17f, text)
    canvas.drawText("Authorized signature", 465f, signatureY + 17f, text)

    fill.color = navy
    canvas.drawRect(0f, 802f, pageWidth.toFloat(), pageHeight.toFloat(), fill)
    text.color = white
    text.textSize = 8.5f
    val footerText = "Generated securely by BatchFee  |  $instName"
    fitSalaryPdfText(footerText, text, 520f, 8.5f, 6.5f)
    canvas.drawText(footerText, pageWidth / 2f, 823f, text)
    text.color = AndroidColor.rgb(165, 243, 252)
    text.textSize = 7.5f
    canvas.drawText("This is a computer-generated salary receipt.", pageWidth / 2f, 836f, text)

    document.finishPage(page)
    val safeMonth = salary.salaryMonth.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val file = File(context.cacheDir, "salary_receipt_${safeMonth}_${salary.id.takeLast(8)}.pdf")
    try {
        file.outputStream().use { document.writeTo(it) }
    } finally {
        document.close()
    }
    return file
}

private fun fitSalaryPdfText(
    value: String,
    paint: Paint,
    maxWidth: Float,
    preferredSize: Float,
    minimumSize: Float,
) {
    paint.textSize = preferredSize
    while (paint.textSize > minimumSize && paint.measureText(value) > maxWidth) {
        paint.textSize -= 0.5f
    }
}

private fun drawSalaryPdfWrappedText(
    canvas: android.graphics.Canvas,
    value: String,
    x: Float,
    startY: Float,
    maxWidth: Float,
    lineHeight: Float,
    paint: Paint,
    maxLines: Int,
): Float {
    val words = value.replace(Regex("\\s+"), " ").trim().split(" ")
    val lines = mutableListOf<String>()
    var current = ""
    words.forEach { word ->
        val candidate = if (current.isBlank()) word else "$current $word"
        if (paint.measureText(candidate) <= maxWidth) {
            current = candidate
        } else {
            if (current.isNotBlank()) lines += current
            current = word
        }
    }
    if (current.isNotBlank()) lines += current
    val visible = lines.take(maxLines).toMutableList()
    if (lines.size > maxLines && visible.isNotEmpty()) {
        var last = visible.last()
        while (last.isNotEmpty() && paint.measureText("$last...") > maxWidth) last = last.dropLast(1)
        visible[visible.lastIndex] = "${last.trimEnd()}..."
    }
    var y = startY
    visible.forEach { line ->
        canvas.drawText(line, x, y, paint)
        y += lineHeight
    }
    return y
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

// ═══════════════════════════════════════════════════════════════
//  Per-class / per-hour class & payment breakdown
// ═══════════════════════════════════════════════════════════════
private class ClassDayGroup(
    val dateMs: Long,
    val label: String,
    val sessions: List<TeachingSessionEntity>,
)

/** Groups completed classes by their calendar day (oldest first). */
private fun groupSessionsByDay(sessions: List<TeachingSessionEntity>): List<ClassDayGroup> {
    val dayKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val dayLabel = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())
    return sessions
        .sortedBy { it.sessionDateMs }
        .groupBy { dayKey.format(Date(it.sessionDateMs)) }
        .values
        .mapNotNull { group ->
            val first = group.minByOrNull { it.sessionDateMs } ?: return@mapNotNull null
            ClassDayGroup(first.sessionDateMs, dayLabel.format(Date(first.sessionDateMs)), group)
        }
        .sortedBy { it.dateMs }
}

/**
 * Lists completed classes grouped by day. Each day shows the class count and
 * earned amount and can be expanded to reveal every class + its amount.
 */
@Composable
private fun ClassBreakdownList(sessions: List<TeachingSessionEntity>, defaultExpanded: Boolean = false) {
    if (sessions.isEmpty()) {
        Text("No class records found.", color = TextMuted, fontSize = 12.sp)
        return
    }
    val groups = groupSessionsByDay(sessions)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { group ->
            val dayAmount = group.sessions.sumOf { it.calculatedAmount }
            var expanded by remember(group.dateMs) { mutableStateOf(defaultExpanded) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBgAlt)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(group.label, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${group.sessions.size} ${if (group.sessions.size == 1) "class" else "classes"}",
                            color = TextMuted, fontSize = 10.sp
                        )
                    }
                    Text("BDT ${salaryAmount(dayAmount)}", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (expanded) "Hide" else "View",
                        color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                }
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = BorderSub)
                    Spacer(Modifier.height(4.dp))
                    group.sessions.forEach { session -> SessionLine(session) }
                }
            }
        }
    }
}

@Composable
private fun SessionLine(session: TeachingSessionEntity) {
    val timeLabel = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(session.sessionDateMs))
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(session.subject?.takeIf { it.isNotBlank() } ?: "Class", color = TextWhite, fontSize = 11.sp)
            Text(timeLabel, color = TextMuted, fontSize = 9.5.sp)
        }
        Text("BDT ${salaryAmount(session.calculatedAmount)}", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Detail view for a generated per-class / per-hour salary: the classes by day,
 * the class-salary total, bonus / deduction / advance, paid and remaining due.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SalaryBreakdownDialog(
    salary: SalaryEntity,
    breakdown: SalaryViewModel.SalaryBreakdown?,
    onDismiss: () -> Unit,
) {
    val paid = salary.paidAmount.coerceIn(0.0, salary.netSalary)
    val due = (salary.netSalary - paid).coerceAtLeast(0.0)
    val sessions = breakdown?.sessions.orEmpty()
    val isPerClass = salary.calculationType == "per_class" || salary.calculationType == "per_hour"
    val sessionsLoaded = breakdown != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Text("Class & payment details", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(salary.salaryMonth, color = TextMuted, fontSize = 12.sp)
            }
        },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (!sessionsLoaded) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 44.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ElectricBlue, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        if (isPerClass) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBgAlt).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Completed classes", color = TextMuted, fontSize = 11.sp)
                                    Text("${sessions.size}", color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Class salary", color = TextMuted, fontSize = 11.sp)
                                    Text("BDT ${salaryAmount(salary.basicSalary)}", color = Cyan, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                        ReceiptLine("Basic salary", salary.basicSalary)
                        if (salary.bonusAmount > 0) ReceiptLine("Bonus", salary.bonusAmount, WAGreen)
                        if (salary.deductionAmount > 0) ReceiptLine("Deduction", salary.deductionAmount, AccentRed)
                        if (salary.advanceAmount > 0) ReceiptLine("Advance", salary.advanceAmount, AccentRed)
                        HorizontalDivider(color = BorderSub)
                        ReceiptLine("Net salary", salary.netSalary, Cyan, bold = true)
                        ReceiptLine("Paid to date", paid, WAGreen, bold = true)
                        ReceiptLine("Remaining due", due, if (due > 0) AccentAmber else WAGreen, bold = true)

                        if (isPerClass) {
                            Spacer(Modifier.height(2.dp))
                            SectionLabel("Classes by day")
                            if (sessions.isEmpty()) {
                                Text("No saved class records are linked to this salary.", color = TextMuted, fontSize = 11.sp)
                            } else {
                                ClassBreakdownList(sessions)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = ElectricBlue, fontWeight = FontWeight.Bold)
            }
        },
    )
}

