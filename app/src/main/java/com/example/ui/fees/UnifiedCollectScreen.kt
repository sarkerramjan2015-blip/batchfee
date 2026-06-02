package com.example.ui.fees

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.AppDatabase
import com.example.data.models.BatchEntity
import com.example.data.models.FeeEntity
import com.example.data.models.PaymentEntity
import com.example.data.models.StudentEntity
import com.example.data.repository.FeeCollectionRepository
import com.example.domain.SessionManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.text.NumberFormat
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
private val AccentGreen = Color(0xFF22C55E)
private val AccentRed = Color(0xFFEF4444)
private val AccentAmber = Color(0xFFF59E0B)

data class EnrichedDue(val fee: FeeEntity, val studentName: String, val batchName: String?)

private data class StudentPaymentHistory(
    val payment: PaymentEntity,
    val feePeriod: String,
    val batchId: String?,
    val batchName: String?,
    val baseAmount: Double,
    val discountAmount: Double,
    val totalAmount: Double,
    val remainingDue: Double
)

private data class PaymentEditRequest(
    val amount: Double,
    val method: String,
    val note: String,
    val feePeriod: String,
    val batchId: String?,
    val batchName: String?,
    val paymentDateMs: Long
)

private enum class PaymentMode(val label: String, val subtitle: String, val icon: ImageVector) {
    Due("Due fee", "Collect an unpaid balance", Icons.Filled.ReceiptLong),
    Running("Running month", "Create and collect this month", Icons.Filled.CalendarMonth),
    Advance("Advance fee", "Collect a future month", Icons.Filled.Payments)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedCollectScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onCollectPayment: (String) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val feeRepository = remember { FeeCollectionRepository(db) }

