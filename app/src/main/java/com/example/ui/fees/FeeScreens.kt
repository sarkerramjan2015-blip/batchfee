package com.example.ui.fees

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.models.BatchEntity
import com.example.data.models.FeeEntity
import com.example.data.models.ReceiptEntity
import com.example.data.models.StudentEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Premium palette (matching other polished screens) ───────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val AccentRed     = Color(0xFFEF4444)
private val AccentGreen   = Color(0xFF10B981)

// polish: shared text field colors for dark theme inputs
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun premiumTextFieldColors() = OutlinedTextFieldDefaults.colors(
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = TextMuted, fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp
    )
}

// ── Helper: Month/Year data ─────────────────────────────────────
private data class MonthYear(val month: Int, val year: Int, val label: String)

private fun generateMonthOptions(): List<MonthYear> {
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    return (currentYear - 1..currentYear + 2).flatMap { year ->
        (1..12).map { month -> MonthYear(month, year, "${names[month - 1]} $year") }
    }
}

private fun parseFeePeriod(period: String): Pair<Int, Int>? {
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val parts = period.trim().split("\\s+".toRegex())
    if (parts.size < 2) return null
    val monthIdx = names.indexOfFirst { parts[0].startsWith(it, ignoreCase = true) }
    if (monthIdx < 0) return null
    val year = parts[1].takeWhile { it.isDigit() }.toIntOrNull() ?: return null
    return Pair(monthIdx, year)
}

// ── Helper: Image processing ────────────────────────────────────
private fun compressAndResizeBitmap(context: Context, uri: Uri, maxSize: Int = 800): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (opts.outWidth / sample > maxSize || opts.outHeight / sample > maxSize) sample *= 2
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, decodeOpts)
    } ?: return null
    val scale = minOf(maxSize.toFloat() / bmp.width, maxSize.toFloat() / bmp.height, 1f)
    return if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
}

