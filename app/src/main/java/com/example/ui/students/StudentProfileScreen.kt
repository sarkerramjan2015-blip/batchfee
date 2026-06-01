package com.example.ui.students

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.database.AppDatabase
import com.example.data.models.BatchEntity
import com.example.data.models.BatchStudentEntity
import com.example.data.models.FeeEntity
import com.example.data.models.StudentEntity
import com.example.domain.SessionManager
import com.example.ui.fees.FeeViewModel
import com.example.ui.fees.FeeViewModelFactory
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

// ── Colors ──────────────────────────────────────────────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val SkyBlue       = Color(0xFF38BDF8)
private val VioletBlue    = Color(0xFF6366F1)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val WAGreen       = Color(0xFF25D366)
private val Teal          = Color(0xFF14B8A6)

// ── Screen ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentProfileScreen(
    db: AppDatabase,
    studentId: String,
    onBack: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val instId = SessionManager.currentInstituteId.collectAsState().value

    var student by remember { mutableStateOf<StudentEntity?>(null) }
    var totalPaid by remember { mutableStateOf(0.0) }
    var totalDue by remember { mutableStateOf(0.0) }
    var batches by remember { mutableStateOf<List<BatchEntity>>(emptyList()) }
    var enrolledBatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var feeHistory by remember { mutableStateOf<List<FeeEntity>>(emptyList()) }

    // ── Batch dialog state ──────────────────────────────────
    var showBatchDialog by remember { mutableStateOf(false) }

    // ── Fee collection state ─────────────────────────────────
    var showFeeForm by remember { mutableStateOf(false) }
    val feeViewModel: FeeViewModel = viewModel(factory = FeeViewModelFactory(db))
    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var feePeriod by remember { mutableStateOf("") }
    var feeAmount by remember { mutableStateOf("") }
    var discountPercent by remember { mutableStateOf("0") }
    var discountExpanded by remember { mutableStateOf(false) }
    val discountOptions = listOf("0", "10", "20", "30", "40", "50", "60", "70", "80")
    var collectAmount by remember { mutableStateOf("") }
    var paymentDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showPaymentDatePicker by remember { mutableStateOf(false) }
    var selectedFeeId by remember { mutableStateOf<String?>(null) }
    var receiptText by remember { mutableStateOf<String?>(null) }

    // ── Receipt image upload state ───────────────────────────
    var receiptImageUri by remember { mutableStateOf<Uri?>(null) }
    val tempReceiptFile = remember { File(context.cacheDir, "receipt_${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() } }
    val tempReceiptUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempReceiptFile)
    }
    val receiptCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success -> if (success) receiptImageUri = tempReceiptUri }
    val receiptGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) receiptImageUri = uri }
    var showReceiptImagePicker by remember { mutableStateOf(false) }

    // ── Load data ────────────────────────────────────────────
    LaunchedEffect(instId, studentId) {
        if (instId != null) {
            db.studentDao().getStudentById(studentId, instId).collect { student = it }
            db.feeDao().getFeesByStudent(instId, studentId).collect { fees ->
                feeHistory = fees
                totalPaid = fees.sumOf { it.paidAmount }
                totalDue = fees.sumOf { it.dueAmount }
            }
            db.batchStudentDao().getBatchesForStudent(studentId, instId).collect {
                batches = it
                enrolledBatchIds = it.map { b -> b.id }.toSet()
            }
        }
    }

    // ── Scaffold ─────────────────────────────────────────────
    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text(student?.fullName ?: "Profile", color = TextWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                actions = {
                    if (onEdit != null) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = Cyan)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        if (student == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cyan)
            }
        } else {
            val s = student!!
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // ── Photo + Student Code ──────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(CardBgAlt)
                            .border(2.dp, ElectricBlue, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!s.photoUri.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(Uri.parse(s.photoUri))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Student photo",
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Filled.Person, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(s.studentCode, color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(s.fullName, color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(s.phone ?: "No phone", color = TextMuted, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Action Buttons ────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Assign Batch
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                            )
                            .clickable { showBatchDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Assign Batch", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    // Collect Fees
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                brush = Brush.horizontalGradient(listOf(WAGreen, Teal))
                            )
                            .clickable { showFeeForm = !showFeeForm },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Payments, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Collect Fees", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Fee Summary Card ───────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total Paid", color = TextMuted, fontSize = 11.sp)
                            Text("BDT $totalPaid", color = WAGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total Due", color = TextMuted, fontSize = 11.sp)
                            Text("BDT $totalDue", color = Color(0xFFEF4444), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Inline Fee Collection Form ─────────────────
                if (showFeeForm) {
                    // Auto-select first batch on open
                    LaunchedEffect(batches) {
                        if (selectedBatchId == null && batches.isNotEmpty()) {
                            selectedBatchId = batches.first().id
                            feeAmount = batches.first().monthlyFeeAmount.toLong().toString()
                        }
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBgAlt)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Collect Fee Payment", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))

                            // Existing unpaid fees as chips
                            if (feeHistory.any { it.dueAmount > 0 }) {
                                Text("Unpaid Fees", color = TextMuted, fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    feeHistory.filter { it.dueAmount > 0 }.take(3).forEach { fee ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .then(
                                                    if (selectedFeeId == fee.id) Modifier.background(
                                                        Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                                                    )
                                                    else Modifier.background(CardBg).border(1.dp, BorderSub, RoundedCornerShape(8.dp))
                                                )
                                                .clickable {
                                                    selectedFeeId = fee.id
                                                    collectAmount = fee.dueAmount.toLong().toString()
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                "${fee.feePeriod} (Due: ${fee.dueAmount.toLong()})",
                                                color = if (selectedFeeId == fee.id) Color.White else TextMuted,
                                                fontSize = 11.sp,
                                                fontWeight = if (selectedFeeId == fee.id) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            // Batch selector
                            Text("Select Batch", color = TextMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                batches.forEach { batch ->
                                    val sel = batch.id == selectedBatchId
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .then(
                                                if (sel) Modifier.background(
                                                    Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                                                )
                                                else Modifier.background(CardBg).border(1.dp, BorderSub, RoundedCornerShape(8.dp))
                                            )
                                            .clickable {
                                                selectedBatchId = batch.id
                                                feeAmount = batch.monthlyFeeAmount.toLong().toString()
                                                discountPercent = "0"
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            "${batch.name} (${batch.monthlyFeeAmount} BDT)",
                                            color = if (sel) Color.White else TextMuted,
                                            fontSize = 11.sp,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            // Fee period
                            OutlinedTextField(
                                value = feePeriod,
                                onValueChange = { feePeriod = it },
                                label = { Text("Fee Period (e.g. Jan 2026)", color = TextMuted) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = darkFieldColors()
                            )
                            Spacer(Modifier.height(6.dp))

                            // Fee amount + discount dropdown
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = feeAmount,
                                    onValueChange = { feeAmount = it },
                                    label = { Text("Fee Amount (BDT)", color = TextMuted) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = darkFieldColors()
                                )
                                // Discount dropdown (10%–80%)
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = "$discountPercent%",
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Discount", color = TextMuted) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        colors = darkFieldColors(),
                                        trailingIcon = {
                                            IconButton(onClick = { discountExpanded = !discountExpanded }) {
                                                Icon(
                                                    if (discountExpanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                                                    contentDescription = null,
                                                    tint = Cyan
                                                )
                                            }
                                        }
                                    )
                                    DropdownMenu(
                                        expanded = discountExpanded,
                                        onDismissRequest = { discountExpanded = false }
                                    ) {
                                        discountOptions.forEach { opt ->
                                            DropdownMenuItem(
                                                text = { Text("$opt%") },
                                                onClick = {
                                                    discountPercent = opt
                                                    discountExpanded = false
                                                },
                                                leadingIcon = if (opt == discountPercent) {
                                                    { Icon(Icons.Filled.Check, contentDescription = null, tint = Cyan) }
                                                } else null
                                            )
                                        }
                                    }
                                }
                            }

                            // Calculated total
                            val baseAmount = feeAmount.toDoubleOrNull() ?: 0.0
                            val discountPct = discountPercent.toDoubleOrNull() ?: 0.0
                            val discountAmt = baseAmount * discountPct / 100.0
                            val finalTotal = (baseAmount - discountAmt).coerceAtLeast(0.0)
                            if (discountPct > 0) {
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Text(
                                        "After discount: BDT ${finalTotal.toLong()}",
                                        color = Cyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            // Collect amount + payment date
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = collectAmount,
                                    onValueChange = { collectAmount = it },
                                    label = { Text("Collect Now (BDT)", color = TextMuted) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = darkFieldColors()
                                )
                                // Payment date
                                val dateStr = remember(paymentDateMs) {
                                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(paymentDateMs))
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(CardBg)
                                        .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
                                        .clickable { showPaymentDatePicker = true }
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Column {
                                        Text("Payment Date", color = TextMuted, fontSize = 11.sp)
                                        Text(dateStr, color = TextWhite, fontSize = 14.sp)
                                    }
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            // ── Receipt image upload section ───────────────
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Receipt Photo (optional)", color = TextMuted, fontSize = 12.sp)
                                Spacer(Modifier.width(8.dp))
                                // Show uploaded image thumbnail or upload icon
                                if (receiptImageUri != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .border(1.5.dp, Cyan, CircleShape)
                                            .clickable { showReceiptImagePicker = true }
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(receiptImageUri)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = "Receipt photo",
                                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(CardBg)
                                            .border(1.dp, BorderSub, CircleShape)
                                            .clickable { showReceiptImagePicker = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.CameraAlt, contentDescription = "Upload", tint = TextMuted, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            // Three action buttons: Save / Print / Share
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // SAVE — creates Fee + Payment + Receipt via FeeViewModel
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                                        )
                                        .clickable {
                                            val base = feeAmount.toDoubleOrNull() ?: 0.0
                                            val paid = collectAmount.toDoubleOrNull() ?: 0.0
                                            if (base > 0 && feePeriod.isNotBlank()) {
                                                val total = (base - discountAmt).coerceAtLeast(0.0)
                                                val due = (total - paid).coerceAtLeast(0.0)
                                                // Build receipt text for Print/Share
                                                val rText = buildString {
                                                    appendLine("═══════════════════════")
                                                    appendLine("  BatchFee - Fee Receipt")
                                                    appendLine("═══════════════════════")
                                                    appendLine("Student : ${s.fullName}")
                                                    appendLine("ID      : ${s.studentCode}")
                                                    appendLine("Phone   : ${s.phone ?: "N/A"}")
                                                    appendLine("Batch   : ${batches.find { it.id == selectedBatchId }?.name ?: "N/A"}")
                                                    appendLine("Period  : $feePeriod")
                                                    appendLine("Date    : ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(paymentDateMs))}")
                                                    appendLine("───────────────────────")
                                                    appendLine("Fee     : BDT ${base.toLong()}")
                                                    appendLine("Discount: BDT ${discountAmt.toLong()} (${discountPct}%)")
                                                    appendLine("Total   : BDT ${total.toLong()}")
                                                    appendLine("Paid    : BDT ${paid.toLong()}")
                                                    appendLine("Due     : BDT ${due.toLong()}")
                                                    appendLine("═══════════════════════")
                                                }
                                                receiptText = rText
                                                scope.launch {
                                                    instId?.let { inst ->
                                                        // Insert Fee, Payment, and Receipt records
                                                        val fee = FeeEntity(
                                                            id = UUID.randomUUID().toString(),
                                                            instituteId = inst,
                                                            studentId = studentId,
                                                            batchId = selectedBatchId,
                                                            feePeriod = feePeriod,
                                                            feeType = "monthly_fee",
                                                            dueDateMs = paymentDateMs,
                                                            baseAmount = base,
                                                            discountAmount = discountAmt,
                                                            lateFeeAmount = 0.0,
                                                            totalAmount = total,
                                                            paidAmount = paid,
                                                            dueAmount = due,
                                                            status = if (due <= 0) "paid" else "partially_paid",
                                                            note = null,
                                                            createdAtMs = System.currentTimeMillis(),
                                                            updatedAtMs = System.currentTimeMillis(),
                                                            cancelledAtMs = null
                                                        )
                                                        db.feeDao().insertFee(fee)
                                                        if (paid > 0) {
                                                            val paymentId = UUID.randomUUID().toString()
                                                            val receiptNumber = "REC-${System.currentTimeMillis()}"
                                                            val payment = com.example.data.models.PaymentEntity(
                                                                id = paymentId,
                                                                instituteId = inst,
                                                                feeId = fee.id,
                                                                studentId = studentId,
                                                                amount = paid,
                                                                paymentMethod = "cash",
                                                                transactionId = null,
                                                                receiptNumber = receiptNumber,
                                                                paymentDateMs = paymentDateMs,
                                                                collectedByUserId = SessionManager.currentUserId.value ?: "",
                                                                status = "completed",
                                                                note = receiptImageUri?.toString(),
                                                                createdAtMs = System.currentTimeMillis(),
                                                                updatedAtMs = System.currentTimeMillis()
                                                            )
                                                            db.paymentDao().insertPayment(payment)
                                                            db.receiptDao().insertReceipt(
                                                                com.example.data.models.ReceiptEntity(
                                                                    id = UUID.randomUUID().toString(),
                                                                    instituteId = inst,
                                                                    paymentId = paymentId,
                                                                    feeId = fee.id,
                                                                    studentId = studentId,
                                                                    receiptNumber = receiptNumber,
                                                                    receiptDateMs = paymentDateMs,
                                                                    totalAmount = total,
                                                                    paidAmount = paid,
                                                                    dueAmount = due,
                                                                    paymentMethod = "cash",
                                                                    receiptText = rText,
                                                                    createdAtMs = System.currentTimeMillis()
                                                                )
                                                            )
                                                        }
                                                    }
                                                    feePeriod = ""; feeAmount = ""; discountPercent = "0"
                                                    collectAmount = ""; receiptImageUri = null
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Save", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                // PRINT — share receipt as text via system print/share chooser
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(CardBg)
                                        .border(1.dp, SkyBlue.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                        .clickable {
                                            val msg = receiptText ?: buildString {
                                                appendLine("═══════════════════════")
                                                appendLine("  BatchFee - Fee Receipt")
                                                appendLine("═══════════════════════")
                                                appendLine("Student : ${s.fullName}")
                                                appendLine("Period  : $feePeriod")
                                                appendLine("Fee     : BDT ${feeAmount}")
                                                appendLine("═══════════════════════")
                                            }
                                            val printIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, msg)
                                                putExtra(Intent.EXTRA_SUBJECT, "Fee Receipt - ${s.fullName}")
                                            }
                                            context.startActivity(Intent.createChooser(printIntent, "Print Receipt"))
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Print, contentDescription = null, tint = SkyBlue, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Print", color = SkyBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                // SHARE — open WhatsApp directly to student's phone number
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(CardBg)
                                        .border(1.dp, WAGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                        .clickable {
                                            val msg = receiptText ?: buildString {
                                                appendLine("BatchFee Receipt")
                                                appendLine("Student: ${s.fullName}")
                                                appendLine("Period: $feePeriod")
                                                appendLine("Fee: BDT $feeAmount")
                                            }
                                            val studentPhone = s.phone?.takeIf { it.isNotBlank() } ?: ""
                                            val encoded = URLEncoder.encode(msg, "UTF-8")
                                            val url = if (studentPhone.isNotEmpty())
                                                "https://wa.me/88${studentPhone}?text=$encoded"
                                            else
                                                "https://wa.me/?text=$encoded"
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Share, contentDescription = null, tint = WAGreen, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Share", color = WAGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // Cancel row
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CardBg)
                                    .border(1.dp, BorderSub, RoundedCornerShape(10.dp))
                                    .clickable { showFeeForm = false },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Cancel", color = TextMuted, fontSize = 12.sp)
                            }
                        }
                    }

                    // Receipt image picker dialog
                    if (showReceiptImagePicker) {
                        AlertDialog(
                            onDismissRequest = { showReceiptImagePicker = false },
                            containerColor = CardBgAlt,
                            title = { Text("Receipt Photo", color = TextWhite) },
                            text = { Text("Capture or select a receipt image", color = TextMuted) },
                            confirmButton = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        showReceiptImagePicker = false
                                        try { receiptCameraLauncher.launch(tempReceiptUri) } catch (_: Exception) {}
                                    }) { Text("Camera", color = Cyan) }
                                    TextButton(onClick = {
                                        showReceiptImagePicker = false
                                        receiptGalleryLauncher.launch("image/*")
                                    }) { Text("Gallery", color = Cyan) }
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showReceiptImagePicker = false }) {
                                    Text("Cancel", color = TextMuted)
                                }
                            }
                        )
                    }

                    // Payment date picker dialog
                    if (showPaymentDatePicker) {
                        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = paymentDateMs)
                        DatePickerDialog(
                            onDismissRequest = { showPaymentDatePicker = false },
                            confirmButton = {
                                TextButton(onClick = {
                                    paymentDateMs = datePickerState.selectedDateMillis ?: System.currentTimeMillis()
                                    showPaymentDatePicker = false
                                }) { Text("OK") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPaymentDatePicker = false }) { Text("Cancel") }
                            }
                        ) {
                            DatePicker(state = datePickerState)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                // ── Info Sections ──────────────────────────────
                SectionHeader("Personal Info")
                InfoCard {
                    InfoRow("Full Name", s.fullName)
                    InfoRow("Student ID", s.studentCode)
                    InfoRow("Gender", (s.gender ?: "N/A").replaceFirstChar { it.uppercase() })
                    InfoRow("Date of Birth", s.dateOfBirthMs?.let {
                        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it))
                    } ?: "N/A")
                    InfoRow("Blood Group", s.bloodGroup ?: "N/A")
                }

                Spacer(Modifier.height(14.dp))

                SectionHeader("Academic Info")
                InfoCard {
                    InfoRow("School / Institute", s.schoolName ?: "N/A")
                    InfoRow("Class", s.className ?: "N/A")
                    InfoRow("Admission Date", SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(s.admissionDateMs)))
                    InfoRow("Status", s.status.replaceFirstChar { it.uppercase() })
                    // Enrolled batches
                    if (batches.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Enrolled Batches:", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        batches.forEach { b ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                Icon(Icons.Filled.Groups, contentDescription = null, tint = SkyBlue, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(b.name, color = TextWhite, fontSize = 13.sp)
                                Spacer(Modifier.width(4.dp))
                                Text("(${b.monthlyFeeAmount} BDT/mo)", color = TextMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                SectionHeader("Contact Info")
                InfoCard {
                    InfoRow("Phone", s.phone ?: "N/A")
                    val whatsappFromNotes = s.notes?.let { n ->
                        if (n.startsWith("WhatsApp: ")) {
                            n.split("\n", limit = 2)[0].removePrefix("WhatsApp: ")
                        } else null
                    }
                    if (!whatsappFromNotes.isNullOrBlank()) {
                        InfoRow("WhatsApp", whatsappFromNotes)
                    }
                    InfoRow("Email", s.email ?: "N/A")
                    InfoRow("Address", s.address ?: "N/A")
                }

                Spacer(Modifier.height(14.dp))

                SectionHeader("Guardian Info")
                InfoCard {
                    InfoRow("Father / Guardian", s.guardianName ?: "N/A")
                    InfoRow("Mother Name", s.emergencyContact ?: "N/A")
                    InfoRow("Guardian Phone", s.guardianPhone ?: "N/A")
                }

                Spacer(Modifier.height(14.dp))

                // ── Notes ──────────────────────────────────────
                val cleanNotes = s.notes?.let { n ->
                    if (n.startsWith("WhatsApp: ")) n.split("\n", limit = 2).getOrElse(1) { "" }.trim()
                    else n
                }?.takeIf { it.isNotBlank() }
                if (!cleanNotes.isNullOrBlank()) {
                    SectionHeader("Notes")
                    InfoCard {
                        Text(cleanNotes, color = TextMuted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                }

                Spacer(Modifier.height(32.dp))
            }

            // ── Batch Assignment Dialog ────────────────────────
            if (showBatchDialog) {
                var allBatches by remember { mutableStateOf<List<BatchEntity>>(emptyList()) }
                LaunchedEffect(instId) {
                    if (instId != null) {
                        db.batchDao().getBatchesByInstitute(instId).collect { allBatches = it }
                    }
                }

                AlertDialog(
                    onDismissRequest = { showBatchDialog = false },
                    containerColor = CardBgAlt,
                    title = { Text("Assign Batch", color = TextWhite, fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            if (allBatches.isEmpty()) {
                                Text("No batches available. Create a batch first.", color = TextMuted)
                            } else {
                                allBatches.forEach { batch ->
                                    val isEnrolled = batch.id in enrolledBatchIds
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isEnrolled) Cyan.copy(alpha = 0.1f) else Color.Transparent)
                                            .then(
                                                if (isEnrolled) Modifier.border(1.dp, Cyan.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                                else Modifier.border(1.dp, BorderSub, RoundedCornerShape(10.dp))
                                            )
                                            .clickable {
                                                if (!isEnrolled && instId != null) {
                                                    scope.launch {
                                                        val enrollment = BatchStudentEntity(
                                                            id = UUID.randomUUID().toString(),
                                                            instituteId = instId!!,
                                                            batchId = batch.id,
                                                            studentId = studentId,
                                                            joinedAtMs = System.currentTimeMillis(),
                                                            status = "active",
                                                            leftAtMs = null
                                                        )
                                                        db.batchStudentDao().enrollStudent(enrollment)
                                                        // Refresh enrolled set
                                                        enrolledBatchIds = enrolledBatchIds + batch.id
                                                        batches = batches + batch
                                                    }
                                                }
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (isEnrolled) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline,
                                            contentDescription = null,
                                            tint = if (isEnrolled) Cyan else TextMuted,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(batch.name, color = if (isEnrolled) Cyan else TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            Text("${batch.monthlyFeeAmount} BDT/mo", color = TextMuted, fontSize = 11.sp)
                                        }
                                    }
                                    if (batch != allBatches.last()) Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showBatchDialog = false }) {
                            Text("Done", color = Cyan)
                        }
                    }
                )
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────
@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = ElectricBlue,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(value, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedBorderColor = ElectricBlue,
    unfocusedBorderColor = BorderSub,
    focusedContainerColor = CardBg,
    unfocusedContainerColor = CardBg,
    cursorColor = Cyan
)
