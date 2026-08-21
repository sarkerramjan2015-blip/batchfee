package com.batchfee.edu.ui.staff

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
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.models.StaffEntity
import com.batchfee.edu.data.models.SalaryEntity
import com.batchfee.edu.domain.SessionManager
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

    // Pre-fetch institute info once
    var instName by remember { mutableStateOf("BatchFee Institute") }
    var instCode by remember { mutableStateOf("N/A") }
    var instPhone by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val iid = SessionManager.currentInstituteId.value
        if (iid != null) {
            withContext(Dispatchers.IO) {
                db.instituteDao().getInstitute(iid)?.let { inst ->
                    instName = inst.name ?: "BatchFee Institute"
                    instCode = inst.instituteCode ?: "N/A"
                    instPhone = inst.phone ?: ""
                }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateSalaryScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: SalaryViewModel = viewModel(factory = SalaryViewModelFactory(db))
    val activeStaff by viewModel.activeStaff.collectAsState()
    val isAdmin = remember { SessionManager.isAdmin() }
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedStaffId by remember { mutableStateOf<String?>(null) }
    var month by remember { mutableStateOf(SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())) }
    var basic by remember { mutableStateOf("0") }
    var bonus by remember { mutableStateOf("0") }
    var deduction by remember { mutableStateOf("0") }
    var advance by remember { mutableStateOf("0") }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        onClick = { selectedStaffId = s.id; basic = s.monthlySalary.toString(); bonus = "0"; deduction = "0"; advance = "0" },
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
            val scope = rememberCoroutineScope()
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
                                onSuccess = onBack,
                                onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } })
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
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
                Column {
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
            TextButton(onClick = {
                try {
                    val file = generateSalaryReceiptPdf(context, salary, staffName, instName, instCode, instPhone)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: Exception) {
                    Toast.makeText(context, "Could not open the receipt.", Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Filled.Print, null, modifier = Modifier.size(18.dp), tint = ElectricBlue)
                Spacer(Modifier.width(5.dp))
                Text("Print", color = ElectricBlue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    try {
                        val file = generateSalaryReceiptPdf(context, salary, staffName, instName, instCode, instPhone)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_TEXT, "Salary receipt - $staffName - ${salary.salaryMonth}")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share salary receipt"))
                    } catch (_: Exception) {
                        Toast.makeText(context, "Could not share the receipt.", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(17.dp), tint = Cyan)
                    Spacer(Modifier.width(4.dp))
                    Text("Share", color = Cyan, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss) { Text("Close", color = TextMuted) }
            }
        },
    )
}

@Composable
private fun ReceiptLine(label: String, amount: Double, color: Color = TextWhite, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(
            "BDT ${salaryAmount(amount)}",
            color = color,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold,
        )
    }
}

private fun generateSalaryReceiptPdf(context: Context, salary: SalaryEntity, staffName: String, instName: String, instCode: String, instPhone: String): File {
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(340, 544, 1).create())
    val canvas = page.canvas
    val white = AndroidColor.WHITE
    val darkBlue = AndroidColor.rgb(30, 58, 95)
    val blue = AndroidColor.rgb(37, 99, 235)
    val textDark = AndroidColor.rgb(30, 41, 59)
    val textMuted = AndroidColor.rgb(71, 85, 105)
    val paid = salary.paidAmount.coerceIn(0.0, salary.netSalary)
    val due = (salary.netSalary - paid).coerceAtLeast(0.0)

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
        "Basic Salary" to "BDT ${salaryAmount(salary.basicSalary)}",
        "Bonus" to "BDT ${salaryAmount(salary.bonusAmount)}",
        "Deduction" to "BDT ${salaryAmount(salary.deductionAmount)}",
        "Advance" to "BDT ${salaryAmount(salary.advanceAmount)}",
        "Net Salary" to "BDT ${salaryAmount(salary.netSalary)}",
        "Paid to Date" to "BDT ${salaryAmount(paid)}",
        "Remaining Due" to "BDT ${salaryAmount(due)}"
    )
    rows.forEach { (label, value) ->
        val isNet = label == "Net Salary" || label == "Paid to Date" || label == "Remaining Due"
        if (isNet) {
            y += 5
            fill.color = android.graphics.Color.rgb(226, 232, 240)
            canvas.drawRect(20f, y - 2, 320f, y + 17f, fill)
        }
        text.textSize = if (isNet) 13f else 11f
        val labelColor = if (isNet) darkBlue else textMuted
        text.color = labelColor
        canvas.drawText(label, 20f, y + 10f, text)
        val valColor = when (label) {
            "Paid to Date" -> AndroidColor.rgb(22, 163, 74)
            "Remaining Due" -> if (due > 0) AndroidColor.rgb(217, 119, 6) else AndroidColor.rgb(22, 163, 74)
            else -> if (isNet) darkBlue else textDark
        }
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