// ── Helper: PDF receipt generation ───────────────────────────────
private fun generatePdfReceipt(
    context: Context,
    receiptNumber: String,
    studentName: String,
    batchName: String,
    feePeriod: String,
    baseAmount: Double,
    discountPercent: Double,
    discountAmount: Double,
    payableAmount: Double,
    totalPaid: Double,
    dueAmount: Double,
    collectedAmount: Double,
    paymentMethod: String
): Uri {
    val doc = PdfDocument()
    val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
    val c = page.canvas
    c.drawColor(android.graphics.Color.WHITE)

    // Header bar
    c.drawRect(0f, 0f, 595f, 80f, Paint().apply { color = android.graphics.Color.parseColor("#0F172A") })
    c.drawText("PAYMENT RECEIPT", 30f, 52f, Paint().apply {
        color = android.graphics.Color.WHITE; textSize = 28f
        typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
    })

    val sub = Paint().apply { color = android.graphics.Color.parseColor("#22D3EE"); textSize = 16f; isAntiAlias = true }
    c.drawText("Receipt #: $receiptNumber", 30f, 110f, sub)

    val lbl = Paint().apply { color = android.graphics.Color.parseColor("#64748B"); textSize = 14f; isAntiAlias = true }
    val vlu = Paint().apply { color = android.graphics.Color.parseColor("#0F172A"); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val nrm = Paint().apply { color = android.graphics.Color.parseColor("#0F172A"); textSize = 14f; isAntiAlias = true }
    val div = Paint().apply { color = android.graphics.Color.parseColor("#E2E8F0"); strokeWidth = 1f }

    var y = 150f; val lh = 28f; val c1 = 30f; val c2 = 200f
    for ((label, value) in listOf(
        "Student" to studentName, "Batch" to batchName, "Fee Period" to feePeriod
    )) { c.drawText(label, c1, y, lbl); c.drawText(value, c2, y, nrm); y += lh }
    y += 10f; c.drawLine(c1, y, 565f, y, div); y += 20f

    for ((label, value) in listOf(
        "Base Amount" to "BDT ${"%.2f".format(baseAmount)}",
        "Discount (${discountPercent.toInt()}%)" to "- BDT ${"%.2f".format(discountAmount)}",
    )) { c.drawText(label, c1, y, lbl); c.drawText(value, c2, y, nrm); y += lh }
    c.drawText("Payable Amount", c1, y, lbl)
    c.drawText("BDT ${"%.2f".format(payableAmount)}", c2, y, vlu); y += lh + 10f
    c.drawLine(c1, y, 565f, y, div); y += 20f

    c.drawText("Total Paid", c1, y, lbl); c.drawText("BDT ${"%.2f".format(totalPaid)}", c2, y, nrm); y += lh
    c.drawText("Due Amount", c1, y, lbl); c.drawText("BDT ${"%.2f".format(dueAmount)}", c2, y, nrm); y += lh
    c.drawText("Collected Now", c1, y, lbl)
    c.drawText("BDT ${"%.2f".format(collectedAmount)}", c2, y, Paint().apply {
        color = android.graphics.Color.parseColor("#22D3EE"); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
    }); y += lh + 10f
    c.drawLine(c1, y, 565f, y, div); y += 20f

    c.drawText("Payment Method", c1, y, lbl); c.drawText(paymentMethod.uppercase(), c2, y, nrm); y += lh
    c.drawText("Date", c1, y, lbl); c.drawText(SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date()), c2, y, nrm)

    c.drawText("Generated by BatchFee", 297.5f, 820f, Paint().apply {
        color = android.graphics.Color.parseColor("#94A3B8"); textSize = 11f; isAntiAlias = true; textAlign = Paint.Align.CENTER
    })

    doc.finishPage(page)
    val file = File(context.cacheDir, "receipt_${receiptNumber.replace("/", "_")}.pdf")
    FileOutputStream(file).use { doc.writeTo(it) }
    doc.close()
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

// ── Helper: Receipt bitmap for sharing ───────────────────────────
private fun createReceiptBitmap(
    receiptNumber: String, studentName: String, batchName: String, feePeriod: String,
    payableAmount: Double, collectedAmount: Double, dueAmount: Double, paymentMethod: String
): Bitmap {
    val w = 800; val h = 900
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(android.graphics.Color.WHITE)

    c.drawRect(0f, 0f, w.toFloat(), 100f, Paint().apply { color = android.graphics.Color.parseColor("#0F172A") })
    c.drawText("PAYMENT RECEIPT", 30f, 65f, Paint().apply {
        color = android.graphics.Color.WHITE; textSize = 36f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
    })

    val lbl = Paint().apply { color = android.graphics.Color.parseColor("#64748B"); textSize = 22f; isAntiAlias = true }
    val vlu = Paint().apply { color = android.graphics.Color.parseColor("#0F172A"); textSize = 24f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
    val div = Paint().apply { color = android.graphics.Color.parseColor("#E2E8F0"); strokeWidth = 2f }

    var y = 150f; val lh = 40f; val c1 = 30f; val c2 = 250f
    for ((label, value) in listOf(
        "Receipt" to receiptNumber, "Student" to studentName, "Batch" to batchName, "Period" to feePeriod
    )) { c.drawText(label, c1, y, lbl); c.drawText(value, c2, y, vlu); y += lh }
    y += 20f; c.drawLine(c1, y, 770f, y, div); y += 30f

    c.drawText("Payable", c1, y, lbl); c.drawText("BDT ${"%.0f".format(payableAmount)}", c2, y, vlu); y += lh
    c.drawText("Collected", c1, y, lbl)
    c.drawText("BDT ${"%.0f".format(collectedAmount)}", c2, y, Paint().apply {
        color = android.graphics.Color.parseColor("#22D3EE"); textSize = 28f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
    }); y += lh
    c.drawText("Due", c1, y, lbl); c.drawText("BDT ${"%.0f".format(dueAmount)}", c2, y, vlu); y += lh + 20f
    c.drawLine(c1, y, 770f, y, div); y += 30f
    c.drawText("Method", c1, y, lbl); c.drawText(paymentMethod.uppercase(), c2, y, vlu); y += lh
    c.drawText("Date", c1, y, lbl); c.drawText(SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date()), c2, y, vlu)

    c.drawText("BatchFee", 400f, 860f, Paint().apply {
        color = android.graphics.Color.parseColor("#94A3B8"); textSize = 18f; isAntiAlias = true; textAlign = Paint.Align.CENTER
    })
    return bmp
}

// ── Helper: WhatsApp / share ─────────────────────────────────────
private fun shareReceiptImage(context: Context, bitmap: Bitmap, phone: String?) {
    val file = File(context.cacheDir, "receipt_share_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = { pkg: String? ->
        Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) `package` = pkg
            if (!phone.isNullOrBlank() && pkg == "com.whatsapp") {
                putExtra("jid", "${phone.replace("+", "").replace(" ", "").replace("-", "")}@s.whatsapp.net")
            }
        }
    }
    try { context.startActivity(Intent.createChooser(send("com.whatsapp"), "Share Receipt")) }
    catch (_: Exception) { context.startActivity(Intent.createChooser(send(null), "Share Receipt")) }
}

