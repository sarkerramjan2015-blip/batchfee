package com.batchfee.edu.ui.fees

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.BatchStudentEntity
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.data.models.PaymentEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.data.repository.FeeCollectionRepository
import com.batchfee.edu.data.repository.FinancialOperationPendingException
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.MonthlyDueCalculator
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
import java.util.UUID

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

    var instituteInfo by remember { mutableStateOf(InstituteInfo("BatchFee", "N/A", "BF")) }
    LaunchedEffect(Unit) {
        val instId = SessionManager.currentInstituteId.value
        if (instId != null) {
            val entity = withContext(Dispatchers.IO) { db.instituteDao().getInstitute(instId) }
            if (entity != null) {
                instituteInfo = InstituteInfo(
                    name = entity.name.ifBlank { "BatchFee Institute" },
                    phone = entity.phone ?: "N/A",
                    logoText = entity.name.take(2).uppercase(),
                    logoUri = entity.profilePhotoUri
                )
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var allStudents by remember { mutableStateOf<List<StudentEntity>>(emptyList()) }
    var selectedStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var studentAllFees by remember { mutableStateOf<List<FeeEntity>>(emptyList()) }
    var studentDues by remember { mutableStateOf<List<EnrichedDue>>(emptyList()) }
    var studentBatches by remember { mutableStateOf<List<BatchEntity>>(emptyList()) }
    var studentEnrollments by remember { mutableStateOf<List<BatchStudentEntity>>(emptyList()) }
    var paymentHistory by remember { mutableStateOf<List<StudentPaymentHistory>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadingLedger by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var hasExistingDues by remember { mutableStateOf(false) }
    var showDueSelector by remember { mutableStateOf(false) }
    var showPeriodPicker by remember { mutableStateOf(false) }
    var showAdmissionFeeMode by remember { mutableStateOf(false) }
    var admissionFeeBatchId by remember { mutableStateOf<String?>(null) }
    var admissionFeeAmountVal by remember { mutableDoubleStateOf(0.0) }
    var admissionFeePaid by remember { mutableDoubleStateOf(0.0) }
    var selectedDueId by remember { mutableStateOf<String?>(null) }
    var selectedBatchId by remember { mutableStateOf<String?>(null) }
    var feePeriod by remember { mutableStateOf(monthLabelForOffset(0)) }
    val monthOptions = remember { generateMonthOptions() }
    val currentMonthIdx = remember {
        val cal = Calendar.getInstance()
        val curMonth = cal.get(Calendar.MONTH) + 1
        val curYear = cal.get(Calendar.YEAR)
        monthOptions.indexOfFirst { it.month == curMonth && it.year == curYear }.coerceAtLeast(0)
    }
    var startMonthIdx by remember { mutableIntStateOf(currentMonthIdx) }
    var endMonthIdx by remember { mutableIntStateOf(currentMonthIdx) }
    var baseAmount by remember { mutableStateOf("") }
    var discountPercent by remember { mutableDoubleStateOf(0.0) }
    var collectAmount by remember { mutableStateOf("") }
    var paymentMethod by remember { mutableStateOf("cash") }
    var note by remember { mutableStateOf("") }
    var collectError by remember { mutableStateOf<String?>(null) }
    var editingHistoryItem by remember { mutableStateOf<StudentPaymentHistory?>(null) }
    var reversingHistoryItem by remember { mutableStateOf<StudentPaymentHistory?>(null) }
    var deletingHistoryItem by remember { mutableStateOf<StudentPaymentHistory?>(null) }
    var isPartialPayment by remember { mutableStateOf(false) }

    val selectedBatch = studentBatches.firstOrNull { it.id == selectedBatchId }
    val selectedDue = studentDues.firstOrNull { it.fee.id == selectedDueId } ?: studentDues.firstOrNull()

    fun amountText(value: Double): String =
        if (value <= 0.0) "" else "%.0f".format(value)

    fun requiredMonthlyAmount(batch: BatchEntity?, period: String): Double {
        val safeBatch = batch ?: return 0.0
        val enrollment = studentEnrollments.firstOrNull { it.batchId == safeBatch.id }
        return MonthlyDueCalculator.monthlyFeeAmountForPeriod(
            period = period,
            monthlyFeeAmount = safeBatch.monthlyFeeAmount,
            firstMonthFeePeriod = enrollment?.firstMonthFeePeriod,
            firstMonthFeeAmount = enrollment?.firstMonthFeeAmount,
            customMonthlyFeeAmount = enrollment?.customMonthlyFeeAmount,
            customFeeEffectiveFromPeriod = enrollment?.customFeeEffectiveFromPeriod
        )
    }

    fun calcNewFeeBase(
        batch: BatchEntity? = selectedBatch,
        startIdx: Int = startMonthIdx,
        endIdx: Int = endMonthIdx
    ): Double {
        val start = minOf(startIdx, endIdx).coerceAtLeast(0)
        val end = maxOf(startIdx, endIdx).coerceAtMost(monthOptions.lastIndex)
        if (start > end) return 0.0
        return (start..end).sumOf { index ->
            requiredMonthlyAmount(batch, monthOptions[index].label)
        }
    }

    fun numSelectedMonths(): Int {
        val realStart = if (startMonthIdx >= 0) startMonthIdx else currentMonthIdx
        val realEnd = if (endMonthIdx >= 0) endMonthIdx else currentMonthIdx
        return if (realEnd >= realStart) realEnd - realStart + 1 else 1
    }

    val autoFeeType = remember(startMonthIdx, endMonthIdx) {
        autoDetectFeeType(startMonthIdx, endMonthIdx, currentMonthIdx)
    }

    fun resetFormForNewFee() {
        showDueSelector = false
        selectedDueId = null
        isPartialPayment = false
        startMonthIdx = currentMonthIdx
        endMonthIdx = currentMonthIdx
        val nextBase = calcNewFeeBase(selectedBatch)
        baseAmount = amountText(nextBase)
        discountPercent = 0.0
        collectAmount = amountText(nextBase)
        collectError = null
        note = ""
        feePeriod = buildFeePeriodLabel(startMonthIdx, endMonthIdx, monthOptions)
    }

    fun applyDueFeeSelection(due: EnrichedDue) {
        showDueSelector = true
        selectedDueId = due.fee.id
        feePeriod = due.fee.feePeriod
        baseAmount = amountText(due.fee.totalAmount)
        discountPercent = 0.0
        collectAmount = amountText(due.fee.dueAmount)
        collectError = null
    }

    fun onPeriodConfirmed(startIdx: Int, endIdx: Int) {
        startMonthIdx = minOf(startIdx, endIdx)
        endMonthIdx = maxOf(startIdx, endIdx)
        feePeriod = buildFeePeriodLabel(startMonthIdx, endMonthIdx, monthOptions)
        val nextBase = calcNewFeeBase(selectedBatch, startMonthIdx, endMonthIdx)
        baseAmount = amountText(nextBase)
        collectAmount = amountText(nextBase)
        showPeriodPicker = false
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
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)

            val allFees = withContext(Dispatchers.IO) {
                db.feeDao().getAllFeesOnce(instId)
                    .filter { it.studentId == student.id && it.cancelledAtMs == null }
            }
            val batches = withContext(Dispatchers.IO) {
                db.batchStudentDao().getBatchesForStudent(student.id, instId).first()
            }
            val enrollments = withContext(Dispatchers.IO) {
                db.batchStudentDao().getActiveEnrollmentsForStudentOnce(student.id, instId)
            }
            val billingEnrollments = withContext(Dispatchers.IO) {
                db.batchStudentDao().getBillingEnrollmentsForStudentOnce(student.id, instId)
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
            studentEnrollments = enrollments
            if (selectedBatchId == null || batches.none { it.id == selectedBatchId }) {
                selectedBatchId = batches.firstOrNull()?.id
            }

            // ── Monthly dues ──
            val monthlyDues = billingEnrollments.flatMap { enrollment ->
                val batch = batchMap[enrollment.batchId] ?: return@flatMap emptyList<EnrichedDue>()
                if (batch.monthlyFeeAmount <= 0.0) return@flatMap emptyList<EnrichedDue>()
                val batchFees = allFees.filter {
                    it.batchId == batch.id && it.studentId == student.id &&
                        MonthlyDueCalculator.isMonthlyFeeType(it.feeType)
                }
                val items = MonthlyDueCalculator.computeMonthlyOutstandingItems(
                    admissionDateMs = enrollment.joinedAtMs,
                    monthlyFeeAmount = batch.monthlyFeeAmount,
                    batchId = batch.id,
                    batchName = batch.name,
                    existingMonthlyFees = batchFees,
                    firstMonthFeePeriod = enrollment.firstMonthFeePeriod,
                    firstMonthFeeAmount = enrollment.firstMonthFeeAmount,
                    customMonthlyFeeAmount = enrollment.customMonthlyFeeAmount,
                    customFeeEffectiveFromPeriod = enrollment.customFeeEffectiveFromPeriod,
                    billingEndedAtMs = enrollment.leftAtMs
                )
                items.map { item ->
                    val existingFee = batchFees.firstOrNull { it.feePeriod.equals(item.period, ignoreCase = true) }
                    if (existingFee != null) EnrichedDue(existingFee, student.fullName, batch.name)
                    else {
                        val virtualFee = FeeEntity(
                            id = "", instituteId = instId, studentId = student.id,
                            batchId = batch.id, feePeriod = item.period, feeType = "monthly_fee",
                            dueDateMs = 0L, baseAmount = item.monthlyFeeAmount, discountAmount = 0.0,
                            lateFeeAmount = 0.0, totalAmount = item.monthlyFeeAmount,
                            paidAmount = item.paidAmount, dueAmount = item.outstanding,
                            status = if (item.paidAmount > 0.0) "partially_paid" else "unpaid",
                            note = null, createdAtMs = 0L, updatedAtMs = 0L, cancelledAtMs = null
                        )
                        EnrichedDue(virtualFee, student.fullName, batch.name)
                    }
                }
            }

            // ── Admission fee dues (one-time per batch) ──
            val admissionDues = batches.mapNotNull { batch ->
                if (batch.admissionFeeAmount <= 0.0) return@mapNotNull null
                val existing = allFees.firstOrNull {
                    it.batchId == batch.id && it.studentId == student.id &&
                        it.feeType == "admission_fee" && it.cancelledAtMs == null
                }
                if (existing != null) {
                    if (existing.dueAmount > 0.0) EnrichedDue(existing, student.fullName, batch.name) else null
                } else {
                    // No admission fee created yet — show virtual due
                    EnrichedDue(
                        FeeEntity(
                            id = "", instituteId = instId, studentId = student.id,
                            batchId = batch.id, feePeriod = "Admission", feeType = "admission_fee",
                            dueDateMs = 0L, baseAmount = batch.admissionFeeAmount, discountAmount = 0.0,
                            lateFeeAmount = 0.0, totalAmount = batch.admissionFeeAmount,
                            paidAmount = 0.0, dueAmount = batch.admissionFeeAmount,
                            status = "unpaid", note = null, createdAtMs = 0L, updatedAtMs = 0L, cancelledAtMs = null
                        ), student.fullName, batch.name
                    )
                }
            }

            // Server-created one-time fees (such as Exam Fee) must be shown
            // here too. They already have a real fee ID, so Collect Payment
            // updates the exact record and creates its normal receipt.
            val generatedOneTimeDues = allFees.filter { fee ->
                fee.dueAmount > 0.0 &&
                    !MonthlyDueCalculator.isMonthlyFeeType(fee.feeType) &&
                    !fee.feeType.equals("admission_fee", ignoreCase = true) &&
                    !fee.feeType.equals("admission", ignoreCase = true)
            }.map { fee ->
                EnrichedDue(
                    fee = fee,
                    studentName = student.fullName,
                    batchName = fee.batchId?.let { batchMap[it]?.name }
                )
            }

            studentDues = (monthlyDues + admissionDues + generatedOneTimeDues)
                .filter { it.fee.dueAmount > 0.0 }
                .sortedBy { it.fee.feePeriod }
            paymentHistory = payments.filter { it.status == "completed" }.map { payment ->
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
                applyDueFeeSelection(studentDues.first())
            } else {
                resetFormForNewFee()
            }
            loadingLedger = false
        }
    }

    LaunchedEffect(Unit) {
        val instId = SessionManager.currentInstituteId.value ?: return@LaunchedEffect
        InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
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
                val canSave = !loadingLedger && !isSaving && collecting > 0.0 && if (showDueSelector) {
                    selectedDue != null
                } else {
                    feePeriod.isNotBlank() && base > 0.0 && discountPercent in 0.0..100.0
                }

                val lockedMonthIndices = remember(selectedStudent?.id, selectedBatchId, studentAllFees) {
                    if ((selectedBatch?.monthlyFeeAmount ?: 0.0) <= 0.0) return@remember emptySet<Int>()
                    monthOptions.mapIndexedNotNull { idx, my ->
                        val existing = studentAllFees.firstOrNull { f ->
                            f.studentId == selectedStudent?.id && f.batchId == selectedBatchId &&
                                f.feePeriod.equals(my.label, ignoreCase = true) && f.cancelledAtMs == null
                        }
                        val paid = existing?.paidAmount ?: 0.0
                        val required = existing?.totalAmount
                            ?: requiredMonthlyAmount(selectedBatch, my.label)
                        if (paid >= required && paid > 0.0) idx else null
                    }.toSet()
                }

                LaunchedEffect(showDueSelector, baseAmount, startMonthIdx, endMonthIdx, selectedBatchId, studentAllFees, isPartialPayment) {
                    if (!showDueSelector && !isPartialPayment) {
                        val realStart = minOf(startMonthIdx, endMonthIdx)
                        val realEnd = maxOf(startMonthIdx, endMonthIdx)
                        var outstandingTotal = 0.0
                        for (i in realStart..realEnd) {
                            val ml = monthOptions[i].label
                            val existing = studentAllFees.firstOrNull { f ->
                                f.studentId == student.id && f.batchId == selectedBatchId &&
                                    f.feePeriod.equals(ml, ignoreCase = true) && f.cancelledAtMs == null
                            }
                            val paid = existing?.paidAmount ?: 0.0
                            val required = existing?.totalAmount
                                ?: requiredMonthlyAmount(selectedBatch, ml)
                            outstandingTotal += (required - paid).coerceAtLeast(0.0)
                        }
                        collectAmount = amountText((outstandingTotal - (outstandingTotal * discountPercent / 100.0)).coerceAtLeast(0.0))
                    }
                }

                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        val hasAdmissionDue = studentDues.any { it.fee.feeType == "admission_fee" }
                        StudentLedgerHeader(
                            student = student,
                            totalDue = totalDue,
                            totalPaid = totalPaid,
                            paymentCount = paymentHistory.size,
                            hasAdmissionFeeDue = hasAdmissionDue,
                            onAdmissionFeeClick = {
                                val admissionDue = studentDues.firstOrNull { it.fee.feeType == "admission_fee" }
                                if (admissionDue != null) {
                                    showDueSelector = true
                                    applyDueFeeSelection(admissionDue)
                                }
                            }
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
                                onPrint = { item -> printHistoryReceipt(context, instituteInfo, student, item) },
                                onWhatsApp = { item -> sendHistoryReceiptWhatsApp(context, instituteInfo, student, student.phone, item) },
                                onMessage = { item -> sendHistoryReceiptMessage(context, student.phone, buildHistoryReceiptText(instituteInfo, student, item)) },
                                onShare = { item -> shareHistoryReceipt(context, buildHistoryReceiptText(instituteInfo, student, item)) },
                                onEdit = { item -> editingHistoryItem = item }
                            )
                        }

                        item {
                            // Action toggle: Due Fee vs New Fee
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (studentDues.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = {
                                            showDueSelector = true
                                            val due = studentDues.first()
                                            applyDueFeeSelection(due)
                                        },
                                        modifier = Modifier.weight(1f).height(38.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, if (showDueSelector) AccentRed.copy(alpha = 0.6f) else BorderSub)
                                    ) {
                                        Text("Pending Fees", color = if (showDueSelector) AccentRed else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { resetFormForNewFee() },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (!showDueSelector) Cyan.copy(alpha = 0.6f) else BorderSub)
                                ) {
                                    Text("New Fee", color = if (!showDueSelector) Cyan else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        if (showDueSelector) {
                            item {
                                ExistingDueSelector(
                                    dues = studentDues,
                                    selectedDueId = selectedDue?.fee?.id,
                                    onSelect = { due -> applyDueFeeSelection(due) }
                                )
                            }
                            // Discount for admission / one-time due fees
                            if (selectedDue?.fee?.feeType == "admission_fee" || selectedDue?.fee?.feeType == "advance_fee") {
                                item {
                                    val dueFee = selectedDue!!.fee
                                    val discPercent = discountPercent
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text("Discount", color = TextMuted, fontSize = 13.sp, modifier = Modifier.width(70.dp))
                                        Card(
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(containerColor = CardBg),
                                            border = BorderStroke(1.dp, BorderSub)
                                        ) {
                                            OutlinedTextField(
                                                value = if (discountPercent == 0.0) "" else "%.0f".format(discountPercent),
                                                onValueChange = { s ->
                                                    val dp = s.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 0.0
                                                    discountPercent = dp
                                                    collectAmount = amountText((dueFee.totalAmount - (dueFee.totalAmount * dp / 100.0)).coerceAtLeast(0.0))
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true,
                                                placeholder = { Text("%", color = TextMuted.copy(alpha = 0.5f)) },
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedTextColor = TextWhite,
                                                    unfocusedTextColor = TextWhite,
                                                    focusedBorderColor = Color.Transparent,
                                                    unfocusedBorderColor = Color.Transparent
                                                ),
                                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            item {
                                NewFeeForm(
                                    batches = studentBatches,
                                    selectedBatchId = selectedBatchId,
                                    startMonthIdx = startMonthIdx,
                                    endMonthIdx = endMonthIdx,
                                    monthOptions = monthOptions,
                                    feeType = autoFeeType,
                                    baseAmount = baseAmount,
                                    calculatedBase = calcNewFeeBase(selectedBatch),
                                    discountPercent = discountPercent,
                                    admissionDateMs = studentEnrollments
                                        .firstOrNull { it.batchId == selectedBatchId }
                                        ?.joinedAtMs
                                        ?: selectedStudent?.admissionDateMs
                                        ?: 0L,
                                    lockedMonthIndices = lockedMonthIndices,
                                    onBatchSelected = { batch ->
                                        selectedBatchId = batch?.id
                                        val nextBase = calcNewFeeBase(batch)
                                        val realStart = minOf(startMonthIdx, endMonthIdx)
                                        val realEnd = maxOf(startMonthIdx, endMonthIdx)
                                        var outstandingTotal = 0.0
                                        for (i in realStart..realEnd) {
                                            val ml = monthOptions[i].label
                                            val existing = studentAllFees.firstOrNull { f ->
                                                f.studentId == student.id && f.batchId == selectedBatchId &&
                                                    f.feePeriod.equals(ml, ignoreCase = true) && f.cancelledAtMs == null
                                            }
                                            val paid = existing?.paidAmount ?: 0.0
                                            val required = existing?.totalAmount
                                                ?: requiredMonthlyAmount(batch, ml)
                                            outstandingTotal += (required - paid).coerceAtLeast(0.0)
                                        }
                                        baseAmount = amountText(nextBase)
                                        collectAmount = amountText(outstandingTotal)
                                    },
                                    onStartMonthChanged = { idx ->
                                        startMonthIdx = minOf(idx, endMonthIdx)
                                        if (idx > endMonthIdx) endMonthIdx = idx
                                        feePeriod = buildFeePeriodLabel(startMonthIdx, endMonthIdx, monthOptions)
                                        // Recalculate outstanding-based amount
                                        val realStart = minOf(startMonthIdx, endMonthIdx)
                                        val realEnd = maxOf(startMonthIdx, endMonthIdx)
                                        var outstandingTotal = 0.0
                                        val rawBase = calcNewFeeBase(selectedBatch)
                                        for (i in realStart..realEnd) {
                                            val ml = monthOptions[i].label
                                            val existing = studentAllFees.firstOrNull { f ->
                                                f.studentId == student.id && f.batchId == selectedBatchId &&
                                                    f.feePeriod.equals(ml, ignoreCase = true) && f.cancelledAtMs == null
                                            }
                                            val paid = existing?.paidAmount ?: 0.0
                                            val required = existing?.totalAmount
                                                ?: requiredMonthlyAmount(selectedBatch, ml)
                                            outstandingTotal += (required - paid).coerceAtLeast(0.0)
                                        }
                                        baseAmount = amountText(rawBase)
                                        collectAmount = amountText(outstandingTotal)
                                    },
                                    onEndMonthChanged = { idx ->
                                        if (idx < startMonthIdx) startMonthIdx = idx
                                        endMonthIdx = kotlin.math.max(startMonthIdx, idx)
                                        feePeriod = buildFeePeriodLabel(startMonthIdx, endMonthIdx, monthOptions)
                                        val realStart = minOf(startMonthIdx, endMonthIdx)
                                        val realEnd = maxOf(startMonthIdx, endMonthIdx)
                                        var outstandingTotal = 0.0
                                        val rawBase = calcNewFeeBase(selectedBatch)
                                        for (i in realStart..realEnd) {
                                            val ml = monthOptions[i].label
                                            val existing = studentAllFees.firstOrNull { f ->
                                                f.studentId == student.id && f.batchId == selectedBatchId &&
                                                    f.feePeriod.equals(ml, ignoreCase = true) && f.cancelledAtMs == null
                                            }
                                            val paid = existing?.paidAmount ?: 0.0
                                            val required = existing?.totalAmount
                                                ?: requiredMonthlyAmount(selectedBatch, ml)
                                            outstandingTotal += (required - paid).coerceAtLeast(0.0)
                                        }
                                        baseAmount = amountText(rawBase)
                                        collectAmount = amountText(outstandingTotal)
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
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        isPartialPayment = false
                                        if (showDueSelector) {
                                            val due = selectedDue?.fee ?: studentDues.firstOrNull()?.fee
                                            if (due != null) {
                                                val remaining = due.dueAmount.coerceAtLeast(0.0)
                                                collectAmount = amountText(remaining)
                                            }
                                        } else {
                                            val realStart = minOf(startMonthIdx, endMonthIdx)
                                            val realEnd = maxOf(startMonthIdx, endMonthIdx)
                                            var outstandingTotal = 0.0
                                            for (i in realStart..realEnd) {
                                                val ml = monthOptions[i].label
                                                val existing = studentAllFees.firstOrNull { f ->
                                                    f.studentId == student.id && f.batchId == selectedBatchId &&
                                                        f.feePeriod.equals(ml, ignoreCase = true) && f.cancelledAtMs == null
                                                }
                                                val paid = existing?.paidAmount ?: 0.0
                                                val required = existing?.totalAmount
                                                    ?: requiredMonthlyAmount(selectedBatch, ml)
                                                outstandingTotal += (required - paid).coerceAtLeast(0.0)
                                            }
                                            collectAmount = amountText((outstandingTotal - (outstandingTotal * discountPercent / 100.0)).coerceAtLeast(0.0))
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (!isPartialPayment) AccentGreen.copy(alpha = 0.6f) else BorderSub)
                                ) {
                                    Text("Full", color = if (!isPartialPayment) AccentGreen else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                                OutlinedButton(
                                    onClick = { isPartialPayment = true },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (isPartialPayment) AccentAmber.copy(alpha = 0.6f) else BorderSub)
                                ) {
                                    Text("Partial", color = if (isPartialPayment) AccentAmber else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        item {
                            PaymentInputCard(
                                isDuePayment = showDueSelector,
                                selectedDue = selectedDue,
                                payable = payable,
                                discountAmount = discountAmount,
                                collectAmount = collectAmount,
                                paymentMethod = paymentMethod,
                                note = note,
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
                                    if (showDueSelector && selectedDue == null) {
                                        collectError = "Select a due fee first."
                                        return@Button
                                    }
                                    if (!showDueSelector) {
                                        if (base <= 0.0 || feePeriod.isBlank()) {
                                            collectError = "Enter fee period and amount."
                                            return@Button
                                        }
                                        val realStart = minOf(startMonthIdx, endMonthIdx)
                                        val realEnd = maxOf(startMonthIdx, endMonthIdx)
                                        val months = numSelectedMonths()
                                        var totalOutstandingInRange = 0.0
                                        for (i in realStart..realEnd) {
                                            val monthLabel = monthOptions[i].label
                                            val existing = studentAllFees.firstOrNull { fee ->
                                                fee.studentId == student.id &&
                                                    fee.batchId == selectedBatchId &&
                                                    fee.feePeriod.equals(monthLabel, ignoreCase = true) &&
                                                    fee.cancelledAtMs == null
                                            }
                                            val paid = existing?.paidAmount ?: 0.0
                                            val required = existing?.totalAmount
                                                ?: requiredMonthlyAmount(selectedBatch, monthLabel)
                                            val remaining = (required - paid).coerceAtLeast(0.0)
                                            totalOutstandingInRange += remaining
                                        }
                                        if (collecting - totalOutstandingInRange > 0.001) {
                                            collectError = "Payment rejected - amount exceeds total outstanding in range."
                                            return@Button
                                        }
                                        if (months == 1 && totalOutstandingInRange <= 0.0) {
                                            collectError = "This month is already fully paid."
                                            return@Button
                                        }
                                    }

                                    scope.launch {
                                        isSaving = true
                                        try {
                                            val now = System.currentTimeMillis()
                                            // All allocations from one Save action share one trusted,
                                            // server-issued receipt number while retaining separate payments.
                                            val receiptGroupId = UUID.randomUUID().toString()
                                            val remainingDue = if (showDueSelector) {
                                                ((selectedDue?.fee?.dueAmount ?: 0.0) - collecting).coerceAtLeast(0.0)
                                            } else {
                                                (payable - collecting).coerceAtLeast(0.0)
                                            }
                                            val receiptText = buildCollectionReceiptText(
                                                instituteName = instituteInfo.name,
                                                institutePhone = instituteInfo.phone,
                                                student = student,
                                                batchName = selectedBatch?.name,
                                                period = if (showDueSelector) selectedDue?.fee?.feePeriod ?: feePeriod else feePeriod.trim(),
                                                mode = autoFeeType,
                                                payableAmount = if (showDueSelector) selectedDue?.fee?.totalAmount ?: 0.0 else payable,
                                                discountAmount = if (showDueSelector) selectedDue?.fee?.discountAmount ?: 0.0 else discountAmount,
                                                collectedAmount = collecting,
                                                remainingDue = remainingDue,
                                                paymentMethod = paymentMethod
                                            )
                                            val receiptNumber = if (showDueSelector) {
                                                val dueFee = selectedDue!!.fee
                                                if (dueFee.id.isBlank()) {
                                                    // Virtual fee — create it first with discount support
                                                    val discAmount = (
                                                        kotlin.math.round(dueFee.totalAmount * discountPercent) / 100.0
                                                    ).coerceAtLeast(0.0)
                                                    val createResult = feeRepository.createFeeWithInitialPayment(
                                                        instituteId = instId,
                                                        collectedByUserId = userId,
                                                        studentId = student.id,
                                                        batchId = dueFee.batchId,
                                                        feePeriod = dueFee.feePeriod,
                                                        feeType = dueFee.feeType.ifBlank { "admission_fee" },
                                                        dueDateMs = now,
                                                        baseAmount = dueFee.totalAmount,
                                                        discountAmount = discAmount,
                                                        lateFeeAmount = 0.0,
                                                        collectedAmount = collecting,
                                                        paymentMethod = paymentMethod,
                                                        paymentDateMs = now,
                                                        note = note.ifBlank { null },
                                                        receiptText = receiptText,
                                                        receiptGroupId = receiptGroupId,
                                                        now = now
                                                    )
                                                    createResult.receiptNumber ?: "payment"
                                                } else {
                                                    feeRepository.collectPayment(
                                                        instituteId = instId,
                                                        collectedByUserId = userId,
                                                        feeId = dueFee.id,
                                                        amount = collecting,
                                                        paymentMethod = paymentMethod,
                                                        note = note.ifBlank { null },
                                                        receiptText = receiptText,
                                                        receiptGroupId = receiptGroupId,
                                                        now = now
                                                    ).receiptNumber
                                                }
                                            } else {
                                                val months = numSelectedMonths()
                                                val realStart = minOf(startMonthIdx, endMonthIdx)
                                                val realEnd = maxOf(startMonthIdx, endMonthIdx)
                                                var receiptNumber: String? = null
                                                if (months > 1) {
                                                    var remainingPayment = collecting
                                                    // Distribute discount proportionally per month
                                                    for (i in realStart..realEnd) {
                                                        if (remainingPayment <= 0.0) break
                                                        val monthLabel = monthOptions[i].label
                                                        val existingFee = studentAllFees.firstOrNull { fee ->
                                                            fee.studentId == student.id &&
                                                                fee.batchId == selectedBatchId &&
                                                                fee.feePeriod.equals(monthLabel, ignoreCase = true) &&
                                                                fee.cancelledAtMs == null
                                                        }
                                                        val monthlyAmount = requiredMonthlyAmount(selectedBatch, monthLabel)
                                                        val paidSoFar = existingFee?.paidAmount ?: 0.0
                                                        val discountForMonth = kotlin.math.round(
                                                            monthlyAmount * discountPercent
                                                        ) / 100.0
                                                        val monthTotal = (monthlyAmount - discountForMonth).coerceAtLeast(0.0)
                                                        val remainingThisMonth = (monthTotal - paidSoFar).coerceAtLeast(0.0)
                                                        val monthPayment = minOf(remainingPayment, remainingThisMonth)
                                                        if (monthPayment <= 0.0) continue
                                                        if (existingFee != null) {
                                                            val result = feeRepository.collectPayment(
                                                                instituteId = instId,
                                                                collectedByUserId = userId,
                                                                feeId = existingFee.id,
                                                                amount = monthPayment,
                                                                paymentMethod = paymentMethod,
                                                                note = note.ifBlank { null },
                                                                receiptText = receiptText,
                                                                receiptGroupId = receiptGroupId,
                                                                now = now
                                                            )
                                                            receiptNumber = receiptNumber ?: result.receiptNumber
                                                        } else {
                                                            val result = feeRepository.createFeeWithInitialPayment(
                                                                instituteId = instId,
                                                                collectedByUserId = userId,
                                                                studentId = student.id,
                                                                batchId = selectedBatchId,
                                                                feePeriod = monthLabel,
                                                                feeType = "monthly_fee",
                                                                dueDateMs = now,
                                                                baseAmount = monthlyAmount,
                                                                discountAmount = discountForMonth,
                                                                lateFeeAmount = 0.0,
                                                                collectedAmount = monthPayment,
                                                                paymentMethod = paymentMethod,
                                                                paymentDateMs = now,
                                                                note = null,
                                                                receiptText = null,
                                                                receiptGroupId = receiptGroupId,
                                                                now = now
                                                            )
                                                            receiptNumber = receiptNumber ?: result.receiptNumber
                                                        }
                                                        remainingPayment -= monthPayment
                                                    }
                                                } else {
                                                    // Single-month path — check for existing fee first
                                                    val existingFee = studentAllFees.firstOrNull { fee ->
                                                        fee.studentId == student.id &&
                                                            fee.batchId == selectedBatchId &&
                                                            fee.feePeriod.equals(feePeriod.trim(), ignoreCase = true) &&
                                                            fee.cancelledAtMs == null
                                                    }
                                                    if (existingFee != null) {
                                                        val result = feeRepository.collectPayment(
                                                            instituteId = instId,
                                                            collectedByUserId = userId,
                                                            feeId = existingFee.id,
                                                            amount = collecting,
                                                            paymentMethod = paymentMethod,
                                                            note = note.ifBlank { null },
                                                            receiptText = receiptText,
                                                            receiptGroupId = receiptGroupId,
                                                            now = now
                                                        )
                                                        receiptNumber = result.receiptNumber
                                                    } else {
                                                        val result = feeRepository.createFeeWithInitialPayment(
                                                            instituteId = instId,
                                                            collectedByUserId = userId,
                                                            studentId = student.id,
                                                            batchId = selectedBatchId,
                                                            feePeriod = feePeriod.trim(),
                                                            feeType = if (autoFeeType == "Advance Fee") "advance_fee" else "monthly_fee",
                                                            dueDateMs = now,
                                                            baseAmount = base,
                                                            discountAmount = kotlin.math.round(discountAmount * 100.0) / 100.0,
                                                            lateFeeAmount = 0.0,
                                                            collectedAmount = collecting,
                                                            paymentMethod = paymentMethod,
                                                            paymentDateMs = now,
                                                            note = note.ifBlank { null },
                                                            receiptText = receiptText,
                                                            receiptGroupId = receiptGroupId,
                                                            now = now
                                                        )
                                                        receiptNumber = result.receiptNumber
                                                    }
                                                }
                                                receiptNumber ?: "payment"
                                            }
                                            snackbarHostState.showSnackbar("Payment saved: $receiptNumber")
                                            note = ""
                                            loadStudentLedger(student)
                                        } catch (e: FinancialOperationPendingException) {
                                            collectError = e.message ?: "Payment is pending reconciliation. Do not retry it."
                                        } catch (e: IllegalArgumentException) {
                                            collectError = e.message ?: "Payment rejected."
                                        } catch (e: Exception) {
                                            collectError = "Payment failed. Please try again."
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
        PaymentEditDialog(
            item = item,
            isSubmitting = isSaving,
            onDismiss = { editingHistoryItem = null },
            onSave = { update ->
                if (student == null) return@PaymentEditDialog
                scope.launch {
                    val instId = SessionManager.currentInstituteId.value
                    if (instId == null) {
                        snackbarHostState.showSnackbar("No active institute session.")
                        return@launch
                    }
                    try {
                        isSaving = true
                        feeRepository.ownerEditPayment(
                            paymentId = item.payment.id,
                            instituteId = instId,
                            amount = update.amount,
                            paymentMethod = update.paymentMethod,
                            paymentDateMs = update.paymentDateMs,
                            feePeriod = update.feePeriod,
                            note = update.note,
                            reason = update.reason
                        )
                        editingHistoryItem = null
                        loadStudentLedger(student)
                        snackbarHostState.showSnackbar("Payment updated successfully.")
                    } catch (e: FinancialOperationPendingException) {
                        snackbarHostState.showSnackbar("Payment update is pending. Do not submit it again.")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(e.message ?: "Could not update this payment.")
                    } finally {
                        isSaving = false
                    }
                }
            },
            onCancelPayment = {
                editingHistoryItem = null
                reversingHistoryItem = item
            },
            onDelete = {
                editingHistoryItem = null
                deletingHistoryItem = item
            }
        )
    }

    reversingHistoryItem?.let { item ->
        val student = selectedStudent
        PaymentReversalDialog(
            item = item,
            isSubmitting = isSaving,
            onDismiss = { reversingHistoryItem = null },
            onReverse = { reason ->
                if (student == null) return@PaymentReversalDialog
                scope.launch {
                    val instId = SessionManager.currentInstituteId.value
                    if (instId == null) {
                        snackbarHostState.showSnackbar("No active institute session.")
                        return@launch
                    }
                    try {
                        isSaving = true
                        val payment = item.payment
                        feeRepository.reversePayment(
                            paymentId = payment.id,
                            instituteId = instId,
                            reason = reason
                        )
                        reversingHistoryItem = null
                        loadStudentLedger(student)
                        snackbarHostState.showSnackbar("Payment cancelled. The original receipt is kept in history.")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(e.message ?: "Could not cancel this payment.")
                    } finally {
                        isSaving = false
                    }
                }
            }
        )
    }

    deletingHistoryItem?.let { item ->
        val student = selectedStudent
        PaymentPermanentDeleteDialog(
            item = item,
            isSubmitting = isSaving,
            onDismiss = { deletingHistoryItem = null },
            onDelete = { reason ->
                if (student == null) return@PaymentPermanentDeleteDialog
                scope.launch {
                    val instId = SessionManager.currentInstituteId.value
                    if (instId == null) {
                        snackbarHostState.showSnackbar("No active institute session.")
                        return@launch
                    }
                    try {
                        isSaving = true
                        feeRepository.ownerDeletePayment(
                            paymentId = item.payment.id,
                            instituteId = instId,
                            reason = reason
                        )
                        deletingHistoryItem = null
                        editingHistoryItem = null
                        loadStudentLedger(student)
                        snackbarHostState.showSnackbar("Payment permanently deleted.")
                    } catch (e: FinancialOperationPendingException) {
                        snackbarHostState.showSnackbar("Deletion is pending. Do not submit it again.")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(e.message ?: "Could not delete this payment.")
                    } finally {
                        isSaving = false
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
    paymentCount: Int,
    hasAdmissionFeeDue: Boolean = false,
    onAdmissionFeeClick: () -> Unit = {}
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
                if (hasAdmissionFeeDue) {
                    Button(
                        onClick = onAdmissionFeeClick,
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber.copy(alpha = 0.15f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Text("Admission Fee", color = AccentAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
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
    val currentUserRole by SessionManager.currentUserRole.collectAsState()
    val isFinancialOwner = currentUserRole in setOf("InstituteOwner", "SuperAdmin")
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
                            if (isFinancialOwner && item.payment.status == "completed") {
                                item {
                                    HistoryActionButton("Edit", Icons.Filled.Payments, Cyan) { onEdit(item) }
                                }
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

private data class PaymentCorrectionRequest(
    val amount: Double,
    val paymentMethod: String,
    val paymentDateMs: Long,
    val feePeriod: String,
    val note: String?,
    val reason: String
)

private fun monthOptionMatchesFeePeriod(option: UcMonthYear, feePeriod: String): Boolean {
    val periodStart = feePeriod.substringBefore(" - ").trim()
    val calendar = Calendar.getInstance().apply { set(option.year, option.month - 1, 1) }
    val longLabel = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(calendar.time)
    return option.label.equals(periodStart, ignoreCase = true) || longLabel.equals(periodStart, ignoreCase = true)
}

@Composable
private fun PaymentEditDialog(
    item: StudentPaymentHistory,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSave: (PaymentCorrectionRequest) -> Unit,
    onCancelPayment: () -> Unit,
    onDelete: () -> Unit
) {
    val monthOptions = remember { generateMonthOptions() }
    val initialMonthIndex = monthOptions.indexOfFirst { monthOptionMatchesFeePeriod(it, item.feePeriod) }
        .takeIf { it >= 0 } ?: monthOptions.indexOfFirst {
            it.label.equals(monthLabelForOffset(0), ignoreCase = true)
        }.coerceAtLeast(0)
    var amount by remember(item.payment.id) { mutableStateOf("%.2f".format(Locale.US, item.payment.amount)) }
    var paymentMethod by remember(item.payment.id) { mutableStateOf(item.payment.paymentMethod) }
    var paymentDate by remember(item.payment.id) { mutableStateOf(formatEditDate(item.payment.paymentDateMs)) }
    var selectedMonthIndex by remember(item.payment.id) { mutableIntStateOf(initialMonthIndex) }
    var note by remember(item.payment.id) { mutableStateOf(item.payment.note.orEmpty()) }
    var reason by remember(item.payment.id) { mutableStateOf("") }
    val validAmount = amount.toDoubleOrNull()?.takeIf { it > 0.0 }
    val validDate = parseEditDate(paymentDate)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.92f),
        containerColor = CardBg,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Text("Edit Payment", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(item.payment.receiptNumber, color = TextMuted, fontSize = 11.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Correct this saved payment. The receipt number stays the same.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
                SmartTextField(
                    value = amount,
                    onValueChange = { amount = moneyInput(it) },
                    placeholder = "Collected amount",
                    keyboardType = KeyboardType.Decimal,
                    leadingIcon = Icons.Filled.Payments
                )
                MonthPickerField(
                    label = "Payment month",
                    selectedIdx = selectedMonthIndex,
                    monthOptions = monthOptions,
                    onSelected = { selectedMonthIndex = it }
                )
                Text(
                    "Changing the month moves only this payment; other receipts stay unchanged.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                SmartTextField(
                    value = paymentDate,
                    onValueChange = { paymentDate = it },
                    placeholder = "Payment date (dd/MM/yyyy)"
                )
                Text("Payment method", color = TextMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("cash", "bkash", "nagad", "bank")) { option ->
                        FilterChip(
                            selected = paymentMethod == option,
                            onClick = { paymentMethod = option },
                            label = { Text(option.replaceFirstChar { it.uppercase() }) },
                            colors = smartChipColors()
                        )
                    }
                }
                SmartTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    placeholder = "Reason for change (required)"
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Button(
                    onClick = {
                        val selectedPeriod = if (selectedMonthIndex == initialMonthIndex) {
                            item.feePeriod
                        } else {
                            monthOptions.getOrNull(selectedMonthIndex)?.label ?: item.feePeriod
                        }
                        val correctedAmount = validAmount ?: return@Button
                        val correctedDate = validDate ?: return@Button
                        onSave(
                            PaymentCorrectionRequest(
                                amount = correctedAmount,
                                paymentMethod = paymentMethod,
                                paymentDateMs = correctedDate,
                                feePeriod = selectedPeriod,
                                note = note.trim().takeIf { it.isNotEmpty() },
                                reason = reason.trim()
                            )
                        )
                    },
                    enabled = !isSubmitting && validAmount != null && validDate != null && reason.trim().length >= 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                ) {
                    Text(
                        if (isSubmitting) "Saving..." else "Save Changes",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isSubmitting,
                        modifier = Modifier.weight(0.65f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                    ) {
                        Text("Close", color = TextMuted, fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = onCancelPayment,
                        enabled = !isSubmitting,
                        modifier = Modifier.weight(1.45f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                    ) {
                        Text("Cancel payment", color = AccentRed, fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = onDelete,
                        enabled = !isSubmitting,
                        modifier = Modifier.weight(0.62f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)
                    ) {
                        Text("Delete", color = AccentRed, fontSize = 12.sp)
                    }
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun PaymentPermanentDeleteDialog(
    item: StudentPaymentHistory,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit
) {
    var reason by remember(item.payment.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(20.dp),
        title = { Text("Delete Payment Permanently?", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This removes the payment and its receipt. The outstanding due will be recalculated. This cannot be undone.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBgAlt),
                    border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.45f))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.payment.receiptNumber, color = TextWhite, fontWeight = FontWeight.Bold)
                        Text("BDT ${formatSmartAmount(item.payment.amount)} • ${item.feePeriod}", color = TextMuted, fontSize = 12.sp)
                    }
                }
                SmartTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    placeholder = "Why are you deleting this payment?",
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onDelete(reason.trim()) },
                enabled = !isSubmitting && reason.trim().length >= 3,
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
            ) {
                Text(if (isSubmitting) "Deleting..." else "Delete Permanently", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Keep Payment", color = TextMuted) }
        }
    )
}

@Composable
private fun PaymentReversalDialog(
    item: StudentPaymentHistory,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onReverse: (String) -> Unit
) {
    var reason by remember(item.payment.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Text("Reverse / Cancel Payment", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(item.payment.receiptNumber, color = TextMuted, fontSize = 11.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Use this only when the full payment was entered by mistake. The old receipt will stay in history for safety.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardBgAlt),
                    border = BorderStroke(1.dp, BorderSub)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Amount: BDT ${formatSmartAmount(item.payment.amount)}", color = TextWhite)
                        Text("Period: ${item.feePeriod}", color = TextMuted, fontSize = 12.sp)
                        Text("Method: ${item.payment.paymentMethod}", color = TextMuted, fontSize = 12.sp)
                    }
                }
                SmartTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    placeholder = "Why are you cancelling this payment?",
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onReverse(reason.trim()) },
                enabled = !isSubmitting && reason.trim().length >= 3,
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
            ) {
                Text(if (isSubmitting) "Cancelling..." else "Cancel Payment", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep Payment", color = TextMuted) }
        }
    )
}

/* Retired P0-05 code kept only in source history; excluded from compilation.
@Suppress("unused")
@Composable
private fun EditPaymentDialog(
    item: StudentPaymentHistory,
    batches: List<BatchEntity>,
    onDismiss: () -> Unit,
    onSave: (PaymentEditRequest) -> Unit,
    onDelete: () -> Unit
) {
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val monthOptions = remember { generateMonthOptions() }

    var amount by remember(item.payment.id) { mutableStateOf("%.0f".format(item.payment.amount)) }
    var method by remember(item.payment.id) { mutableStateOf(item.payment.paymentMethod) }
    var note by remember(item.payment.id) { mutableStateOf(item.payment.note.orEmpty()) }
    var paymentDate by remember(item.payment.id) { mutableStateOf(formatEditDate(item.payment.paymentDateMs)) }
    var selectedBatchId by remember(item.payment.id) { mutableStateOf(item.batchId) }
    var baseAmountStr by remember(item.payment.id) { mutableStateOf("%.0f".format(item.baseAmount)) }
    var discountStr by remember(item.payment.id) { mutableStateOf("%.0f".format(item.discountAmount)) }
    val feePeriodIdx = remember(item.feePeriod) {
        monthOptions.indexOfFirst { it.label.equals(item.feePeriod, ignoreCase = true) }
            .let { if (it >= 0) it else monthOptions.indexOfFirst { m -> m.label == monthOptions[0].label } }
    }
    var selectedMonthIdx by remember(item.payment.id) { mutableIntStateOf(feePeriodIdx) }
    val selectedBatch = batches.firstOrNull { it.id == selectedBatchId }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val baseVal = baseAmountStr.toDoubleOrNull() ?: 0.0
    val discVal = discountStr.toDoubleOrNull() ?: 0.0
    val calcTotal = (baseVal - discVal).coerceAtLeast(0.0)
    val calcDue = (calcTotal - (amount.toDoubleOrNull() ?: 0.0)).coerceAtLeast(0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.94f),
        containerColor = CardBg,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Edit Payment", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(item.payment.receiptNumber, color = TextMuted, fontSize = 11.sp)
                }
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(AccentRed.copy(alpha = 0.12f)).clickable { showDeleteConfirm = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = AccentRed, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Base + Discount row
                Text("Fee Amount", color = TextMuted, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmartTextField(
                        value = baseAmountStr,
                        onValueChange = { baseAmountStr = moneyInput(it) },
                        placeholder = "Base",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f)
                    )
                    SmartTextField(
                        value = discountStr,
                        onValueChange = { discountStr = moneyInput(it) },
                        placeholder = "Discount",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f)
                    )
                }
                // Calculated summary
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total after discount", color = TextMuted, fontSize = 11.sp)
                        Text("BDT ${formatSmartAmount(calcTotal)}", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Collected amount
                Text("Collected", color = TextMuted, fontSize = 11.sp)
                SmartTextField(
                    value = amount,
                    onValueChange = { amount = moneyInput(it) },
                    placeholder = "Collected amount",
                    keyboardType = KeyboardType.Decimal,
                    leadingIcon = Icons.Filled.Payments
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("New due after edit", color = TextMuted, fontSize = 11.sp)
                    Text("BDT ${formatSmartAmount(calcDue)}", color = if (calcDue > 0.0) AccentRed else AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // Month picker
                Text("Fee Period", color = TextMuted, fontSize = 11.sp)
                MonthPickerField(
                    label = "Select month",
                    selectedIdx = selectedMonthIdx,
                    monthOptions = monthOptions,
                    onSelected = { idx ->
                        selectedMonthIdx = idx
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Date
                SmartTextField(
                    value = paymentDate,
                    onValueChange = { paymentDate = it },
                    placeholder = "Payment date (dd/MM/yyyy)"
                )

                // Batch
                Text("Batch", color = TextMuted, fontSize = 11.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(batches, key = { it.id }) { batch ->
                        FilterChip(
                            selected = selectedBatchId == batch.id,
                            onClick = { selectedBatchId = batch.id },
                            label = { Text(batch.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = smartChipColors()
                        )
                    }
                }

                // Payment method
                Text("Payment Method", color = TextMuted, fontSize = 11.sp)
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

                // Note
                SmartTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = "Note",
                    modifier = Modifier.heightIn(min = 44.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        PaymentEditRequest(
                            amount = amount.toDoubleOrNull() ?: 0.0,
                            method = method,
                            note = note,
                            feePeriod = monthOptions.getOrNull(selectedMonthIdx)?.label ?: item.feePeriod,
                            batchId = selectedBatchId,
                            batchName = selectedBatch?.name,
                            paymentDateMs = parseEditDate(paymentDate) ?: item.payment.paymentDateMs,
                            baseAmount = baseVal.ifZero(item.baseAmount),
                            discountAmount = discVal.ifZero(item.discountAmount)
                        )
                    )
                },
                modifier = Modifier.height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
            ) {
                Text("Save Changes", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        }
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            modifier = Modifier.fillMaxWidth(0.88f),
            containerColor = CardBg,
            shape = RoundedCornerShape(20.dp),
            icon = {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(AccentRed.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⚠", fontSize = 22.sp)
                }
            },
            title = {
                Text("Delete Payment", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "This action is permanent and cannot be undone.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBgAlt),
                        border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.3f))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Receipt: ${item.payment.receiptNumber}", color = TextWhite, fontSize = 12.sp)
                            Text("Amount: BDT ${formatSmartAmount(item.payment.amount)}", color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Period: ${item.feePeriod}", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                ) {
                    Text("Yes, Delete Permanently", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Keep Payment", color = TextMuted)
                }
            }
        )
    }
}

private fun Double.ifZero(fallback: Double): Double = if (this == 0.0) fallback else this
*/

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
            Text("Select Fee to Collect", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Exam, admission and monthly fees are listed here.", color = TextMuted, fontSize = 11.sp)
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
                            Text(
                                listOfNotNull(due.batchName, due.fee.feeType.toCollectionFeeLabel())
                                    .joinToString(" · "),
                                color = TextMuted, fontSize = 11.sp
                            )
                        }
                        Text(formatSmartAmount(due.fee.dueAmount), color = AccentRed, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

private fun String.toCollectionFeeLabel(): String = when (trim().lowercase(Locale.US)) {
    "exam_fee", "exam" -> "Exam fee"
    "admission_fee", "admission" -> "Admission fee"
    "advance_fee", "advance" -> "Advance fee"
    "monthly_fee", "monthly" -> "Monthly fee"
    else -> replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun NewFeeForm(
    batches: List<BatchEntity>,
    selectedBatchId: String?,
    startMonthIdx: Int = 0,
    endMonthIdx: Int = 0,
    monthOptions: List<UcMonthYear> = emptyList(),
    feeType: String,
    baseAmount: String,
    calculatedBase: Double,
    discountPercent: Double,
    admissionDateMs: Long = 0L,
    lockedMonthIndices: Set<Int> = emptySet(),
    onBatchSelected: (BatchEntity?) -> Unit,
    onStartMonthChanged: (Int) -> Unit,
    onEndMonthChanged: (Int) -> Unit,
    onBaseAmountChange: (String) -> Unit,
    onDiscountChange: (Double) -> Unit
) {
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val minAllowedMonthIdx = remember(admissionDateMs, startMonthIdx, endMonthIdx) {
        if (admissionDateMs <= 0L) null
        else {
            val admCal = Calendar.getInstance().apply { timeInMillis = admissionDateMs }
            monthOptions.indexOfFirst { it.year == admCal.get(Calendar.YEAR) && it.month == (admCal.get(Calendar.MONTH) + 1) }
                .takeIf { it >= 0 }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderSub)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$feeType Details", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)

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

            Text("Fee Period", color = TextMuted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MonthPickerField(
                    label = "Start Month",
                    selectedIdx = startMonthIdx,
                    monthOptions = monthOptions,
                    minAllowedIdx = minAllowedMonthIdx,
                    lockedIndices = lockedMonthIndices,
                    onSelected = onStartMonthChanged,
                    modifier = Modifier.weight(1f)
                )
                MonthPickerField(
                    label = "End Month",
                    selectedIdx = endMonthIdx,
                    monthOptions = monthOptions,
                    minAllowedIdx = if (startMonthIdx >= 0) startMonthIdx else null,
                    lockedIndices = lockedMonthIndices,
                    onSelected = onEndMonthChanged,
                    modifier = Modifier.weight(1f)
                )
            }
            val monthCount = (endMonthIdx - startMonthIdx + 1).coerceAtLeast(1)
            val startLabel = monthOptions.getOrNull(startMonthIdx)?.label ?: "—"
            val endLabel = monthOptions.getOrNull(endMonthIdx)?.label ?: "—"
            val monthlyFee = batches.firstOrNull { it.id == selectedBatchId }?.monthlyFeeAmount ?: 0.0

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBgAlt)
                    .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Selected period", color = TextMuted, fontSize = 11.sp)
                Text(
                    "$startLabel → $endLabel",
                    color = TextWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("$monthCount month${if (monthCount > 1) "s" else ""}", color = Cyan.copy(alpha = 0.7f), fontSize = 12.sp)
                    Text("·", color = TextMuted.copy(alpha = 0.4f), fontSize = 12.sp)
                    Text("Monthly fee", color = TextMuted, fontSize = 12.sp)
                    Text("BDT ${formatSmartAmount(monthlyFee)}", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
                Text(
                    "Base amount: BDT ${formatSmartAmount(calculatedBase)}",
                    color = Cyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

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
    isDuePayment: Boolean,
    selectedDue: EnrichedDue?,
    payable: Double,
    discountAmount: Double,
    collectAmount: String,
    paymentMethod: String,
    note: String,
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
                    label = if (isDuePayment) "Selected due" else "Payable amount",
                    value = formatSmartAmount(if (isDuePayment) dueAmount else payable),
                    color = if (isDuePayment) AccentRed else TextWhite
                )
                if (!isDuePayment && discountAmount > 0.0) {
                    SummaryLine("Discount", "-${formatSmartAmount(discountAmount)}", AccentGreen)
                }
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
private fun <T> PremiumMonthDropdown(
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
            label = { Text(label, color = TextMuted) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, tint = TextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
                focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                focusedBorderColor = Cyan, unfocusedBorderColor = BorderSub
            ),
            shape = RoundedCornerShape(12.dp)
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { expanded = !expanded }
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(CardBg).heightIn(max = 280.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), color = if (option == selectedOption) Cyan else TextWhite) },
                    onClick = { onOptionSelected(option); expanded = false }
                )
            }
        }
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

// ── Auto-detect fee type from selected months ──
private fun autoDetectFeeType(startIdx: Int, endIdx: Int, currentMonthIdx: Int): String {
    val safeStart = minOf(startIdx, endIdx)
    val safeEnd = maxOf(startIdx, endIdx)
    return when {
        safeEnd < currentMonthIdx -> "Due Fee"
        safeStart > currentMonthIdx -> "Advance Fee"
        safeStart == currentMonthIdx && safeEnd == currentMonthIdx -> "Running Month"
        safeStart <= currentMonthIdx && safeEnd >= currentMonthIdx -> "Mixed Period"
        else -> "Monthly Fee"
    }
}

// ═══════════════════════════════════════════════════════════════
//  MonthYearRangePicker — grid-based month picker with range support
// ═══════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthYearRangePicker(
    selectedStartIdx: Int,
    selectedEndIdx: Int,
    monthOptions: List<UcMonthYear>,
    currentMonthIdx: Int,
    onDismiss: () -> Unit,
    onConfirm: (startIdx: Int, endIdx: Int) -> Unit
) {
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val years = remember { (currentYear - 1..currentYear + 2).toList() }
    var viewYear by remember { mutableIntStateOf(monthOptions.getOrNull(selectedStartIdx)?.year ?: currentYear) }

    var tapStart by remember { mutableIntStateOf(selectedStartIdx) }
    var tapEnd by remember { mutableIntStateOf(selectedEndIdx) }
    var awaitingSecondTap by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = {
            Column {
                Text("Select Fee Period", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (viewYear > years.first()) viewYear -= 1 }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ChevronLeft, null, tint = TextMuted.copy(alpha = 0.7f))
                    }
                    Text("$viewYear", color = Cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { if (viewYear < years.last()) viewYear += 1 }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ChevronRight, null, tint = TextMuted.copy(alpha = 0.7f))
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in 0..2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (col in 0..3) {
                            val month = row * 4 + col + 1
                            val globalIdx = monthOptions.indexOfFirst { it.month == month && it.year == viewYear }
                            val effectiveStart = minOf(tapStart, tapEnd)
                            val effectiveEnd = maxOf(tapStart, tapEnd)
                            val inRange = globalIdx in effectiveStart..effectiveEnd
                            val isEdge = globalIdx == tapStart || globalIdx == tapEnd
                            val isPast = globalIdx in 0..<currentMonthIdx

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1.4f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        when {
                                            isEdge -> Cyan
                                            inRange -> Cyan.copy(alpha = 0.2f)
                                            else -> CardBgAlt
                                        }
                                    )
                                    .border(
                                        width = if (isEdge) 0.dp else 1.dp,
                                        color = when {
                                            globalIdx == currentMonthIdx -> Cyan.copy(alpha = 0.5f)
                                            isPast -> BorderSub.copy(alpha = 0.5f)
                                            else -> BorderSub
                                        },
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        if (awaitingSecondTap) {
                                            tapStart = globalIdx
                                            tapEnd = globalIdx
                                            awaitingSecondTap = false
                                        } else {
                                            if (globalIdx >= tapStart) {
                                                tapEnd = globalIdx
                                            } else {
                                                tapStart = globalIdx
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    names[month - 1],
                                    color = when {
                                        isEdge -> CardBg
                                        inRange -> Cyan
                                        isPast -> TextMuted.copy(alpha = 0.45f)
                                        else -> TextMuted
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = if (isEdge) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                val safeStart = minOf(tapStart, tapEnd)
                val safeEnd = maxOf(tapStart, tapEnd)
                val monthCount = (safeEnd - safeStart + 1).coerceAtLeast(1)
                val label = buildFeePeriodLabel(safeStart, safeEnd, monthOptions)
                val detectedType = autoDetectFeeType(safeStart, safeEnd, currentMonthIdx)

                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBgAlt),
                    border = BorderStroke(1.dp, BorderSub)
                ) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label.ifBlank { "No selection" }, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "$monthCount Month${if (monthCount > 1) "s" else ""} · $detectedType",
                            color = Cyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            val safeStart = minOf(tapStart, tapEnd)
            val safeEnd = maxOf(tapStart, tapEnd)
            TextButton(onClick = { onConfirm(safeStart, safeEnd) }) {
                Text("Confirm ✓", color = Cyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) } }
    )
}

@Composable
private fun MonthPickerField(
    label: String,
    selectedIdx: Int,
    monthOptions: List<UcMonthYear>,
    minAllowedIdx: Int? = null,
    lockedIndices: Set<Int> = emptySet(),
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    val selectedLabel = monthOptions.getOrNull(selectedIdx)?.label ?: "Select"

    Box(modifier = modifier) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, color = TextMuted, fontSize = 11.sp) },
            trailingIcon = {
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Filled.CalendarMonth, null, tint = Cyan.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextWhite,
                unfocusedTextColor = TextWhite,
                focusedContainerColor = CardBgAlt,
                unfocusedContainerColor = CardBgAlt,
                focusedBorderColor = Cyan.copy(alpha = 0.5f),
                unfocusedBorderColor = BorderSub,
                cursorColor = Cyan
            )
        )
    }

    if (showPicker) {
        MonthPickerDialog(
            selectedIdx = selectedIdx,
            monthOptions = monthOptions,
            minAllowedIdx = minAllowedIdx,
            lockedIndices = lockedIndices,
            onDismiss = { showPicker = false },
            onSelected = { idx ->
                onSelected(idx)
                showPicker = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthPickerDialog(
    selectedIdx: Int,
    monthOptions: List<UcMonthYear>,
    minAllowedIdx: Int? = null,
    lockedIndices: Set<Int> = emptySet(),
    onDismiss: () -> Unit,
    onSelected: (Int) -> Unit
) {
    val distinctYears = remember(monthOptions) { monthOptions.map { it.year }.distinct().sorted() }
    val initialYear = monthOptions.getOrNull(selectedIdx)?.year ?: distinctYears.firstOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
    var pickerYear by remember { mutableIntStateOf(initialYear) }
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val yearMonths = remember(pickerYear, monthOptions) {
        monthOptions.filter { it.year == pickerYear }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.92f),
        containerColor = CardBg,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = {
                    val prev = distinctYears.indexOf(pickerYear)
                    if (prev > 0) pickerYear = distinctYears[prev - 1]
                }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous year", tint = Cyan.copy(alpha = if (distinctYears.first() < pickerYear) 0.8f else 0.25f))
                }
                Text(
                    "$pickerYear",
                    color = TextWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = {
                    val next = distinctYears.indexOf(pickerYear)
                    if (next < distinctYears.lastIndex) pickerYear = distinctYears[next + 1]
                }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next year", tint = Cyan.copy(alpha = if (distinctYears.last() > pickerYear) 0.8f else 0.25f))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0..3) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (col in 0..2) {
                            val monthIdx = row * 3 + col
                            val item = yearMonths.getOrNull(monthIdx)
                            val globalIdx = if (item != null) monthOptions.indexOf(item) else -1
                            val isBeforeAdmission = minAllowedIdx != null && globalIdx >= 0 && globalIdx < minAllowedIdx
                            val isLocked = !isBeforeAdmission && globalIdx >= 0 && globalIdx in lockedIndices
                            val isDisabled = isBeforeAdmission || isLocked
                            val isSelected = globalIdx >= 0 && globalIdx == selectedIdx

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1.2f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .then(
                                        when {
                                            isSelected -> Modifier.background(
                                                Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                                            )
                                            isDisabled -> Modifier.background(CardBgAlt.copy(alpha = 0.4f))
                                            else -> Modifier.background(CardBgAlt).border(1.dp, BorderSub, RoundedCornerShape(12.dp))
                                        }
                                    )
                                    .then(if (!isDisabled && item != null) Modifier.clickable {
                                        onSelected(globalIdx)
                                    } else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                if (item != null) {
                                    Text(
                                        monthNames[item.month - 1],
                                        color = when {
                                            isSelected -> Color.White
                                            isDisabled -> TextMuted.copy(alpha = 0.35f)
                                            else -> TextWhite.copy(alpha = 0.85f)
                                        },
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        }
    )
}

private data class UcMonthYear(val month: Int, val year: Int, val label: String)

private fun generateMonthOptions(): List<UcMonthYear> {
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    return (currentYear - 1..currentYear + 2).flatMap { year ->
        (1..12).map { month -> UcMonthYear(month, year, "${names[month - 1]} $year") }
    }
}

private fun buildFeePeriodLabel(startIdx: Int, endIdx: Int, options: List<UcMonthYear>): String {
    if (startIdx == endIdx) return options.getOrNull(startIdx)?.label ?: ""
    val s = options.getOrNull(startIdx)?.label ?: return ""
    val e = options.getOrNull(endIdx)?.label ?: return ""
    return "$s - $e"
}

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

private fun buildCollectionReceiptText(
    instituteName: String,
    institutePhone: String,
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
    appendLine(instituteName)
    appendLine("PAYMENT RECEIPT")
    appendLine("________________________________")
    appendLine()
    appendLine("Student : ${student.fullName}")
    appendLine("ID      : ${student.studentCode}")
    appendLine("Batch   : ${batchName ?: "Direct"}")
    appendLine("Type    : $mode")
    appendLine("Period  : $period")
    appendLine("________________________________")
    appendLine("Payable     : BDT ${formatSmartAmount(payableAmount)}")
    if (discountAmount > 0.0) {
        val pct = if (payableAmount + discountAmount > 0.0) (discountAmount / (payableAmount + discountAmount) * 100.0) else 0.0
        val pctStr = if (pct % 1.0 == 0.0) "%.0f".format(pct) else "%.1f".format(pct)
        appendLine("Discount    : ${pctStr}% - BDT ${formatSmartAmount(discountAmount)}")
    }
    appendLine("Collected   : BDT ${formatSmartAmount(collectedAmount)}")
    appendLine("Due         : BDT ${formatSmartAmount(remainingDue)}")
    appendLine("Method      : ${paymentMethod.uppercase()}")
    appendLine("Date        : ${formatDate(System.currentTimeMillis())}")
    appendLine("________________________________")
    appendLine("Contact : $institutePhone")
    appendLine("Thank you, $instituteName")
}

private fun buildHistoryReceiptText(institute: InstituteInfo, student: StudentEntity, item: StudentPaymentHistory): String =
    buildString {
        appendLine(institute.name)
        appendLine("PAYMENT RECEIPT")
        appendLine("Receipt : ${item.payment.receiptNumber}")
        appendLine("________________________________")
        appendLine()
        appendLine("Student : ${student.fullName}")
        appendLine("ID      : ${student.studentCode}")
        appendLine("Batch   : ${item.batchName ?: "Direct"}")
        appendLine("Period  : ${item.feePeriod}")
        appendLine("Date    : ${formatDate(item.payment.paymentDateMs)}")
        appendLine("________________________________")
        appendLine("Fee Amount  : BDT ${formatSmartAmount(item.baseAmount)}")
        if (item.discountAmount > 0.0) {
            appendLine("Discount    : ${formatDiscountPercent(item)}% - BDT ${formatSmartAmount(item.discountAmount)}")
        }
        appendLine("Payable     : BDT ${formatSmartAmount(item.totalAmount)}")
        appendLine("Collected   : BDT ${formatSmartAmount(item.payment.amount)}")
        appendLine("Due         : BDT ${formatSmartAmount(item.remainingDue)}")
        appendLine("Method      : ${item.payment.paymentMethod.uppercase()}")
        item.payment.note?.takeIf { it.isNotBlank() }?.let { appendLine("Note        : $it") }
        appendLine("________________________________")
        appendLine("Contact : ${institute.phone}")
        appendLine("Thank you, ${institute.name}")
    }

private data class InstituteInfo(val name: String, val phone: String, val logoText: String, val logoUri: String? = null)

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

private fun sendHistoryReceiptWhatsApp(context: Context, institute: InstituteInfo, student: StudentEntity, phone: String?, item: StudentPaymentHistory) {
    try {
        val file = generateReceiptPdf(context, institute, student, item)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val cleanPhone = phone.orEmpty().replace("+", "").replace(" ", "").replace("-", "")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Payment Receipt - ${student.fullName}")
        }
        try {
            intent.`package` = "com.whatsapp"
            context.startActivity(intent)
        } catch (_: Exception) {
            val waIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(if (cleanPhone.isBlank()) "https://wa.me/" else "https://wa.me/$cleanPhone")
            }
            context.startActivity(waIntent)
            Toast.makeText(context, "Please attach the PDF manually from your files.", Toast.LENGTH_LONG).show()
        }
    } catch (_: Exception) {
        val receiptText = buildHistoryReceiptText(institute, student, item)
        val cleanPhone = phone.orEmpty().replace("+", "").replace(" ", "").replace("-", "")
        val encoded = URLEncoder.encode(receiptText, "UTF-8")
        val url = if (cleanPhone.isBlank()) "https://wa.me/?text=$encoded" else "https://wa.me/$cleanPhone?text=$encoded"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun printHistoryReceipt(context: Context, institute: InstituteInfo, student: StudentEntity, item: StudentPaymentHistory) {
    try {
        val file = generateReceiptPdf(context, institute, student, item)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    } catch (e: Exception) {
        val receiptText = buildHistoryReceiptText(institute, student, item)
        Toast.makeText(context, "Could not open receipt PDF. Sharing text instead.", Toast.LENGTH_SHORT).show()
        shareHistoryReceipt(context, receiptText)
    }
}

private fun generateReceiptPdf(context: Context, institute: InstituteInfo, student: StudentEntity, item: StudentPaymentHistory): File {
    val document = PdfDocument()
    val hasDiscount = item.discountAmount > 0.0
    val hasRemark = !item.payment.note.isNullOrBlank()

    val pageWidth = 440f
    val headerH = 125f
    val studentH = 72f
    val feeH = if (hasDiscount) 116f else 92f
    val paymentH = 92f
    val remarkH = if (hasRemark) 54f else 0f
    val sigH = 46f
    val footerH = 56f
    val gap = 14f

    val totalHeight = (headerH + gap + studentH + gap + feeH + gap + paymentH +
            (if (hasRemark) gap + remarkH else 0f) + gap + sigH + gap + footerH).toInt()

    val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth.toInt(), totalHeight, 1).create())
    val canvas = page.canvas
    val right = pageWidth - 28f

    val ink = AndroidColor.rgb(20, 27, 38)
    val muted = AndroidColor.rgb(101, 116, 139)
    val blue = AndroidColor.rgb(37, 99, 235)
    val cyan = AndroidColor.rgb(14, 165, 233)
    val green = AndroidColor.rgb(16, 185, 129)
    val red = AndroidColor.rgb(239, 68, 68)
    val softBlue = AndroidColor.rgb(239, 246, 255)
    val softLine = AndroidColor.rgb(226, 232, 240)
    val pale = AndroidColor.rgb(248, 250, 252)

    val fill = Paint().apply { style = Paint.Style.FILL }
    val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = softLine }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 11f }
    val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 12f; isFakeBoldText = true }
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; textSize = 13f; isFakeBoldText = true }
    val large = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue; textSize = 22f; isFakeBoldText = true }

    canvas.drawColor(AndroidColor.WHITE)

    // ── Load logo image ──
    val logoBitmap: Bitmap? = FirebaseStorageImageUploadHelper.displaySource(context, institute.logoUri)?.let { source ->
        try {
            val uri = Uri.parse(source)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    val connection = (java.net.URL(source).openConnection() as java.net.HttpURLConnection).apply {
                        doInput = true
                        connectTimeout = 5_000
                        readTimeout = 5_000
                    }
                    try {
                        connection.inputStream.use(BitmapFactory::decodeStream)
                    } finally {
                        connection.disconnect()
                    }
                }
                else -> context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Watermark (logo image or text, center, very faded) ──
    if (logoBitmap != null) {
        val wmSize = (totalHeight * 0.40f).toInt()
        val wmScaled = Bitmap.createScaledBitmap(logoBitmap, wmSize, wmSize, true)
        val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 12; isFilterBitmap = true }
        canvas.drawBitmap(wmScaled, pageWidth / 2f - wmSize / 2f, (totalHeight - wmSize) / 2f, wmPaint)
    }

    // ── Page border ──
    fill.color = pale
    canvas.drawRoundRect(RectF(16f, 12f, right, totalHeight - 12f), 18f, 18f, fill)
    canvas.drawRoundRect(RectF(16f, 12f, right, totalHeight - 12f), 18f, 18f, stroke)

    // ── Header ──
    fill.color = blue
    canvas.drawRoundRect(RectF(16f, 12f, right, 12f + headerH), 18f, 18f, fill)
    // Corner accents
    fill.color = AndroidColor.argb(30, 255, 255, 255)
    canvas.drawCircle(right - 27f, 28f, 48f, fill)
    canvas.drawCircle(right - 57f, 70f, 28f, fill)
    fill.color = cyan
    canvas.drawCircle(right - 17f, 120f, 14f, fill)

    // Logo / initials
    val logoX = 32f; val logoY = 28f; val logoSize = 44f
    if (logoBitmap != null) {
        fill.color = AndroidColor.WHITE
        canvas.drawCircle(logoX + logoSize / 2f, logoY + logoSize / 2f, logoSize / 2f, fill)
        // Clip to circle
        val saved = canvas.saveLayer(RectF(logoX, logoY, logoX + logoSize, logoY + logoSize), null)
        val circle = android.graphics.Path().apply {
            addCircle(logoX + logoSize / 2f, logoY + logoSize / 2f, logoSize / 2f - 1f, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(circle)
        val bmp = Bitmap.createScaledBitmap(logoBitmap, (logoSize - 2).toInt(), (logoSize - 2).toInt(), true)
        canvas.drawBitmap(bmp, logoX + 1f, logoY + 1f, null)
        canvas.restoreToCount(saved ?: canvas.saveCount)
    } else {
        fill.color = AndroidColor.WHITE
        canvas.drawCircle(logoX + logoSize / 2f, logoY + logoSize / 2f, logoSize / 2f, fill)
        bold.color = blue; bold.textSize = 16f
        canvas.drawText(institute.logoText.take(2).uppercase(), logoX + 12f, logoY + 28f, bold)
    }

    white.textSize = 16f
    val nameX = logoX + logoSize + 14f
    canvas.drawText(pdfSafe(institute.name, 28), nameX, logoY + 22f, white)
    white.textSize = 10f
    canvas.drawText("PAYMENT RECEIPT", nameX, logoY + 40f, white)
    text.textSize = 9.5f; text.color = AndroidColor.argb(200, 255, 255, 255)
    text.textAlign = Paint.Align.RIGHT
    canvas.drawText(item.payment.receiptNumber, right - 12f, logoY + 12f, text)
    canvas.drawText(formatEditDate(item.payment.paymentDateMs), right - 12f, logoY + 28f, text)
    canvas.drawText("Contact: ${institute.phone}", right - 12f, logoY + 44f, text)
    text.textAlign = Paint.Align.LEFT

    // ── Sections ──
    var y = 12f + headerH + gap  // y = 149

    // Student info
    card(canvas, fill, stroke, 28f, y, right, studentH)
    bold.color = ink; bold.textSize = 16f; bold.textAlign = Paint.Align.LEFT
    canvas.drawText(pdfSafe(student.fullName, 28), 46f, y + 22f, bold)
    text.color = muted; text.textSize = 10f; text.textAlign = Paint.Align.LEFT
    val row1 = y + 44f
    canvas.drawText("${student.studentCode}  ·  ${student.phone ?: student.guardianPhone ?: ""}", 46f, row1, text)
    val guardian = student.guardianName?.takeIf { it.isNotBlank() }
    if (guardian != null) {
        canvas.drawText("Guardian: ${pdfSafe(guardian, 22)}", 46f, row1 + 14f, text)
        canvas.drawText("Batch: ${pdfSafe(item.batchName ?: "Direct", 22)}", 260f, row1 + 14f, text)
    } else {
        canvas.drawText("Batch: ${pdfSafe(item.batchName ?: "Direct", 26)}", 46f, row1 + 14f, text)
    }
    y += studentH + gap

    // Fee details
    card(canvas, fill, stroke, 28f, y, right, feeH)
    sectionLabel(canvas, text, "FEE DETAILS", 46f, y + 16f)
    row(canvas, text, bold, "Period", item.feePeriod, 46f, y + 36f, right - 28f)
    row(canvas, text, bold, "Fee amount", "BDT ${formatSmartAmount(item.baseAmount)}", 46f, y + 56f, right - 28f)
    if (hasDiscount) {
        row(canvas, text, bold, "Discount", "−BDT ${formatSmartAmount(item.discountAmount)}", 46f, y + 76f, right - 28f, green)
        row(canvas, text, bold, "Payable", "BDT ${formatSmartAmount(item.totalAmount)}", 46f, y + 96f, right - 28f)
    } else {
        row(canvas, text, bold, "Payable", "BDT ${formatSmartAmount(item.totalAmount)}", 46f, y + 76f, right - 28f)
    }
    y += feeH + gap

    // Payment
    card(canvas, fill, stroke, 28f, y, right, paymentH)
    sectionLabel(canvas, text, "COLLECTED", 46f, y + 16f)
    large.textAlign = Paint.Align.RIGHT; large.color = blue
    canvas.drawText("BDT ${formatSmartAmount(item.payment.amount)}", right - 28f, y + 18f, large)
    large.textAlign = Paint.Align.LEFT
    stroke.color = AndroidColor.argb(50, 37, 99, 235); stroke.strokeWidth = 0.7f
    canvas.drawLine(46f, y + 38f, right - 28f, y + 38f, stroke)
    stroke.color = softLine; stroke.strokeWidth = 1f
    val dueColor = if (item.remainingDue > 0.0) red else green
    row(canvas, text, bold, "Remaining due", "BDT ${formatSmartAmount(item.remainingDue)}", 46f, y + 56f, right - 28f, dueColor)
    row(canvas, text, bold, "Method", item.payment.paymentMethod.replaceFirstChar { it.uppercase() }, 46f, y + 74f, right - 28f)
    y += paymentH + gap

    // Remark
    if (hasRemark) {
        fill.color = softBlue
        canvas.drawRoundRect(RectF(28f, y, right, y + remarkH), 12f, 12f, fill)
        text.color = muted; text.textSize = 10f; text.textAlign = Paint.Align.LEFT
        canvas.drawText("Remark: ${pdfSafe(item.payment.note ?: "", 46)}", 46f, y + 28f, text)
        y += remarkH + gap
    }

    // Signature
    stroke.strokeWidth = 1f; stroke.color = softLine
    canvas.drawLine(36f, y + 6f, pageWidth / 2f - 32f, y + 6f, stroke)
    canvas.drawLine(pageWidth / 2f + 12f, y + 6f, right, y + 6f, stroke)
    text.color = muted; text.textSize = 9f; text.textAlign = Paint.Align.CENTER
    canvas.drawText("Received By", pageWidth / 4f + 14f, y + 22f, text)
    canvas.drawText("Authorized Sign", pageWidth * 3f / 4f - 14f, y + 22f, text)
    text.textAlign = Paint.Align.LEFT

    // Footer
    val footerY = totalHeight - footerH
    fill.color = blue
    canvas.drawRoundRect(RectF(16f, footerY, right, totalHeight - 12f), 14f, 14f, fill)
    text.color = AndroidColor.argb(220, 255, 255, 255); text.textSize = 10f; text.textAlign = Paint.Align.CENTER
    canvas.drawText("Thank you  ·  ${institute.name}", pageWidth / 2f, footerY + 22f, text)
    text.textSize = 8.5f
    canvas.drawText("For any query contact: ${institute.phone}", pageWidth / 2f, footerY + 38f, text)
    text.textAlign = Paint.Align.LEFT

    document.finishPage(page)
    val file = File(context.cacheDir, "history_receipt_${item.payment.receiptNumber.replace("/", "_")}.pdf")
    file.outputStream().use { document.writeTo(it) }
    document.close()
    return file
}

// ── Helpers ──

private fun card(canvas: android.graphics.Canvas, fill: Paint, stroke: Paint, l: Float, t: Float, r: Float, h: Float) {
    fill.color = AndroidColor.WHITE
    canvas.drawRoundRect(RectF(l, t, r, t + h), 14f, 14f, fill)
    stroke.strokeWidth = 1f; stroke.color = AndroidColor.rgb(226, 232, 240)
    canvas.drawRoundRect(RectF(l, t, r, t + h), 14f, 14f, stroke)
}

private fun sectionLabel(canvas: android.graphics.Canvas, paint: Paint, label: String, x: Float, y: Float) {
    paint.color = AndroidColor.rgb(37, 99, 235); paint.textSize = 9f; paint.isFakeBoldText = true
    paint.textAlign = Paint.Align.LEFT
    canvas.drawText(label, x, y, paint)
    paint.isFakeBoldText = false
}

private fun row(canvas: android.graphics.Canvas, labelP: Paint, valueP: Paint,
                 label: String, value: String, x: Float, y: Float,
                 rightEdge: Float = 374f,
                 valueColor: Int = AndroidColor.rgb(20, 27, 38)) {
    labelP.color = AndroidColor.rgb(100, 116, 139); labelP.textSize = 10.5f; labelP.textAlign = Paint.Align.LEFT
    canvas.drawText(label, x, y, labelP)
    valueP.color = valueColor; valueP.textSize = 11.5f; valueP.isFakeBoldText = true
    valueP.textAlign = Paint.Align.RIGHT
    canvas.drawText(value, rightEdge, y, valueP)
    valueP.textAlign = Paint.Align.LEFT; valueP.isFakeBoldText = false
}