    var searchQuery by remember { mutableStateOf("") }
    var allStudents by remember { mutableStateOf<List<StudentEntity>>(emptyList()) }
    var selectedStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var studentAllFees by remember { mutableStateOf<List<FeeEntity>>(emptyList()) }
    var studentDues by remember { mutableStateOf<List<EnrichedDue>>(emptyList()) }
    var studentBatches by remember { mutableStateOf<List<BatchEntity>>(emptyList()) }
    var paymentHistory by remember { mutableStateOf<List<StudentPaymentHistory>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadingLedger by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var paymentMode by remember { mutableStateOf(PaymentMode.Due) }
    var selectedDueId by remember { mutableStateOf<String?>(null) }
    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var feePeriod by remember { mutableStateOf(monthLabelForOffset(0)) }
    var advanceOffset by remember { mutableIntStateOf(1) }
    var baseAmount by remember { mutableStateOf("") }
    var discountPercent by remember { mutableDoubleStateOf(0.0) }
    var collectAmount by remember { mutableStateOf("") }
    var paymentMethod by remember { mutableStateOf("cash") }
    var note by remember { mutableStateOf("") }
    var collectError by remember { mutableStateOf<String?>(null) }
    var editingHistoryItem by remember { mutableStateOf<StudentPaymentHistory?>(null) }

    val selectedBatch = studentBatches.firstOrNull { it.id == selectedBatchId }
    val selectedDue = studentDues.firstOrNull { it.fee.id == selectedDueId } ?: studentDues.firstOrNull()

    fun amountText(value: Double): String =
        if (value <= 0.0) "" else "%.0f".format(value)

    fun newFeeBaseFor(mode: PaymentMode, batch: BatchEntity? = selectedBatch, months: Int = advanceOffset): Double {
        val monthlyFee = batch?.monthlyFeeAmount ?: 0.0
        return when (mode) {
            PaymentMode.Advance -> monthlyFee * months.coerceAtLeast(1)
            PaymentMode.Running -> monthlyFee
            PaymentMode.Due -> 0.0
        }
    }

    fun setModeDefaults(mode: PaymentMode) {
        paymentMode = mode
        collectError = null
        note = ""
        when (mode) {
            PaymentMode.Due -> {
                val due = selectedDue ?: studentDues.firstOrNull()
                selectedDueId = due?.fee?.id
                feePeriod = due?.fee?.feePeriod ?: monthLabelForOffset(0)
                baseAmount = amountText(due?.fee?.totalAmount ?: 0.0)
                discountPercent = 0.0
                collectAmount = amountText(due?.fee?.dueAmount ?: 0.0)
            }
            PaymentMode.Running -> {
                selectedDueId = null
                feePeriod = monthLabelForOffset(0)
                baseAmount = amountText(newFeeBaseFor(mode))
                discountPercent = 0.0
                collectAmount = amountText(newFeeBaseFor(mode))
            }
            PaymentMode.Advance -> {
                selectedDueId = null
                feePeriod = advancePeriodLabel(advanceOffset)
                baseAmount = amountText(newFeeBaseFor(mode))
                discountPercent = 0.0
                collectAmount = amountText(newFeeBaseFor(mode))
            }
        }
    }

    fun loadStudentLedger(student: StudentEntity) {
        selectedStudent = student
        scope.launch {
            loadingLedger = true
            collectError = null
            val instId = SessionManager.currentInstituteId.value
            if (instId == null) {
                loadingLedger = false
                collectError = "No active institute session."
                return@launch
            }

            val allFees = withContext(Dispatchers.IO) {
                db.feeDao().getAllFeesOnce(instId)
                    .filter { it.studentId == student.id && it.cancelledAtMs == null }
            }
            val batches = withContext(Dispatchers.IO) {
                db.batchStudentDao().getBatchesForStudent(student.id, instId).first()
            }
            val batchMap = withContext(Dispatchers.IO) {
                db.batchDao().getBatchesByInstituteOnce(instId).associateBy { it.id }
            }
            val payments = withContext(Dispatchers.IO) {
                db.paymentDao().getAllPaymentsOnce(instId)
                    .filter { it.studentId == student.id }
                    .sortedByDescending { it.paymentDateMs }
            }
            val receiptDueByPayment = withContext(Dispatchers.IO) {
                payments.associate { payment ->
                    payment.id to (db.receiptDao().getReceiptByPaymentIdOnce(instId, payment.id)?.dueAmount ?: 0.0)
                }
            }

            studentAllFees = allFees
            studentBatches = batches
            if (selectedBatchId == null || batches.none { it.id == selectedBatchId }) {
                selectedBatchId = batches.firstOrNull()?.id
            }
            studentDues = allFees
                .filter { it.dueAmount > 0.0 }
                .sortedBy { it.dueDateMs }
                .map { fee -> EnrichedDue(fee, student.fullName, fee.batchId?.let { batchMap[it]?.name }) }
            paymentHistory = payments.map { payment ->
                val fee = allFees.firstOrNull { it.id == payment.feeId }
                StudentPaymentHistory(
                    payment = payment,
                    feePeriod = fee?.feePeriod ?: "Fee payment",
                    batchId = fee?.batchId,
                    batchName = fee?.batchId?.let { batchMap[it]?.name },
                    baseAmount = fee?.baseAmount ?: payment.amount,
                    discountAmount = fee?.discountAmount ?: 0.0,
                    totalAmount = fee?.totalAmount ?: payment.amount,
                    remainingDue = receiptDueByPayment[payment.id] ?: 0.0
                )
            }

            if (studentDues.isNotEmpty()) {
                selectedDueId = studentDues.first().fee.id
                setModeDefaults(PaymentMode.Due)
            } else {
                setModeDefaults(PaymentMode.Running)
            }
            loadingLedger = false
        }
    }

    LaunchedEffect(Unit) {
        val instId = SessionManager.currentInstituteId.value ?: return@LaunchedEffect
        allStudents = withContext(Dispatchers.IO) {
            db.studentDao().getStudentsByInstituteOnce(instId)
        }
        isLoading = false
    }

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedStudent == null) "Fee Collection" else "Collect Payment",
                        color = TextWhite,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectedStudent != null) {
                                selectedStudent = null
                                studentAllFees = emptyList()
                                studentDues = emptyList()
                                paymentHistory = emptyList()
                                collectError = null
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        val filteredStudents = remember(allStudents, searchQuery) {
            if (searchQuery.isBlank()) {
                allStudents
            } else {
                allStudents.filter { student ->
                    student.fullName.contains(searchQuery, ignoreCase = true) ||
                        student.studentCode.contains(searchQuery, ignoreCase = true) ||
                        (student.phone?.contains(searchQuery) == true)
                }
            }
        }

        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Cyan)
                }
            }
            selectedStudent == null -> {
                StudentSearchPane(
                    modifier = Modifier.padding(padding),
                    query = searchQuery,
                    students = filteredStudents,
                    onQueryChange = { searchQuery = it },
                    onStudentSelected = { loadStudentLedger(it) }
                )
            }
            else -> {
                val student = selectedStudent!!
                val totalDue = studentDues.sumOf { it.fee.dueAmount }
                val totalPaid = paymentHistory.sumOf { it.payment.amount }
                val base = baseAmount.toDoubleOrNull() ?: 0.0
                val discountAmount = (base * discountPercent / 100.0).coerceAtLeast(0.0)
                val payable = (base - discountAmount).coerceAtLeast(0.0)
                val collecting = collectAmount.toDoubleOrNull() ?: 0.0
                val remainingAfterPayment = when (paymentMode) {
                    PaymentMode.Due -> ((selectedDue?.fee?.dueAmount ?: 0.0) - collecting).coerceAtLeast(0.0)
                    else -> (payable - collecting).coerceAtLeast(0.0)
                }
                val canSave = !loadingLedger && !isSaving && collecting > 0.0 && when (paymentMode) {
                    PaymentMode.Due -> selectedDue != null
                    else -> feePeriod.isNotBlank() && base > 0.0 && discountPercent in 0.0..100.0
                }

                LaunchedEffect(paymentMode, baseAmount, discountPercent, advanceOffset, selectedBatchId) {
                    if (paymentMode != PaymentMode.Due) {
                        collectAmount = amountText(payable)
                    }
                }

                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        StudentLedgerHeader(
                            student = student,
                            totalDue = totalDue,
                            totalPaid = totalPaid,
                            paymentCount = paymentHistory.size
                        )
                    }

                    if (loadingLedger) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 26.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Cyan, modifier = Modifier.size(26.dp))
                            }
                        }
                    } else {
                        item {
                            PaymentHistoryCard(
                                history = paymentHistory,
                                onPrint = { item -> printHistoryReceipt(context, student, item) },
                                onWhatsApp = { item -> sendHistoryReceiptWhatsApp(context, student.phone, buildHistoryReceiptText(student, item)) },
                                onMessage = { item -> sendHistoryReceiptMessage(context, student.phone, buildHistoryReceiptText(student, item)) },
                                onShare = { item -> shareHistoryReceipt(context, buildHistoryReceiptText(student, item)) },
                                onEdit = { item -> editingHistoryItem = item }
                            )
                        }

                        item {
                            PaymentModeSelector(
                                selected = paymentMode,
                                hasDue = studentDues.isNotEmpty(),
                                onSelect = { setModeDefaults(it) }
                            )
                        }

                        if (paymentMode == PaymentMode.Due) {
                            item {
                                ExistingDueSelector(
                                    dues = studentDues,
                                    selectedDueId = selectedDue?.fee?.id,
                                    onSelect = { due ->
                                        selectedDueId = due.fee.id
                                        feePeriod = due.fee.feePeriod
                                        baseAmount = amountText(due.fee.totalAmount)
                                        discountPercent = 0.0
                                        collectAmount = amountText(due.fee.dueAmount)
                                        collectError = null
                                    }
                                )
                            }
                        } else {
                            item {
                                NewFeeForm(
                                    mode = paymentMode,
                                    batches = studentBatches,
                                    selectedBatchId = selectedBatchId,
                                    feePeriod = feePeriod,
                                    advanceOffset = advanceOffset,
                                    baseAmount = baseAmount,
                                    discountPercent = discountPercent,
                                    onBatchSelected = { batch ->
                                        selectedBatchId = batch?.id
                                        val nextBase = newFeeBaseFor(paymentMode, batch)
                                        baseAmount = amountText(nextBase)
                                        collectAmount = amountText(nextBase)
                                    },
                                    onFeePeriodChange = { feePeriod = it },
                                    onAdvanceOffsetChange = {
                                        advanceOffset = it
                                        feePeriod = advancePeriodLabel(it)
                                        val nextBase = newFeeBaseFor(PaymentMode.Advance, selectedBatch, it)
                                        baseAmount = amountText(nextBase)
                                        collectAmount = amountText(nextBase)
                                    },
                                    onBaseAmountChange = {
                                        baseAmount = moneyInput(it)
                                        collectAmount = moneyInput(it)
                                    },
                                    onDiscountChange = { nextDiscount ->
                                        val safeDiscount = nextDiscount.coerceIn(0.0, 100.0)
                                        discountPercent = safeDiscount
                                        val nextBase = baseAmount.toDoubleOrNull() ?: 0.0
                                        collectAmount = amountText((nextBase - (nextBase * safeDiscount / 100.0)).coerceAtLeast(0.0))
                                    }
                                )
                            }
                        }

                        item {
                            PaymentInputCard(
                                paymentMode = paymentMode,
                                selectedDue = selectedDue,
                                payable = payable,
                                discountAmount = discountAmount,
                                collectAmount = collectAmount,
                                paymentMethod = paymentMethod,
                                note = note,
                                remainingAfterPayment = remainingAfterPayment,
                                onCollectAmountChange = { collectAmount = moneyInput(it) },
                                onMethodChange = { paymentMethod = it },
                                onNoteChange = { note = it }
                            )
                        }

                        if (collectError != null) {
                            item {
                                Text(
                                    collectError ?: "",
                                    color = AccentRed,
                                    fontSize = 13.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        item {
                            Button(
                                onClick = {
                                    collectError = null
                                    val instId = SessionManager.currentInstituteId.value
                                    val userId = SessionManager.currentUserId.value
                                    if (instId == null || userId == null) {
                                        collectError = "No active session."
                                        return@Button
                                    }
                                    if (collecting <= 0.0) {
                                        collectError = "Enter a valid payment amount."
                                        return@Button
                                    }
                                    if (paymentMode == PaymentMode.Due && selectedDue == null) {
                                        collectError = "Select a due fee first."
                                        return@Button
                                    }
                                    if (paymentMode != PaymentMode.Due) {
                                        if (base <= 0.0 || feePeriod.isBlank()) {
                                            collectError = "Enter fee period and amount."
                                            return@Button
                                        }
                                        if (collecting - payable > 0.001) {
                                            collectError = "Payment rejected - amount exceeds payable fee."
                                            return@Button
                                        }
                                        val duplicate = studentAllFees.any { fee ->
                                            fee.studentId == student.id &&
                                                fee.batchId == selectedBatchId &&
                                                fee.feePeriod.equals(feePeriod.trim(), ignoreCase = true) &&
                                                fee.cancelledAtMs == null
                                        }
                                        if (duplicate) {
                                            collectError = "This student already has a fee record for $feePeriod."
                                            return@Button
                                        }
                                    }

                                    scope.launch {
                                        isSaving = true
                                        try {
                                            val now = System.currentTimeMillis()
                                            val receiptText = buildCollectionReceiptText(
                                                student = student,
                                                batchName = selectedBatch?.name,
                                                period = if (paymentMode == PaymentMode.Due) selectedDue?.fee?.feePeriod ?: feePeriod else feePeriod.trim(),
                                                mode = paymentMode.label,
                                                payableAmount = if (paymentMode == PaymentMode.Due) selectedDue?.fee?.totalAmount ?: 0.0 else payable,
                                                discountAmount = if (paymentMode == PaymentMode.Due) selectedDue?.fee?.discountAmount ?: 0.0 else discountAmount,
                                                collectedAmount = collecting,
                                                remainingDue = remainingAfterPayment,
                                                paymentMethod = paymentMethod
                                            )
                                            val receiptNumber = if (paymentMode == PaymentMode.Due) {
                                                val result = feeRepository.collectPayment(
                                                    instituteId = instId,
                                                    collectedByUserId = userId,
                                                    feeId = selectedDue!!.fee.id,
                                                    amount = collecting,
                                                    paymentMethod = paymentMethod,
                                                    note = note.ifBlank { null },
                                                    receiptText = receiptText,
                                                    now = now
                                                )
                                                result.receiptNumber
                                            } else {
                                                val result = feeRepository.createFeeWithInitialPayment(
                                                    instituteId = instId,
                                                    collectedByUserId = userId,
                                                    studentId = student.id,
                                                    batchId = selectedBatchId,
                                                    feePeriod = feePeriod.trim(),
                                                    feeType = if (paymentMode == PaymentMode.Advance) "advance_fee" else "monthly_fee",
                                                    dueDateMs = now,
                                                    baseAmount = base,
                                                    discountAmount = discountAmount,
                                                    lateFeeAmount = 0.0,
                                                    collectedAmount = collecting,
                                                    paymentMethod = paymentMethod,
                                                    paymentDateMs = now,
                                                    note = note.ifBlank { null },
                                                    receiptText = receiptText,
                                                    now = now
                                                )
                                                result.receiptNumber ?: "payment"
                                            }
                                            snackbarHostState.showSnackbar("Payment saved: $receiptNumber")
                                            note = ""
                                            loadStudentLedger(student)
                                        } catch (e: IllegalArgumentException) {
                                            collectError = e.message ?: "Payment rejected."
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                },
                                enabled = canSave,
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElectricBlue,
                                    disabledContainerColor = CardBgAlt,
                                    contentColor = Color.White,
                                    disabledContentColor = TextMuted
                                )
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (isSaving) "Saving..." else "Save Payment", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    editingHistoryItem?.let { item ->
        val student = selectedStudent
        EditPaymentDialog(
            item = item,
            batches = studentBatches,
            onDismiss = { editingHistoryItem = null },
            onSave = { editRequest ->
                if (student == null) return@EditPaymentDialog
                scope.launch {
                    val instId = SessionManager.currentInstituteId.value
                    if (instId == null) {
                        snackbarHostState.showSnackbar("No active institute session.")
                        return@launch
                    }
                    if (editRequest.amount <= 0.0) {
                        snackbarHostState.showSnackbar("Enter a valid payment amount.")
                        return@launch
                    }
                    if (editRequest.feePeriod.isBlank()) {
                        snackbarHostState.showSnackbar("Enter fee month/period.")
                        return@launch
                    }
                    try {
                        val payment = item.payment
                        val fee = db.feeDao().getFeeById(payment.feeId, instId)
                        if (fee == null) {
                            snackbarHostState.showSnackbar("Fee record not found.")
                            return@launch
                        }
                        val allPaymentsForFee = db.paymentDao().getAllPaymentsOnce(instId)
                            .filter { it.feeId == fee.id && it.status == "completed" }
                        val updatedPaid = allPaymentsForFee.sumOf {
                            if (it.id == payment.id) editRequest.amount else it.amount
                        }
                        if (updatedPaid - fee.totalAmount > 0.001) {
                            snackbarHostState.showSnackbar("Payment rejected - amount exceeds payable fee.")
                            return@launch
                        }
                        val updatedDue = (fee.totalAmount - updatedPaid).coerceAtLeast(0.0)
                        val updatedFee = fee.copy(
                            batchId = editRequest.batchId,
                            feePeriod = editRequest.feePeriod.trim(),
                            dueDateMs = editRequest.paymentDateMs,
                            paidAmount = updatedPaid,
                            dueAmount = updatedDue,
                            status = when {
                                updatedDue <= 0.001 -> "paid"
                                updatedPaid > 0.001 -> "partially_paid"
                                else -> "unpaid"
                            },
                            updatedAtMs = System.currentTimeMillis()
                        )
                        db.feeDao().updateFee(updatedFee)
                        val updatedPayment = payment.copy(
                            amount = editRequest.amount,
                            paymentMethod = editRequest.method,
                            paymentDateMs = editRequest.paymentDateMs,
                            note = editRequest.note.ifBlank { null },
                            updatedAtMs = System.currentTimeMillis()
                        )
                        db.paymentDao().insertPayment(updatedPayment)
                        db.receiptDao().getReceiptByPaymentIdOnce(instId, payment.id)?.let { receipt ->
                            db.receiptDao().insertReceipt(
                                receipt.copy(
                                    totalAmount = updatedFee.totalAmount,
                                    paidAmount = editRequest.amount,
                                    dueAmount = updatedDue,
                                    receiptDateMs = editRequest.paymentDateMs,
                                    paymentMethod = editRequest.method,
                                    receiptText = buildHistoryReceiptText(
                                        student = student,
                                        item = item.copy(
                                            payment = updatedPayment,
                                            feePeriod = editRequest.feePeriod.trim(),
                                            batchId = editRequest.batchId,
                                            batchName = editRequest.batchName,
                                            remainingDue = updatedDue
                                        )
                                    )
                                )
                            )
                        }
                        editingHistoryItem = null
                        loadStudentLedger(student)
                        snackbarHostState.showSnackbar("Payment history updated.")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(e.message ?: "Could not update payment.")
                    }
                }
            }
        )
    }
}

@Composable
private fun StudentSearchPane(
    modifier: Modifier,
    query: String,
    students: List<StudentEntity>,
    onQueryChange: (String) -> Unit,
    onStudentSelected: (StudentEntity) -> Unit
) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Find student", color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        SmartTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Name, ID, or phone",
            leadingIcon = Icons.Filled.Search
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(students, key = { it.id }) { student ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, BorderSub)
                ) {
                    Column(Modifier.padding(14.dp).fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            InitialAvatar(student.fullName)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(student.fullName, color = TextWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    listOf(student.studentCode, student.phone).filterNotNull().filter { it.isNotBlank() }.joinToString("  |  "),
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { onStudentSelected(student) },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                        ) {
                            Icon(Icons.Filled.Payments, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Collect Payment", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (students.isEmpty() && query.isNotBlank()) {
                item { Text("No students found.", color = TextMuted, modifier = Modifier.padding(20.dp)) }
            }
        }
    }
}

@Composable
private fun StudentLedgerHeader(
    student: StudentEntity,
    totalDue: Double,
    totalPaid: Double,
    paymentCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(student.fullName)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(student.fullName, color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(student.studentCode, color = TextMuted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                LedgerStat("Due", formatSmartAmount(totalDue), AccentRed, Modifier.weight(1f))
                LedgerStat("Paid", formatSmartAmount(totalPaid), AccentGreen, Modifier.weight(1f))
                LedgerStat("Payments", paymentCount.toString(), Cyan, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LedgerStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardBgAlt)
            .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(label, color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PaymentHistoryCard(
    history: List<StudentPaymentHistory>,
    onPrint: (StudentPaymentHistory) -> Unit,
    onWhatsApp: (StudentPaymentHistory) -> Unit,
    onMessage: (StudentPaymentHistory) -> Unit,
    onShare: (StudentPaymentHistory) -> Unit,
    onEdit: (StudentPaymentHistory) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, contentDescription = null, tint = Cyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Payment History", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            if (history.isEmpty()) {
                Text("No payment recorded yet.", color = TextMuted, fontSize = 13.sp)
            } else {
                history.take(6).forEachIndexed { index, item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.feePeriod,
                                    color = TextWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    listOfNotNull(item.batchName, item.payment.paymentMethod.uppercase(), item.payment.receiptNumber)
                                        .joinToString(" | "),
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(formatSmartAmount(item.payment.amount), color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(formatDate(item.payment.paymentDateMs), color = TextMuted, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                HistoryActionButton("Print", Icons.Filled.Print, Cyan) { onPrint(item) }
                            }
                            item {
                                HistoryActionButton("WhatsApp", Icons.Filled.Message, AccentGreen) { onWhatsApp(item) }
                            }
                            item {
                                HistoryActionButton("Message", Icons.Filled.Message, TextMuted) { onMessage(item) }
                            }
                            item {
                                HistoryActionButton("Share", Icons.Filled.Share, ElectricBlue) { onShare(item) }
                            }
                            item {
                                HistoryActionButton("Edit", Icons.Filled.Payments, AccentAmber) { onEdit(item) }
                            }
                        }
                    }
                    if (index != history.take(6).lastIndex) HorizontalDivider(color = BorderSub)
                }
            }
        }
    }
}

@Composable
private fun HistoryActionButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EditPaymentDialog(
    item: StudentPaymentHistory,
    batches: List<BatchEntity>,
    onDismiss: () -> Unit,
    onSave: (PaymentEditRequest) -> Unit
) {
    var amount by remember(item.payment.id) { mutableStateOf("%.0f".format(item.payment.amount)) }
    var method by remember(item.payment.id) { mutableStateOf(item.payment.paymentMethod) }
    var note by remember(item.payment.id) { mutableStateOf(item.payment.note.orEmpty()) }
    var feePeriod by remember(item.payment.id) { mutableStateOf(item.feePeriod) }
    var paymentDate by remember(item.payment.id) { mutableStateOf(formatEditDate(item.payment.paymentDateMs)) }
    var selectedBatchId by remember(item.payment.id) { mutableStateOf(item.batchId) }
    val selectedBatch = batches.firstOrNull { it.id == selectedBatchId }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = { Text("Edit Payment", color = TextWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.payment.receiptNumber, color = TextMuted, fontSize = 12.sp)
                SmartTextField(
                    value = amount,
                    onValueChange = { amount = moneyInput(it) },
                    placeholder = "Payment amount",
                    keyboardType = KeyboardType.Decimal
                )
                SmartTextField(
                    value = paymentDate,
                    onValueChange = { paymentDate = it },
                    placeholder = "Payment date (dd/MM/yyyy)"
                )
                SmartTextField(
                    value = feePeriod,
                    onValueChange = { feePeriod = it },
                    placeholder = "Fee month / period"
                )
                Text("Batch", color = TextMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedBatchId == null,
                            onClick = { selectedBatchId = null },
                            label = { Text("Direct") },
                            colors = smartChipColors()
                        )
                    }
                    items(batches, key = { it.id }) { batch ->
                        FilterChip(
                            selected = selectedBatchId == batch.id,
                            onClick = { selectedBatchId = batch.id },
                            label = { Text(batch.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = smartChipColors()
                        )
                    }
                }
                Text("Payment Method", color = TextMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("cash", "bkash", "nagad", "bank")) { option ->
                        FilterChip(
                            selected = method == option,
                            onClick = { method = option },
                            label = { Text(option.replaceFirstChar { it.uppercase() }) },
                            colors = smartChipColors()
                        )
                    }
                }
                SmartTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = "Note or transaction reference"
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        PaymentEditRequest(
                            amount = amount.toDoubleOrNull() ?: 0.0,
                            method = method,
                            note = note,
                            feePeriod = feePeriod,
                            batchId = selectedBatchId,
                            batchName = selectedBatch?.name,
                            paymentDateMs = parseEditDate(paymentDate) ?: item.payment.paymentDateMs
                        )
                    )
                }
            ) {
                Text("Save", color = Cyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        }
    )
}

@Composable
private fun PaymentModeSelector(
    selected: PaymentMode,
    hasDue: Boolean,
    onSelect: (PaymentMode) -> Unit
) {
    Column {
        Text("Payment Type", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaymentMode.entries.forEach { mode ->
                val enabled = mode != PaymentMode.Due || hasDue
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(92.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = enabled) { onSelect(mode) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected == mode) ElectricBlue.copy(alpha = 0.22f) else CardBg
                    ),
                    border = BorderStroke(1.dp, if (selected == mode) Cyan else BorderSub)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(mode.icon, contentDescription = null, tint = if (enabled) Cyan else TextMuted, modifier = Modifier.size(18.dp))
                        Text(mode.label, color = if (enabled) TextWhite else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(mode.subtitle, color = TextMuted, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExistingDueSelector(
    dues: List<EnrichedDue>,
    selectedDueId: String?,
    onSelect: (EnrichedDue) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Select Due Fee", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (dues.isEmpty()) {
                Text("No pending due fee. Use Running month or Advance fee.", color = TextMuted, fontSize = 13.sp)
            } else {
                dues.forEach { due ->
                    val selected = due.fee.id == selectedDueId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) ElectricBlue.copy(alpha = 0.18f) else CardBgAlt)
                            .border(1.dp, if (selected) Cyan else BorderSub, RoundedCornerShape(12.dp))
                            .clickable { onSelect(due) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(due.fee.feePeriod, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(due.batchName ?: due.fee.feeType, color = TextMuted, fontSize = 11.sp)
                        }
                        Text(formatSmartAmount(due.fee.dueAmount), color = AccentRed, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun NewFeeForm(
    mode: PaymentMode,
    batches: List<BatchEntity>,
    selectedBatchId: String?,
    feePeriod: String,
    advanceOffset: Int,
    baseAmount: String,
    discountPercent: Double,
    onBatchSelected: (BatchEntity?) -> Unit,
    onFeePeriodChange: (String) -> Unit,
    onAdvanceOffsetChange: (Int) -> Unit,
    onBaseAmountChange: (String) -> Unit,
    onDiscountChange: (Double) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (mode == PaymentMode.Advance) "Advance Fee Details" else "Running Month Details", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)

            if (batches.isNotEmpty()) {
                Text("Batch", color = TextMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(batches, key = { it.id }) { batch ->
                        FilterChip(
                            selected = selectedBatchId == batch.id,
                            onClick = { onBatchSelected(batch) },
                            label = { Text("${batch.name} (${formatSmartAmount(batch.monthlyFeeAmount)})", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = smartChipColors()
                        )
                    }
                }
            } else {
                Text("No active batch assigned. This will be saved as a direct student fee.", color = TextMuted, fontSize = 12.sp)
            }

            if (mode == PaymentMode.Advance) {
                Text("Advance Month", color = TextMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(1, 2, 3, 6, 12)) { offset ->
                        FilterChip(
                            selected = advanceOffset == offset,
                            onClick = { onAdvanceOffsetChange(offset) },
                            label = { Text("+$offset month${if (offset > 1) "s" else ""}") },
                            colors = smartChipColors()
                        )
                    }
                }
            }

            SmartTextField(
                value = feePeriod,
                onValueChange = onFeePeriodChange,
                placeholder = "e.g. ${if (mode == PaymentMode.Advance) advancePeriodLabel(advanceOffset) else monthLabelForOffset(0)}",
                leadingIcon = Icons.Filled.CalendarMonth
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                SmartTextField(
                    value = baseAmount,
                    onValueChange = onBaseAmountChange,
                    placeholder = "Fee amount",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
                SmartTextField(
                    value = if (discountPercent == 0.0) "" else "%.0f".format(discountPercent),
                    onValueChange = { onDiscountChange(it.toDoubleOrNull() ?: 0.0) },
                    placeholder = "Discount %",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PaymentInputCard(
    paymentMode: PaymentMode,
    selectedDue: EnrichedDue?,
    payable: Double,
    discountAmount: Double,
    collectAmount: String,
    paymentMethod: String,
    note: String,
    remainingAfterPayment: Double,
    onCollectAmountChange: (String) -> Unit,
    onMethodChange: (String) -> Unit,
    onNoteChange: (String) -> Unit
) {
    val dueAmount = selectedDue?.fee?.dueAmount ?: 0.0
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Payment", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBgAlt)
                    .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SummaryLine(
                    label = if (paymentMode == PaymentMode.Due) "Selected due" else "Payable amount",
                    value = formatSmartAmount(if (paymentMode == PaymentMode.Due) dueAmount else payable),
                    color = if (paymentMode == PaymentMode.Due) AccentRed else TextWhite
                )
                if (paymentMode != PaymentMode.Due && discountAmount > 0.0) {
                    SummaryLine("Discount", "-${formatSmartAmount(discountAmount)}", AccentGreen)
                }
                SummaryLine("Remaining after payment", formatSmartAmount(remainingAfterPayment), if (remainingAfterPayment > 0.0) AccentAmber else AccentGreen)
            }

            SmartTextField(
                value = collectAmount,
                onValueChange = onCollectAmountChange,
                placeholder = "Collected amount",
                keyboardType = KeyboardType.Decimal,
                leadingIcon = Icons.Filled.Payments
            )

            Text("Payment Method", color = TextMuted, fontSize = 12.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("cash", "bkash", "nagad", "bank")) { method ->
                    FilterChip(
                        selected = paymentMethod == method,
                        onClick = { onMethodChange(method) },
                        label = { Text(method.replaceFirstChar { it.uppercase() }) },
                        colors = smartChipColors()
                    )
                }
            }

            SmartTextField(
                value = note,
                onValueChange = onNoteChange,
                placeholder = "Note or transaction reference"
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, color: Color = TextWhite) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 13.sp)
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
    leadingIcon: ImageVector? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = TextMuted.copy(alpha = 0.55f)) },
        modifier = modifier,
        singleLine = true,
        leadingIcon = leadingIcon?.let { icon ->
            { Icon(icon, contentDescription = null, tint = TextMuted) }
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = TextStyle(color = TextWhite, fontSize = 14.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextWhite,
            unfocusedTextColor = TextWhite,
            focusedBorderColor = ElectricBlue,
            unfocusedBorderColor = BorderSub,
            focusedContainerColor = CardBgAlt,
            unfocusedContainerColor = CardBgAlt,
            cursorColor = ElectricBlue
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun InitialAvatar(name: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(ElectricBlue.copy(alpha = 0.18f))
            .border(1.dp, Cyan.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(name.ifBlank { "S" }.take(1).uppercase(), color = Cyan, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun smartChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = ElectricBlue.copy(alpha = 0.24f),
    selectedLabelColor = Cyan,
    containerColor = CardBgAlt,
    labelColor = TextMuted
)

private fun moneyInput(value: String): String =
    value.filter { it.isDigit() || it == '.' }

private fun monthLabelForOffset(offset: Int): String {
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.MONTH, offset)
    return SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(calendar.time)
}

private fun advancePeriodLabel(monthCount: Int): String {
    val safeCount = monthCount.coerceAtLeast(1)
    return if (safeCount == 1) {
        monthLabelForOffset(1)
    } else {
        "${monthLabelForOffset(1)} - ${monthLabelForOffset(safeCount)}"
    }
}

private fun formatDate(timeMs: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timeMs))

private fun formatEditDate(timeMs: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(timeMs))

private fun parseEditDate(value: String): Long? =
    runCatching {
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).apply {
            isLenient = false
        }.parse(value.trim())?.time
    }.getOrNull()

private fun formatSmartAmount(amount: Double): String =
    NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 0
    }.format(amount)

private fun pdfSafe(value: String, maxChars: Int): String =
    if (value.length <= maxChars) value else value.take(maxChars - 1) + "..."

private fun formatDiscountPercent(item: StudentPaymentHistory): String {
    val percent = if (item.baseAmount > 0.0) item.discountAmount / item.baseAmount * 100.0 else 0.0
    return if (percent % 1.0 == 0.0) "%.0f".format(percent) else "%.1f".format(percent)
}

private fun drawReceiptLine(
    canvas: android.graphics.Canvas,
    textPaint: Paint,
    boldPaint: Paint,
    label: String,
    value: String,
    x: Float,
    y: Float,
    valueColor: Int = AndroidColor.rgb(20, 27, 38)
) {
    textPaint.color = AndroidColor.rgb(100, 116, 139)
    textPaint.textSize = 11f
    textPaint.textAlign = Paint.Align.LEFT
    canvas.drawText(label, x, y, textPaint)
    boldPaint.color = valueColor
    boldPaint.textSize = 12f
    boldPaint.textAlign = Paint.Align.RIGHT
    canvas.drawText(value, 368f, y, boldPaint)
    boldPaint.textAlign = Paint.Align.LEFT
}

private fun buildCollectionReceiptText(
    student: StudentEntity,
    batchName: String?,
    period: String,
    mode: String,
    payableAmount: Double,
    discountAmount: Double,
    collectedAmount: Double,
    remainingDue: Double,
    paymentMethod: String
): String = buildString {
    appendLine("BatchFee - Payment Receipt")
    appendLine("Student: ${student.fullName}")
    appendLine("ID: ${student.studentCode}")
    appendLine("Batch: ${batchName ?: "Direct"}")
    appendLine("Type: $mode")
    appendLine("Period: $period")
    appendLine("Payable: BDT ${formatSmartAmount(payableAmount)}")
    if (discountAmount > 0.0) appendLine("Discount: BDT ${formatSmartAmount(discountAmount)}")
    appendLine("Collected: BDT ${formatSmartAmount(collectedAmount)}")
    appendLine("Remaining Due: BDT ${formatSmartAmount(remainingDue)}")
    appendLine("Method: ${paymentMethod.uppercase()}")
    appendLine("Date: ${formatDate(System.currentTimeMillis())}")
}

private fun buildHistoryReceiptText(student: StudentEntity, item: StudentPaymentHistory): String =
    buildString {
        appendLine("BatchFee - Payment Receipt")
        appendLine("Receipt: ${item.payment.receiptNumber}")
        appendLine("Student: ${student.fullName}")
        appendLine("ID: ${student.studentCode}")
        appendLine("Batch: ${item.batchName ?: "Direct"}")
        appendLine("Period: ${item.feePeriod}")
        appendLine("Date: ${formatDate(item.payment.paymentDateMs)}")
        appendLine("Student ID: ${student.studentCode}")
        appendLine("Fee Amount: BDT ${formatSmartAmount(item.baseAmount)}")
        if (item.discountAmount > 0.0) {
            appendLine("Discount: ${formatDiscountPercent(item)}% - BDT ${formatSmartAmount(item.discountAmount)}")
        }
        appendLine("Payable: BDT ${formatSmartAmount(item.totalAmount)}")
        appendLine("Collected: BDT ${formatSmartAmount(item.payment.amount)}")
        appendLine("Remaining Due: BDT ${formatSmartAmount(item.remainingDue)}")
        appendLine("Method: ${item.payment.paymentMethod.uppercase()}")
        item.payment.note?.takeIf { it.isNotBlank() }?.let { appendLine("Note: $it") }
    }

private fun shareHistoryReceipt(context: Context, receiptText: String) {
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Payment Receipt")
                putExtra(Intent.EXTRA_TEXT, receiptText)
            },
            "Share Receipt"
        )
    )
}

private fun sendHistoryReceiptMessage(context: Context, phone: String?, receiptText: String) {
    context.startActivity(
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phone.orEmpty()}")).apply {
            putExtra("sms_body", receiptText)
        }
    )
}

private fun sendHistoryReceiptWhatsApp(context: Context, phone: String?, receiptText: String) {
    val cleanPhone = phone.orEmpty().replace("+", "").replace(" ", "").replace("-", "")
    val encoded = URLEncoder.encode(receiptText, "UTF-8")
    val url = if (cleanPhone.isBlank()) "https://wa.me/?text=$encoded" else "https://wa.me/$cleanPhone?text=$encoded"
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun printHistoryReceipt(context: Context, student: StudentEntity, item: StudentPaymentHistory) {
    val receiptText = buildHistoryReceiptText(student, item)
    try {
        val document = PdfDocument()
        val showDiscount = item.discountAmount > 0.0
        val pageHeight = if (showDiscount) 575 else 535
        val page = document.startPage(PdfDocument.PageInfo.Builder(420, pageHeight, 1).create())
        val canvas = page.canvas
        val ink = AndroidColor.rgb(20, 27, 38)
        val muted = AndroidColor.rgb(101, 116, 139)
        val blue = AndroidColor.rgb(37, 99, 235)
        val cyan = AndroidColor.rgb(14, 165, 233)
        val green = AndroidColor.rgb(16, 185, 129)
        val softBlue = AndroidColor.rgb(239, 246, 255)
        val softLine = AndroidColor.rgb(226, 232, 240)
        val pale = AndroidColor.rgb(248, 250, 252)
        val fill = Paint().apply { style = Paint.Style.FILL }
        val stroke = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f
            color = softLine
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 11.5f
        }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 13f
            isFakeBoldText = true
        }
        val whiteBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 12f
            isFakeBoldText = true
        }

        canvas.drawColor(AndroidColor.WHITE)
        fill.color = pale
        canvas.drawRoundRect(RectF(18f, 18f, 402f, pageHeight - 18f), 22f, 22f, fill)
        canvas.drawRoundRect(RectF(18f, 18f, 402f, pageHeight - 18f), 22f, 22f, stroke)

        fill.color = blue
        canvas.drawRoundRect(RectF(18f, 18f, 402f, 94f), 22f, 22f, fill)
        fill.color = cyan
        canvas.drawCircle(363f, 48f, 42f, fill)
        fill.color = AndroidColor.argb(80, 255, 255, 255)
        canvas.drawCircle(334f, 80f, 24f, fill)

        fill.color = AndroidColor.WHITE
        canvas.drawRoundRect(RectF(34f, 34f, 72f, 72f), 10f, 10f, fill)
        bold.color = blue
        bold.textSize = 14f
        canvas.drawText("BF", 44f, 58f, bold)
        whiteBold.textSize = 18f
        canvas.drawText("Fee Receipt", 88f, 50f, whiteBold)
        text.color = AndroidColor.argb(210, 255, 255, 255)
        text.textSize = 10.5f
        canvas.drawText("Receipt ${item.payment.receiptNumber}", 88f, 68f, text)
        canvas.drawText(formatEditDate(item.payment.paymentDateMs), 292f, 68f, text)

        var y = 124f
        bold.color = ink
        bold.textSize = 17f
        canvas.drawText(pdfSafe(student.fullName, 27), 34f, y, bold)
        text.color = muted
        text.textSize = 11f
        canvas.drawText("Student ID: ${student.studentCode}", 34f, y + 18f, text)
        canvas.drawText("Guardian: ${pdfSafe(student.guardianName ?: "N/A", 24)}", 34f, y + 34f, text)
        canvas.drawText("Phone: ${student.phone ?: student.guardianPhone ?: "N/A"}", 244f, y + 18f, text)
        canvas.drawText("Batch: ${pdfSafe(item.batchName ?: "Direct", 19)}", 244f, y + 34f, text)

        y += 64f
        fill.color = AndroidColor.WHITE
        canvas.drawRoundRect(RectF(34f, y, 386f, y + 108f), 16f, 16f, fill)
        canvas.drawRoundRect(RectF(34f, y, 386f, y + 108f), 16f, 16f, stroke)
        drawReceiptLine(canvas, text, bold, "Fee period", item.feePeriod, 52f, y + 28f)
        drawReceiptLine(canvas, text, bold, "Fee amount", "BDT ${formatSmartAmount(item.baseAmount)}", 52f, y + 52f)
        if (showDiscount) {
            drawReceiptLine(
                canvas,
                text,
                bold,
                "Discount",
                "${formatDiscountPercent(item)}%  |  BDT ${formatSmartAmount(item.discountAmount)}",
                52f,
                y + 76f,
                valueColor = green
            )
            drawReceiptLine(canvas, text, bold, "Payable", "BDT ${formatSmartAmount(item.totalAmount)}", 52f, y + 100f)
            y += 132f
        } else {
            drawReceiptLine(canvas, text, bold, "Payable", "BDT ${formatSmartAmount(item.totalAmount)}", 52f, y + 76f)
            y += 108f
        }

        fill.color = AndroidColor.WHITE
        canvas.drawRoundRect(RectF(34f, y, 386f, y + 104f), 16f, 16f, fill)
        canvas.drawRoundRect(RectF(34f, y, 386f, y + 104f), 16f, 16f, stroke)
        text.color = muted
        text.textSize = 11f
        canvas.drawText("Collected", 52f, y + 30f, text)
        bold.color = blue
        bold.textSize = 24f
        bold.textAlign = Paint.Align.RIGHT
        canvas.drawText("BDT ${formatSmartAmount(item.payment.amount)}", 368f, y + 34f, bold)
        bold.textAlign = Paint.Align.LEFT
        drawReceiptLine(canvas, text, bold, "Remaining due", "BDT ${formatSmartAmount(item.remainingDue)}", 52f, y + 68f, valueColor = if (item.remainingDue > 0.0) AndroidColor.rgb(245, 158, 11) else green)
        drawReceiptLine(canvas, text, bold, "Payment mode", item.payment.paymentMethod.replaceFirstChar { it.uppercase() }, 52f, y + 90f)
        y += 130f

        fill.color = softBlue
        canvas.drawRoundRect(RectF(34f, y, 386f, y + 54f), 14f, 14f, fill)
        text.color = muted
        text.textSize = 10.5f
        canvas.drawText("Remark", 52f, y + 20f, text)
        text.color = ink
        text.textSize = 11.5f
        canvas.drawText(pdfSafe(item.payment.note ?: "No remark added", 48), 52f, y + 39f, text)
        y += 86f

        stroke.color = softLine
        canvas.drawLine(44f, y, 172f, y, stroke)
        canvas.drawLine(248f, y, 376f, y, stroke)
        text.color = muted
        text.textSize = 10f
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("Received By", 108f, y + 17f, text)
        canvas.drawText("Authorized Sign", 312f, y + 17f, text)
        text.textAlign = Paint.Align.LEFT

        fill.color = blue
        canvas.drawRoundRect(RectF(34f, pageHeight - 46f, 386f, pageHeight - 30f), 8f, 8f, fill)
        text.color = AndroidColor.WHITE
        text.textSize = 9.5f
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("Thank you. This receipt was generated by BatchFee.", 210f, pageHeight - 34f, text)
        text.textAlign = Paint.Align.LEFT

        document.finishPage(page)

        val file = File(context.cacheDir, "history_receipt_${item.payment.receiptNumber.replace("/", "_")}.pdf")
        file.outputStream().use { document.writeTo(it) }
        document.close()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open receipt PDF. Sharing text instead.", Toast.LENGTH_SHORT).show()
        shareHistoryReceipt(context, receiptText)
    }
}