// ── Reusable dropdown composable ─────────────────────────────────
@Composable
private fun <T> PremiumDropdown(
    label: String,
    options: List<T>,
    selectedOption: T?,
    onOptionSelected: (T) -> Unit,
    optionLabel: (T) -> String,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = selectedOption?.let(optionLabel) ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, tint = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = premiumTextFieldColors(),
            shape = RoundedCornerShape(12.dp)
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        expanded = !expanded
                    }
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(CardBg).heightIn(max = 300.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            optionLabel(option),
                            color = if (option == selectedOption) Cyan else TextWhite
                        )
                    },
                    onClick = { onOptionSelected(option); expanded = false }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  CreateFeeScreen  (unchanged)
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateFeeScreen(db: AppDatabase, onBack: () -> Unit) {
    val viewModel: FeeViewModel = viewModel(factory = FeeViewModelFactory(db))
    val instId = SessionManager.currentInstituteId.collectAsState().value
    var students by remember { mutableStateOf<List<com.example.data.models.StudentEntity>>(emptyList()) }
    var selectedStudentId by remember { mutableStateOf<String?>(null) }
    var baseAmount by remember { mutableStateOf("") }
    var feePeriod by remember { mutableStateOf("") }
    
    LaunchedEffect(instId) {
        if(instId != null) {
            db.studentDao().getStudentsByInstitute(instId).collect { students = it }
        }
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Create Fee", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite)
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
            if (students.isNotEmpty()) {
                SectionLabel("Select Student")
                Spacer(Modifier.height(6.dp))
                LazyRow {
                    items(students, key = { it.id }) { s ->
                        val selected = selectedStudentId == s.id
                        FilterChip(
                            selected = selected,
                            onClick = { selectedStudentId = if (selected) null else s.id },
                            label = { Text(s.fullName) },
                            modifier = Modifier.padding(end = 8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricBlue.copy(alpha = 0.25f),
                                selectedLabelColor = Cyan,
                                containerColor = CardBg,
                                labelColor = TextMuted
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = BorderSub,
                                selectedBorderColor = Cyan,
                                enabled = true,
                                selected = selected
                            )
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No students available. Please add a student first.", color = TextMuted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(16.dp))

            SectionLabel("Fee Period")
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = feePeriod,
                onValueChange = { feePeriod = it },
                placeholder = { Text("e.g. Jan 2026", color = TextMuted.copy(alpha = 0.5f)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = premiumTextFieldColors(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))

            SectionLabel("Amount (BDT)")
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = baseAmount,
                onValueChange = { baseAmount = it },
                placeholder = { Text("0.00", color = TextMuted.copy(alpha = 0.5f)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = premiumTextFieldColors(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(20.dp))

            val saveEnabled = selectedStudentId != null && baseAmount.isNotBlank() && feePeriod.isNotBlank()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.35f))
                    .let { m ->
                        if (saveEnabled)
                            m.background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                        else
                            m.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(14.dp))
                    },
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = {
                        val amount = baseAmount.toDoubleOrNull()
                        if (amount != null && selectedStudentId != null && feePeriod.isNotBlank()) {
                            viewModel.createFee(
                                studentId = selectedStudentId!!,
                                batchId = null,
                                feePeriod = feePeriod,
                                feeType = "monthly_fee",
                                dueDateMs = System.currentTimeMillis() + 7L*24*60*60*1000,
                                baseAmount = amount,
                                discount = 0.0,
                                lateFee = 0.0
                            )
                            onBack()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    enabled = saveEnabled,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (saveEnabled) Color.White else TextMuted,
                        disabledContentColor = TextMuted
                    )
                ) {
                    Text("Save Fee", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  CollectPaymentScreen  (enhanced)
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectPaymentScreen(db: AppDatabase, feeId: String, onBack: () -> Unit, onNavigateReceipt: (String) -> Unit) {
    val viewModel: FeeViewModel = viewModel(factory = FeeViewModelFactory(db))
    val instId = SessionManager.currentInstituteId.collectAsState().value
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── Data ──
    var fee by remember { mutableStateOf<FeeEntity?>(null) }
    var student by remember { mutableStateOf<StudentEntity?>(null) }
    var batches by remember { mutableStateOf<List<BatchEntity>>(emptyList()) }
    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // ── Calculations ──
    val monthOptions = remember { generateMonthOptions() }
    var startMonthIdx by remember { mutableIntStateOf(0) }
    var endMonthIdx by remember { mutableIntStateOf(0) }
    var discountPercent by remember { mutableDoubleStateOf(0.0) }
    var collectedAmount by remember { mutableStateOf("") }
    var manualAmountEdit by remember { mutableStateOf(false) }

    // ── Image ──
    var paymentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) paymentBitmap = compressAndResizeBitmap(context, uri)
    }

    // ── UI state ──
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var savedPaymentId by remember { mutableStateOf<String?>(null) }
    var savedReceipt by remember { mutableStateOf<ReceiptEntity?>(null) }

    // ── Load fee ──
    LaunchedEffect(instId, feeId) {
        if (instId != null) { fee = db.feeDao().getFeeById(feeId, instId); isLoading = false }
    }
    // ── Load student ──
    LaunchedEffect(instId, fee?.studentId) {
        val sid = fee?.studentId ?: return@LaunchedEffect
        if (instId != null) db.studentDao().getStudentById(sid, instId).collect { student = it }
    }
    // ── Load batches ──
    LaunchedEffect(instId, fee?.studentId) {
        val sid = fee?.studentId ?: return@LaunchedEffect
        if (instId != null) {
            db.batchStudentDao().getBatchesForStudent(sid, instId).collect { list ->
                batches = list
                if (selectedBatchId == null && list.isNotEmpty()) {
                    selectedBatchId = fee?.batchId?.takeIf { id -> list.any { it.id == id } }
                        ?: list.firstOrNull()?.id
                }
            }
        }
    }
    // ── Parse fee period → default month indices ──
    LaunchedEffect(fee, monthOptions.size) {
        if (fee != null && startMonthIdx == 0 && endMonthIdx == 0) {
            parseFeePeriod(fee!!.feePeriod)?.let { (m, y) ->
                val idx = monthOptions.indexOfFirst { it.month == m + 1 && it.year == y }
                if (idx >= 0) { startMonthIdx = idx; endMonthIdx = idx }
            }
        }
    }

    // ── Reactive math ──
    val selectedBatch = batches.find { it.id == selectedBatchId }
    val batchFee = selectedBatch?.monthlyFeeAmount ?: 0.0
    val numMonths = if (endMonthIdx >= startMonthIdx && startMonthIdx >= 0) endMonthIdx - startMonthIdx + 1 else 0
    val baseAmount = batchFee * numMonths
    val discountAmount = baseAmount * discountPercent / 100.0
    val payableAmount = baseAmount - discountAmount
    val totalPaid = fee?.paidAmount ?: 0.0
    val dueAmount = (payableAmount - totalPaid).coerceAtLeast(0.0)

    // Auto-fill collected amount
    LaunchedEffect(discountPercent, selectedBatchId, startMonthIdx, endMonthIdx) { manualAmountEdit = false }
    LaunchedEffect(dueAmount) {
        if (!manualAmountEdit) collectedAmount = if (dueAmount > 0) String.format("%.0f", dueAmount) else ""
    }

    // ── Scaffold ──
    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Collect Payment", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        when {
            isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cyan)
            }
            fee == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Fee not found.", color = TextMuted)
            }
            savedPaymentId != null -> {
                // ── Success state ──
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(40.dp))
                    Icon(Icons.Filled.CheckCircle, "Saved", tint = AccentGreen, modifier = Modifier.size(72.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Payment Saved!", color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(24.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(0.2f)),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, BorderSub)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            savedReceipt?.let { r ->
                                ReceiptRow("Receipt", r.receiptNumber)
                                Spacer(Modifier.height(6.dp))
                            }
                            student?.let { ReceiptRow("Student", it.fullName) }
                            Spacer(Modifier.height(6.dp))
                            selectedBatch?.let { ReceiptRow("Batch", it.name) }
                            Spacer(Modifier.height(6.dp))
                            ReceiptRow("Period", monthOptions.getOrNull(startMonthIdx)?.label ?: "")
                            Spacer(Modifier.height(6.dp))
                            ReceiptRow("Collected", "BDT ${"%.2f".format(collectedAmount.toDoubleOrNull() ?: 0.0)}")
                            Spacer(Modifier.height(6.dp))
                            ReceiptRow("Remaining Due", "BDT ${"%.2f".format(dueAmount - (collectedAmount.toDoubleOrNull() ?: 0.0).coerceAtMost(dueAmount))}")
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    // Print & Share
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val uri = generatePdfReceipt(
                                        context, savedReceipt?.receiptNumber ?: "", student?.fullName ?: "",
                                        selectedBatch?.name ?: "", monthOptions.getOrNull(startMonthIdx)?.label ?: "",
                                        baseAmount, discountPercent, discountAmount, payableAmount,
                                        totalPaid + (collectedAmount.toDoubleOrNull() ?: 0.0),
                                        dueAmount - (collectedAmount.toDoubleOrNull() ?: 0.0).coerceAtMost(dueAmount),
                                        collectedAmount.toDoubleOrNull() ?: 0.0, "cash"
                                    )
                                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "application/pdf")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    })
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open PDF", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Cyan),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                        ) {
                            Icon(Icons.Filled.Print, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Print")
                        }
                        Button(
                            onClick = {
                                val bmp = createReceiptBitmap(
                                    savedReceipt?.receiptNumber ?: "", student?.fullName ?: "",
                                    selectedBatch?.name ?: "", monthOptions.getOrNull(startMonthIdx)?.label ?: "",
                                    payableAmount, collectedAmount.toDoubleOrNull() ?: 0.0,
                                    dueAmount - (collectedAmount.toDoubleOrNull() ?: 0.0).coerceAtMost(dueAmount), "cash"
                                )
                                shareReceiptImage(context, bmp, student?.phone)
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share", color = Color.White)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CardBgAlt)
                    ) { Text("Done", color = TextWhite, fontWeight = FontWeight.Bold) }
                }
            }
            else -> {
                // ── Collection form ──
                val f = fee!!
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    // Student info card
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = ElectricBlue.copy(0.15f)),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, BorderSub)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Person, null, tint = Cyan, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(student?.fullName ?: "Loading…", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                if (!student?.phone.isNullOrBlank()) {
                                    Text("Phone: ${student!!.phone}", color = TextMuted, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    // Batch selection
                    SectionLabel("BATCH")
                    Spacer(Modifier.height(4.dp))
                    PremiumDropdown(
                        label = "Select Batch",
                        options = batches,
                        selectedOption = selectedBatch,
                        onOptionSelected = { selectedBatchId = it.id },
                        optionLabel = { "${it.name} — BDT ${"%.0f".format(it.monthlyFeeAmount)}" },
                        enabled = batches.isNotEmpty()
                    )
                    if (batches.isEmpty()) {
                        Text("Student is not enrolled in any batch.", color = AccentRed, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(14.dp))

                    // Fee period
                    SectionLabel("FEE PERIOD")
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            PremiumDropdown("Start Month", monthOptions, monthOptions.getOrNull(startMonthIdx),
                                onOptionSelected = { startMonthIdx = monthOptions.indexOf(it) }, optionLabel = { it.label })
                        }
                        Box(Modifier.weight(1f)) {
                            PremiumDropdown("End Month", monthOptions, monthOptions.getOrNull(endMonthIdx),
                                onOptionSelected = { endMonthIdx = monthOptions.indexOf(it) }, optionLabel = { it.label })
                        }
                    }
                    if (numMonths > 0) {
                        Text("$numMonths month${if (numMonths > 1) "s" else ""} selected", color = Cyan, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (endMonthIdx < startMonthIdx) {
                        Text("End month must be after start month.", color = AccentRed, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(14.dp))

                    // Discount
                    SectionLabel("DISCOUNT")
                    Spacer(Modifier.height(4.dp))
                    val discountOptions = listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0)
                    PremiumDropdown("Discount %", discountOptions, discountPercent,
                        onOptionSelected = { discountPercent = it }, optionLabel = { "${it.toInt()}%" })
                    Spacer(Modifier.height(14.dp))

                    // Fee summary card
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(0.15f)),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, BorderSub)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            SummaryRow("Batch Fee / month", "BDT ${"%.0f".format(batchFee)}")
                            SummaryRow("× $numMonths months", "BDT ${"%.0f".format(baseAmount)}")
                            if (discountPercent > 0) {
                                SummaryRow("Discount (${discountPercent.toInt()}%)", "- BDT ${"%.2f".format(discountAmount)}", isDiscount = true)
                            }
                            HorizontalDivider(color = BorderSub, modifier = Modifier.padding(vertical = 6.dp))
                            SummaryRow("Payable Amount", "BDT ${"%.2f".format(payableAmount)}", bold = true)
                            Spacer(Modifier.height(4.dp))
                            SummaryRow("Already Paid", "BDT ${"%.2f".format(totalPaid)}")
                            SummaryRow("Due Amount", "BDT ${"%.2f".format(dueAmount)}", bold = true, valueColor = if (dueAmount > 0) AccentRed else AccentGreen)
                        }
                    }

                    // Collected now
                    SectionLabel("COLLECTED NOW")
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = collectedAmount,
                        onValueChange = { collectedAmount = it; manualAmountEdit = true },
                        placeholder = { Text("0", color = TextMuted.copy(0.5f)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = premiumTextFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        prefix = { Text("BDT ", color = TextMuted) }
                    )
                    Spacer(Modifier.height(14.dp))

                    // Image upload
                    SectionLabel("RECEIPT IMAGE (optional)")
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderSub),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                        ) {
                            Icon(Icons.Filled.PhotoLibrary, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Gallery", fontSize = 13.sp)
                        }
                        if (paymentBitmap != null) {
                            IconButton(onClick = { paymentBitmap = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Filled.Close, "Remove image", tint = AccentRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (paymentBitmap != null) {
                        Spacer(Modifier.height(8.dp))
                        Image(
                            bitmap = paymentBitmap!!.asImageBitmap(),
                            contentDescription = "Payment receipt image",
                            modifier = Modifier.size(90.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(Modifier.height(20.dp))

                    // Error
                    if (errorMsg != null) {
                        Text(errorMsg!!, color = AccentRed, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }

                    // Save button
                    val canSave = batches.isNotEmpty() && numMonths > 0 && endMonthIdx >= startMonthIdx
                            && (collectedAmount.toDoubleOrNull() ?: 0.0) > 0
                    Box(
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(0.35f))
                            .let { m ->
                                if (canSave) m.background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                                else m.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(14.dp))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(
                            onClick = {
                                errorMsg = null
                                val amount = collectedAmount.toDoubleOrNull() ?: 0.0
                                if (amount <= 0) { errorMsg = "Enter a valid amount."; return@TextButton }
                                val periodLabel = if (startMonthIdx == endMonthIdx) monthOptions[startMonthIdx].label
                                else "${monthOptions[startMonthIdx].label} – ${monthOptions[endMonthIdx].label}"
                                viewModel.updateFeeAndCollectPayment(
                                    feeId = feeId,
                                    newBaseAmount = baseAmount,
                                    discountPercent = discountPercent,
                                    collectedAmount = amount,
                                    paymentMethod = "cash",
                                    feePeriod = periodLabel,
                                    onSuccess = { pid ->
                                        scope.launch {
                                            savedPaymentId = pid
                                            savedReceipt = instId?.let { db.receiptDao().getReceiptByPaymentIdOnce(it, pid) }
                                        }
                                    },
                                    onError = { errorMsg = it }
                                )
                            },
                            modifier = Modifier.fillMaxSize(),
                            enabled = canSave,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (canSave) Color.White else TextMuted,
                                disabledContentColor = TextMuted
                            )
                        ) { Text("Save Payment", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, bold: Boolean = false, isDiscount: Boolean = false, valueColor: Color = TextWhite) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = if (isDiscount) AccentGreen else TextMuted, fontSize = 14.sp)
        Text(value, color = if (isDiscount) AccentGreen else valueColor, fontSize = if (bold) 16.sp else 14.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ReceiptRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 14.sp)
        Text(value, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ═══════════════════════════════════════════════════════════════
//  ReceiptDetailScreen  (enhanced with Print & Share)
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptDetailScreen(db: AppDatabase, paymentId: String, onBack: () -> Unit) {
    val instId = SessionManager.currentInstituteId.collectAsState().value
    val context = LocalContext.current
    var receipt by remember { mutableStateOf<ReceiptEntity?>(null) }
    var studentName by remember { mutableStateOf("") }
    var studentPhone by remember { mutableStateOf<String?>(null) }
    var batchName by remember { mutableStateOf("") }
    var feePeriod by remember { mutableStateOf("") }
    var discountPercent by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(instId, paymentId) {
        if (instId != null) db.receiptDao().getReceiptByPaymentId(instId, paymentId).collect { receipt = it }
    }
    LaunchedEffect(instId, receipt?.studentId) {
        val sid = receipt?.studentId ?: return@LaunchedEffect
        if (instId != null) {
            db.studentDao().getStudentById(sid, instId).firstOrNull()?.let {
                studentName = it.fullName; studentPhone = it.phone
            }
        }
    }
    LaunchedEffect(instId, receipt?.feeId) {
        val fid = receipt?.feeId ?: return@LaunchedEffect
        if (instId != null) {
            val fee = db.feeDao().getFeeById(fid, instId)
            fee?.let { feePeriod = it.feePeriod; discountPercent = if (it.baseAmount > 0) it.discountAmount / it.baseAmount * 100.0 else 0.0 }
            fee?.batchId?.let { bid ->
                db.batchDao().getBatchById(bid, instId).firstOrNull()?.let { batchName = it.name }
            }
        }
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Receipt", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            receipt?.let { r ->
                Card(
                    modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(0.25f)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, BorderSub)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Box(Modifier.fillMaxWidth().height(3.dp)
                            .background(brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))))
                        Spacer(Modifier.height(16.dp))
                        Text("Receipt #${r.receiptNumber}", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        if (studentName.isNotBlank()) { ReceiptRow("Student", studentName); Spacer(Modifier.height(6.dp)) }
                        if (batchName.isNotBlank()) { ReceiptRow("Batch", batchName); Spacer(Modifier.height(6.dp)) }
                        if (feePeriod.isNotBlank()) { ReceiptRow("Period", feePeriod); Spacer(Modifier.height(6.dp)) }
                        Text("Paid: BDT ${"%.2f".format(r.paidAmount)}", color = Cyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Remaining Due: BDT ${"%.2f".format(r.dueAmount)}", color = TextMuted, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Payments, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Method: ${r.paymentMethod.uppercase()}", color = TextMuted, fontSize = 13.sp)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val uri = generatePdfReceipt(
                                    context, r.receiptNumber, studentName, batchName, feePeriod,
                                    r.totalAmount, discountPercent, r.totalAmount * discountPercent / 100.0,
                                    r.totalAmount, r.paidAmount, r.dueAmount, r.paidAmount, r.paymentMethod
                                )
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            } catch (e: Exception) { Toast.makeText(context, "Could not open PDF", Toast.LENGTH_SHORT).show() }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Cyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                    ) {
                        Icon(Icons.Filled.Print, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Print")
                    }
                    Button(
                        onClick = {
                            val bmp = createReceiptBitmap(
                                r.receiptNumber, studentName, batchName, feePeriod,
                                r.totalAmount, r.paidAmount, r.dueAmount, r.paymentMethod
                            )
                            shareReceiptImage(context, bmp, studentPhone)
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Share", color = Color.White)
                    }
                }
            } ?: run {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Cyan)
                }
            }
        }
    }
}
