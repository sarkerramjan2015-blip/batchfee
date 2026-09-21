package com.batchfee.edu.ui.students

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.batchfee.edu.data.audit.StaffActivityLogger
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.StudentSyncHelper
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.BatchStudentEntity
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.data.models.PaymentEntity
import com.batchfee.edu.data.models.StudentEntity
import com.batchfee.edu.data.repository.FeeCollectionRepository
import com.batchfee.edu.data.repository.BatchEnrollmentRepository
import com.batchfee.edu.data.repository.FinancialOperationPendingException
import com.batchfee.edu.data.repository.StudentDeletionRepository
import com.batchfee.edu.domain.appendInstituteSignature
import com.batchfee.edu.domain.loadInstituteSignature
import com.batchfee.edu.domain.MonthlyDueCalculator
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.isCourseBatch
import com.batchfee.edu.ui.components.buildWhatsAppUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
private val AccentAmber   = Color(0xFFF59E0B)
private val DashboardLine = Color(0x5522D3EE)
private val DashboardSoft = Color(0x1A22D3EE)
private val DangerRed     = Color(0xFFEF4444)

private fun isInactiveStudentStatus(status: String?): Boolean =
    status.orEmpty().trim().lowercase() in setOf("inactive", "close", "closed")

private data class StudentPdfExport(
    val file: File,
    val title: String,
    val description: String,
    val shareLabel: String
)

// ── Screen ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentProfileScreen(
    db: AppDatabase,
    studentId: String,
    onBack: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onGenerateIdCard: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val studentViewModel: StudentViewModel = viewModel(factory = StudentViewModelFactory(db))
    val instId = SessionManager.currentInstituteId.collectAsState().value
    val currentUserRole = SessionManager.currentUserRole.collectAsState().value
    val canArchiveStudent = currentUserRole in setOf("InstituteOwner", "SuperAdmin")
    val canManageCustomMonthlyFee = currentUserRole in setOf(
        "InstituteOwner", "owner", "instituteOwner",
        "SuperAdmin", "superAdmin", "super_admin"
    )

    var student by remember { mutableStateOf<StudentEntity?>(null) }
    var instituteSignature by remember { mutableStateOf("") }
    var instituteName by remember { mutableStateOf("") }
    var instituteContact by remember { mutableStateOf("") }
    var showStudentMenu by remember { mutableStateOf(false) }
    var showMessageDialog by remember { mutableStateOf(false) }
    var directMessage by remember { mutableStateOf("") }
    var pendingConfirmAction by remember { mutableStateOf<StudentMenuConfirmAction?>(null) }
    var isDeletingStudent by remember { mutableStateOf(false) }
    var isUpdatingStudentStatus by remember { mutableStateOf(false) }
    var showStudentInsights by remember { mutableStateOf(false) }
    var showSetStudentPasswordDialog by remember { mutableStateOf(false) }
    var showCustomMonthlyFeeDialog by remember { mutableStateOf(false) }
    var isGeneratingAdmissionForm by remember { mutableStateOf(false) }
    var admissionFormFile by remember { mutableStateOf<File?>(null) }
    var isGeneratingStudentPdf by remember { mutableStateOf(false) }
    var studentPdfExport by remember { mutableStateOf<StudentPdfExport?>(null) }
    // This value is deliberately not saveable. It exists only until the one-time
    // password dialog closes and is never persisted with the student profile.
    var oneTimeStudentPassword by remember { mutableStateOf<String?>(null) }
    var totalPaid by remember { mutableStateOf(0.0) }
    var totalDue by remember { mutableStateOf(0.0) }
    var batches by remember { mutableStateOf<List<BatchEntity>>(emptyList()) }
    var activeEnrollments by remember { mutableStateOf<List<BatchStudentEntity>>(emptyList()) }
    var billingEnrollments by remember { mutableStateOf<List<BatchStudentEntity>>(emptyList()) }
    var enrolledBatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var assigningBatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var feeHistory by remember { mutableStateOf<List<FeeEntity>>(emptyList()) }
    var paymentHistory by remember { mutableStateOf<List<PaymentEntity>>(emptyList()) }
    var monthAttendance by remember { mutableStateOf<List<com.batchfee.edu.data.models.AttendanceEntity>>(emptyList()) }

    LaunchedEffect(instId) {
        instituteSignature = loadInstituteSignature(db, instId)
        val institute = instId?.let { db.instituteDao().getInstitute(it) }
        instituteName = institute?.name?.trim().orEmpty()
        instituteContact = com.example.domain.MessageTemplateStore.loadInstituteContact(db, instId)
    }

    // ── Batch dialog state ──────────────────────────────────
    var showBatchDialog by remember { mutableStateOf(false) }
    var pendingBatchUnassign by remember { mutableStateOf<BatchStudentEntity?>(null) }
    var isUnassigningBatch by remember { mutableStateOf(false) }

    // ── Shift dialog state ───────────────────────────────────
    var showShiftDialog by remember { mutableStateOf(false) }

    // ── Fee collection state ─────────────────────────────────
    var showFeeForm by remember { mutableStateOf(false) }
    val feeRepository = remember { FeeCollectionRepository(db) }
    val batchEnrollmentRepository = remember(db) { BatchEnrollmentRepository(db) }
    val studentDeletionRepository = remember(db) { StudentDeletionRepository(db) }
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
    var feeErrorMessage by remember { mutableStateOf<String?>(null) }
    var isSavingFee by remember { mutableStateOf(false) }

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
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            launch {
                db.studentDao().getStudentById(studentId, instId).collect { student = it }
            }
            launch {
                db.feeDao().getFeesByStudent(instId, studentId).collect { fees ->
                    feeHistory = fees
                    totalPaid = fees.sumOf { it.paidAmount }
                    totalDue = fees.sumOf { it.dueAmount }
                }
            }
            launch {
                db.paymentDao().getRecentPayments(instId).collect { payments ->
                    // A reversed receipt stays in the backend audit trail but
                    // is never presented as a successful student payment.
                    paymentHistory = payments.filter { it.studentId == studentId && it.status == "completed" }
                }
            }
            launch {
                val range = currentMonthRangeMs()
                db.attendanceDao().getAttendanceForStudentByDateRange(instId, studentId, range.first, range.second).collect {
                    monthAttendance = it
                }
            }
            launch {
                db.batchStudentDao().getBatchesForStudent(studentId, instId).collect {
                    batches = it
                    enrolledBatchIds = it.map { b -> b.id }.toSet()
                }
            }
            launch {
                db.batchStudentDao().getActiveEnrollmentsForStudent(studentId, instId).collect {
                    activeEnrollments = it
                }
            }
            launch {
                // Keep removed enrollments only for their historic fee balance.
                db.batchStudentDao().getBillingEnrollmentsForStudent(studentId, instId).collect {
                    billingEnrollments = it
                }
            }
        }
    }

    // ── Scaffold ─────────────────────────────────────────────
    // Custom fees saved by older app versions were stored on the enrollment
    // only. Reconcile each policy once so a legacy unpaid monthly row can
    // never revive the old batch price in a future month.
    LaunchedEffect(instId, currentUserRole, activeEnrollments) {
        val instituteId = instId ?: return@LaunchedEffect
        if (!canManageCustomMonthlyFee) return@LaunchedEffect
        activeEnrollments
            .filter { it.customMonthlyFeeAmount != null && it.customFeePolicySyncedAtMs == null }
            .forEach { enrollment ->
                try {
                    withContext(Dispatchers.IO) {
                        feeRepository.setCustomMonthlyFee(
                            instituteId = instituteId,
                            enrollmentId = enrollment.id,
                            studentId = enrollment.studentId,
                            batchId = enrollment.batchId,
                            customMonthlyFeeAmount = enrollment.customMonthlyFeeAmount,
                            customFeeReason = enrollment.customFeeReason
                                ?.takeIf { it.trim().length >= 3 }
                                ?: "Custom monthly fee"
                        )
                        db.batchStudentDao().enrollStudent(
                            enrollment.copy(customFeePolicySyncedAtMs = System.currentTimeMillis())
                        )
                    }
                } catch (_: Exception) {
                    // Keep the marker empty. A later profile load retries the
                    // reconciliation; no local or cloud fee is changed here.
                }
            }
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(student?.fullName ?: "Profile", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            student?.status?.replaceFirstChar { it.uppercase() } ?: "",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                actions = {
                    if (onEdit != null) {
                        IconButton(onClick = { showStudentMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = TextWhite)
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
            val computedTotalDue = remember(feeHistory, batches, billingEnrollments, s.admissionDateMs) {
                var computed = feeHistory.filter { !MonthlyDueCalculator.isMonthlyFeeType(it.feeType) }.sumOf { it.dueAmount }
                computed += feeHistory.filter {
                    it.dueAmount > 0.0 &&
                        MonthlyDueCalculator.isMonthlyInstallmentDue(it.feeType, it.feePeriod) &&
                        billingEnrollments.filter { enrollment -> enrollment.batchId == it.batchId }.any { enrollment ->
                            MonthlyDueCalculator.isMonthlyFeeWithinEnrollmentWindow(
                                feePeriod = it.feePeriod,
                                studentAdmissionDateMs = s.admissionDateMs,
                                enrollmentJoinedAtMs = enrollment.joinedAtMs,
                                firstMonthFeePeriod = enrollment.firstMonthFeePeriod,
                                billingEndedAtMs = enrollment.leftAtMs
                            )
                        }
                }.sumOf { it.dueAmount }
                billingEnrollments.forEach { enrollment ->
                    val batch = batches.firstOrNull { it.id == enrollment.batchId }
                        ?: return@forEach
                    if (batch.monthlyFeeAmount > 0.0) {
                        val billingStartMs = MonthlyDueCalculator.effectiveBillingStartMs(
                            s.admissionDateMs,
                            enrollment.joinedAtMs,
                            enrollment.firstMonthFeePeriod
                        )
                        val batchFees = feeHistory.filter {
                            it.batchId == batch.id && MonthlyDueCalculator.isMonthlyFeeType(it.feeType)
                        }
                        val items = MonthlyDueCalculator.computeMonthlyOutstandingItems(
                            admissionDateMs = billingStartMs,
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
                        computed += items.sumOf { it.outstanding }
                    }
                    val admissionAlreadyCreated = feeHistory.any { fee ->
                        fee.batchId == batch.id && fee.feeType.equals("admission_fee", ignoreCase = true)
                    }
                    if (enrollment.status.equals("active", ignoreCase = true) &&
                        batch.admissionFeeAmount > 0.0 && !admissionAlreadyCreated) {
                        computed += batch.admissionFeeAmount
                    }
                }
                computed
            }
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                StudentDashboardContent(
                    student = s,
                    batches = batches,
                    totalPaid = totalPaid,
                    totalDue = computedTotalDue,
                    paymentHistory = paymentHistory,
                    monthAttendance = monthAttendance,
                    activeEnrollments = activeEnrollments,
                    context = context,
                    instituteSignature = instituteSignature,
                    insightsVisible = showStudentInsights,
                    onToggleInsights = { showStudentInsights = !showStudentInsights },
                    onAssignBatch = { showBatchDialog = true },
                    onSetCustomMonthlyFee = if (canManageCustomMonthlyFee && activeEnrollments.isNotEmpty()) {
                        { showCustomMonthlyFeeDialog = true }
                    } else null,
                    onSetOrResetPassword = { showSetStudentPasswordDialog = true },
                    onShareLoginInfo = {
                        shareStudentText(
                            context,
                            "Student Login",
                            buildStudentLoginInfoText(s, instituteSignature)
                        )
                    }
                )

                if (false) {
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
                                    .data(FirebaseStorageImageUploadHelper.displaySource(context, s.photoUri))
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
                            .fillMaxWidth()
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
                            Text("Manage Batches", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
                                                    selectedBatchId = fee.batchId
                                                    feePeriod = fee.feePeriod
                                                    feeAmount = fee.totalAmount.toLong().toString()
                                                    discountPercent = if (fee.baseAmount > 0.0) {
                                                        ((fee.discountAmount / fee.baseAmount) * 100.0).toInt().toString()
                                                    } else {
                                                        "0"
                                                    }
                                                    collectAmount = fee.dueAmount.toLong().toString()
                                                    feeErrorMessage = null
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
                                                selectedFeeId = null
                                                feeAmount = batch.monthlyFeeAmount.toLong().toString()
                                                discountPercent = "0"
                                                feeErrorMessage = null
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
                            if (feeErrorMessage != null) {
                                Text(feeErrorMessage!!, color = Color(0xFFEF4444), fontSize = 12.sp)
                                Spacer(Modifier.height(8.dp))
                            }

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
                                        .clickable(enabled = !isSavingFee) {
                                            if (isSavingFee) return@clickable
                                            val base = feeAmount.toDoubleOrNull() ?: 0.0
                                            val paid = collectAmount.toDoubleOrNull() ?: 0.0
                                            feeErrorMessage = null
                                            if (base <= 0.0) {
                                                feeErrorMessage = "Enter a valid fee amount."
                                                return@clickable
                                            }
                                            if (feePeriod.isBlank()) {
                                                feeErrorMessage = "Enter a fee period."
                                                return@clickable
                                            }
                                            if (paid < 0.0) {
                                                feeErrorMessage = "Collected amount cannot be negative."
                                                return@clickable
                                            }
                                            val inst = instId ?: run {
                                                feeErrorMessage = "No active institute session."
                                                return@clickable
                                            }
                                            val userId = SessionManager.currentUserId.value ?: run {
                                                feeErrorMessage = "No active user session."
                                                return@clickable
                                            }
                                            val total = (base - discountAmt).coerceAtLeast(0.0)
                                            val due = (total - paid).coerceAtLeast(0.0)
                                            val rText = buildString {
                                                appendLine("BatchFee - Fee Receipt")
                                                appendLine("Student : ${s.fullName}")
                                                appendLine("ID      : ${s.studentCode}")
                                                appendLine("Phone   : ${s.phone ?: "N/A"}")
                                                appendLine("Batch   : ${batches.find { it.id == selectedBatchId }?.name ?: "N/A"}")
                                                appendLine("Period  : $feePeriod")
                                                appendLine("Date    : ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(paymentDateMs))}")
                                                appendLine("Fee     : BDT ${base.toLong()}")
                                                appendLine("Discount: BDT ${discountAmt.toLong()} (${discountPct}%)")
                                                appendLine("Total   : BDT ${total.toLong()}")
                                                appendLine("Paid    : BDT ${paid.toLong()}")
                                                appendLine("Due     : BDT ${due.toLong()}")
                                            }
                                            receiptText = rText
                                            isSavingFee = true
                                            scope.launch {
                                                try {
                                                    val existingFeeId = selectedFeeId
                                                    if (existingFeeId != null) {
                                                        feeRepository.collectPayment(
                                                            instituteId = inst,
                                                            collectedByUserId = userId,
                                                            feeId = existingFeeId,
                                                            amount = paid,
                                                            paymentMethod = "cash",
                                                            paymentDateMs = paymentDateMs,
                                                            note = receiptImageUri?.toString(),
                                                            receiptText = rText
                                                        )
                                                    } else {
                                                        feeRepository.createFeeWithInitialPayment(
                                                            instituteId = inst,
                                                            collectedByUserId = userId,
                                                            studentId = studentId,
                                                            batchId = selectedBatchId,
                                                            feePeriod = feePeriod,
                                                            feeType = "monthly_fee",
                                                            dueDateMs = paymentDateMs,
                                                            baseAmount = base,
                                                            discountAmount = kotlin.math.round(discountAmt * 100.0) / 100.0,
                                                            lateFeeAmount = 0.0,
                                                            collectedAmount = paid,
                                                            paymentMethod = "cash",
                                                            paymentDateMs = paymentDateMs,
                                                            note = receiptImageUri?.toString(),
                                                            receiptText = rText
                                                        )
                                                    }
                                                    feePeriod = ""
                                                    feeAmount = ""
                                                    discountPercent = "0"
                                                    collectAmount = ""
                                                    selectedFeeId = null
                                                    receiptImageUri = null
                                                } catch (e: FinancialOperationPendingException) {
                                                    feeErrorMessage = e.message ?: "Payment is pending reconciliation. Do not retry it."
                                                } catch (e: IllegalArgumentException) {
                                                    feeErrorMessage = e.message ?: "Payment rejected."
                                                } catch (e: Exception) {
                                                    feeErrorMessage = "Payment failed before it could be queued."
                                                } finally {
                                                    isSavingFee = false
                                                }
                                            }
                                            return@clickable
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
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isSavingFee) {
                                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                        } else {
                                            Icon(Icons.Filled.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (isSavingFee) "Saving..." else "Save", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                                            val url = buildWhatsAppUrl(studentPhone, msg)
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
                    val whatsappFromNotes = s.notes?.let { n ->
                        if (n.startsWith("WhatsApp: ")) {
                            n.split("\n", limit = 2)[0].removePrefix("WhatsApp: ")
                        } else null
                    }
                    CompactContactInfoRow(
                        "Phone",
                        s.phone ?: "N/A",
                        "WhatsApp",
                        whatsappFromNotes?.takeIf { it.isNotBlank() } ?: "N/A"
                    )
                    InfoRow("Email", s.email ?: "N/A")
                    InfoRow("Address", s.address ?: "N/A")
                }

                Spacer(Modifier.height(14.dp))

                SectionHeader("Guardian Info")
                InfoCard {
                    InfoRow("Father / Guardian", s.guardianName ?: "N/A")
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
            }

            // ── Batch Assignment Dialog ────────────────────────
            if (showBatchDialog) {
                var allBatches by remember { mutableStateOf<List<BatchEntity>>(emptyList()) }
                LaunchedEffect(instId) {
                    if (instId != null) {
                        InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
                        db.batchDao().getBatchesByInstitute(instId).collect { allBatches = it }
                    }
                }

                AlertDialog(
                    onDismissRequest = { showBatchDialog = false },
                    containerColor = CardBgAlt,
                    title = { Text("Manage Batch Assignment", color = TextWhite, fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(
                                "Tap an assigned batch to remove only that batch. Unpaid fees from a mistaken assignment will be cleared; payment history stays safe.",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            if (allBatches.isEmpty()) {
                                Text("No batches available. Create a batch first.", color = TextMuted)
                            } else {
                                allBatches.forEach { batch ->
                                    val activeEnrollment = activeEnrollments.firstOrNull { it.batchId == batch.id }
                                    val isEnrolled = activeEnrollment != null
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isEnrolled) Cyan.copy(alpha = 0.1f) else Color.Transparent)
                                            .then(
                                                if (isEnrolled) Modifier.border(1.dp, Cyan.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                                else Modifier.border(1.dp, BorderSub, RoundedCornerShape(10.dp))
                                            )
                                            .clickable(
                                                enabled = batch.id !in assigningBatchIds && !isUnassigningBatch
                                            ) {
                                                if (isEnrolled) {
                                                    pendingBatchUnassign = activeEnrollment
                                                } else if (batch.id !in assigningBatchIds && instId != null) {
                                                    assigningBatchIds = assigningBatchIds + batch.id
                                                    scope.launch {
                                                        try {
                                                            val enrollmentStart = s.admissionDateMs.takeIf { it > 0L }
                                                                ?: System.currentTimeMillis()
                                                            BatchEnrollmentRepository(db).enroll(
                                                                instituteId = instId!!,
                                                                studentId = studentId,
                                                                batch = batch,
                                                                enrollmentStartMs = enrollmentStart
                                                            )
                                                            // Refresh enrolled set
                                                            enrolledBatchIds = enrolledBatchIds + batch.id
                                                            batches = batches + batch
                                                        } catch (error: Exception) {
                                                            Toast.makeText(
                                                                context,
                                                                error.message ?: "Could not assign this batch.",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        } finally {
                                                            assigningBatchIds = assigningBatchIds - batch.id
                                                        }
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
                                            Text(
                                                if (batch.isCourseBatch()) {
                                                    "Course fee BDT ${batch.courseFeeAmount.toLong()}"
                                                } else {
                                                    "BDT ${batch.monthlyFeeAmount.toLong()}/mo"
                                                },
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                            if (isEnrolled) {
                                                Text(
                                                    "Assigned · tap to remove",
                                                    color = DangerRed,
                                                    fontSize = 10.sp
                                                )
                                            }
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

            // ── Shift Batch Dialog ────────────────────────────
            pendingBatchUnassign?.let { enrollment ->
                val batchName = batches.firstOrNull { it.id == enrollment.batchId }?.name ?: "this batch"
                AlertDialog(
                    onDismissRequest = {
                        if (!isUnassigningBatch) pendingBatchUnassign = null
                    },
                    containerColor = CardBg,
                    icon = {
                        Icon(
                            Icons.Filled.RemoveCircleOutline,
                            contentDescription = null,
                            tint = DangerRed,
                            modifier = Modifier.size(36.dp)
                        )
                    },
                    title = {
                        Text("Remove from $batchName?", color = TextWhite, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Text(
                            "The student will no longer appear in this batch. Unpaid fees linked to this batch will be removed from due collection. Any payment or receipt history will remain safe.",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (isUnassigningBatch) return@Button
                                isUnassigningBatch = true
                                scope.launch {
                                    try {
                                        val result = batchEnrollmentRepository.unassign(enrollment)
                                        val summary = buildString {
                                            append("Removed from ")
                                            append(batchName)
                                            append(".")
                                            if (result.cancelledFeeIds.isNotEmpty()) {
                                                append(" ${result.cancelledFeeIds.size} unpaid fee")
                                                append(if (result.cancelledFeeIds.size == 1) " was" else "s were")
                                                append(" cleared.")
                                            }
                                            if (result.retainedHistoryFeeCount > 0) {
                                                append(" Payment history was kept safe.")
                                            }
                                        }
                                        Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
                                        pendingBatchUnassign = null
                                    } catch (error: Exception) {
                                        Toast.makeText(
                                            context,
                                            error.message ?: "Could not remove this batch. Check your connection and try again.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } finally {
                                        isUnassigningBatch = false
                                    }
                                }
                            },
                            enabled = !isUnassigningBatch,
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isUnassigningBatch) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Text("Remove Batch", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { pendingBatchUnassign = null },
                            enabled = !isUnassigningBatch
                        ) {
                            Text("Cancel", color = TextMuted)
                        }
                    }
                )
            }

            if (showShiftDialog) {
                var allBatches by remember { mutableStateOf<List<BatchEntity>>(emptyList()) }
                var selectedSourceEnrollmentId by remember { mutableStateOf<String?>(null) }
                var selectedNewBatchId by remember { mutableStateOf<String?>(null) }
                var isShifting by remember { mutableStateOf(false) }
                LaunchedEffect(instId) {
                    if (instId != null) {
                        InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
                        db.batchDao().getBatchesByInstitute(instId).collect { allBatches = it }
                    }
                }
                LaunchedEffect(activeEnrollments) {
                    if (selectedSourceEnrollmentId !in activeEnrollments.map { it.id }) {
                        selectedSourceEnrollmentId = activeEnrollments.firstOrNull()?.id
                    }
                }
                val sourceEnrollment = activeEnrollments.firstOrNull { it.id == selectedSourceEnrollmentId }
                val sourceBatch = allBatches.firstOrNull { it.id == sourceEnrollment?.batchId }
                    ?: batches.firstOrNull { it.id == sourceEnrollment?.batchId }
                val availableBatches = allBatches.filter { batch ->
                    batch.id != sourceEnrollment?.batchId &&
                        !enrolledBatchIds.contains(batch.id) &&
                        batch.status.equals("active", ignoreCase = true)
                }
                val selectedTargetBatch = availableBatches.firstOrNull { it.id == selectedNewBatchId }

                AlertDialog(
                    onDismissRequest = { if (!isShifting) showShiftDialog = false },
                    containerColor = CardBg,
                    icon = { Icon(Icons.Filled.SwapHoriz, null, tint = AccentAmber, modifier = Modifier.size(40.dp)) },
                    title = { Text("Shift Student to Another Batch", color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Move one selected batch assignment. Old paid receipts and completed fee history stay safely in the old batch.",
                                color = TextMuted, fontSize = 12.sp
                            )
                            if (activeEnrollments.size > 1) {
                                Text("Move from", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                activeEnrollments.forEach { enrollment ->
                                    val batch = allBatches.firstOrNull { it.id == enrollment.batchId }
                                        ?: batches.firstOrNull { it.id == enrollment.batchId }
                                    val isSelectedSource = enrollment.id == selectedSourceEnrollmentId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSelectedSource) AccentAmber.copy(alpha = 0.1f) else CardBgAlt)
                                            .border(1.dp, if (isSelectedSource) AccentAmber.copy(alpha = 0.5f) else BorderSub, RoundedCornerShape(10.dp))
                                            .clickable(enabled = !isShifting) {
                                                selectedSourceEnrollmentId = enrollment.id
                                                selectedNewBatchId = null
                                            }
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(batch?.name ?: "Previous batch", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            Text("Current assignment", color = TextMuted, fontSize = 11.sp)
                                        }
                                        if (isSelectedSource) Icon(Icons.Filled.CheckCircle, null, tint = AccentAmber, modifier = Modifier.size(19.dp))
                                    }
                                }
                            } else if (sourceBatch != null) {
                                Text("From: ${sourceBatch.name}", color = TextMuted, fontSize = 12.sp)
                            }
                            Text("Move to", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            if (availableBatches.isEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Info, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("No other batches available.", color = TextMuted, fontSize = 13.sp)
                                }
                            } else {
                                availableBatches.forEach { batch ->
                                    val isSelected = selectedNewBatchId == batch.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSelected) AccentAmber.copy(alpha = 0.1f) else CardBgAlt)
                                            .border(1.dp, if (isSelected) AccentAmber.copy(alpha = 0.5f) else BorderSub, RoundedCornerShape(10.dp))
                                            .clickable { if (!isShifting) selectedNewBatchId = batch.id }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(batch.name, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                if (batch.isCourseBatch()) {
                                                    "Course fee: BDT ${"%.0f".format(batch.courseFeeAmount)}"
                                                } else {
                                                    "Monthly fee: BDT ${"%.0f".format(batch.monthlyFeeAmount)}"
                                                },
                                                color = TextMuted, fontSize = 11.sp
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(Icons.Filled.CheckCircle, null, tint = AccentAmber, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                            selectedTargetBatch?.let { target ->
                                val detail = if (target.isCourseBatch()) {
                                    "Course fee BDT ${"%.0f".format(target.courseFeeAmount)} will be added once."
                                } else {
                                    "This month's fee will be calculated from today's shift date."
                                }
                                Text(detail, color = Cyan, fontSize = 11.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val source = sourceEnrollment ?: return@Button
                                val target = selectedTargetBatch ?: return@Button
                                isShifting = true
                                scope.launch {
                                    try {
                                        batchEnrollmentRepository.shift(
                                            sourceEnrollment = source,
                                            targetBatch = target,
                                            shiftDateMs = System.currentTimeMillis()
                                        )
                                        StaffActivityLogger.logCompletedAction(
                                            db,
                                            "student_batch_changed",
                                            "batches",
                                            "Moved ${s.fullName} from ${sourceBatch?.name ?: "a batch"} to ${target.name}"
                                        )
                                        Toast.makeText(context, "Student moved to ${target.name}.", Toast.LENGTH_SHORT).show()
                                        showShiftDialog = false
                                    } catch (error: Exception) {
                                        Toast.makeText(
                                            context,
                                            error.message ?: "Could not shift the student. Please try again.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } finally {
                                            isShifting = false
                                    }
                                }
                            },
                            enabled = !isShifting && sourceEnrollment != null && selectedTargetBatch != null,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isShifting) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            else Text("Shift Batch", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showShiftDialog = false }, enabled = !isShifting) {
                            Text("Cancel", color = TextMuted)
                        }
                    }
                )
            }

            if (showSetStudentPasswordDialog) {
                StudentPasswordDialog(
                    isReset = s.isAppAccessEnabled,
                    onDismiss = { showSetStudentPasswordDialog = false },
                    onSetPassword = { password, onSuccess, onError ->
                        studentViewModel.setStudentAppPassword(
                            studentId = s.id,
                            password = password,
                            onSuccess = onSuccess,
                            onError = onError
                        )
                    },
                    onPasswordSet = { password ->
                        showSetStudentPasswordDialog = false
                        oneTimeStudentPassword = password
                    }
                )
            }

            if (showCustomMonthlyFeeDialog) {
                CustomMonthlyFeeDialog(
                    enrollments = activeEnrollments,
                    batches = batches,
                    onDismiss = { showCustomMonthlyFeeDialog = false },
                    onSave = { updatedEnrollment, onSuccess, onError ->
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    // The trusted ledger updates the fee policy and any untouched
                                    // running/future fee records in one server transaction. Room is
                                    // updated only after that confirmed cloud change.
                                    feeRepository.setCustomMonthlyFee(
                                        instituteId = updatedEnrollment.instituteId,
                                        enrollmentId = updatedEnrollment.id,
                                        studentId = updatedEnrollment.studentId,
                                        batchId = updatedEnrollment.batchId,
                                        customMonthlyFeeAmount = updatedEnrollment.customMonthlyFeeAmount,
                                        customFeeReason = updatedEnrollment.customFeeReason
                                    )
                                    db.batchStudentDao().enrollStudent(
                                        updatedEnrollment.copy(
                                            customFeePolicySyncedAtMs = System.currentTimeMillis()
                                        )
                                    )
                                }
                                StaffActivityLogger.logCompletedAction(
                                    db,
                                    "student_custom_monthly_fee_updated",
                                    "batch_students",
                                    "Updated a student's custom monthly fee"
                                )
                                onSuccess()
                            } catch (e: Exception) {
                                onError(e.message ?: "Could not save the custom monthly fee. Check your connection and try again.")
                            }
                        }
                    }
                )
            }

            oneTimeStudentPassword?.let { password ->
                OneTimeStudentPasswordDialog(
                    student = s,
                    password = password,
                    instituteSignature = instituteSignature,
                    onDismiss = { oneTimeStudentPassword = null }
                )
            }

            if (showStudentMenu) {
                StudentBatchMenuDialog(
                    onDismiss = { showStudentMenu = false },
                    onEdit = {
                        showStudentMenu = false
                        onEdit?.invoke()
                    },
                    onAssignBatch = {
                        showStudentMenu = false
                        showBatchDialog = true
                    },
                    onShiftBatch = {
                        showStudentMenu = false
                        showShiftDialog = true
                    },
                    isStudentInactive = isInactiveStudentStatus(s.status),
                    onChangeStudentActivity = {
                        showStudentMenu = false
                        pendingConfirmAction = if (isInactiveStudentStatus(s.status)) {
                            StudentMenuConfirmAction.Activate
                        } else {
                            StudentMenuConfirmAction.Close
                        }
                    },
                    onShareLogin = {
                        showStudentMenu = false
                        shareStudentText(
                            context,
                            "Student Login",
                            buildStudentLoginInfoText(s, instituteSignature)
                        )
                    },
                    onMessage = {
                        showStudentMenu = false
                        directMessage = ""
                        showMessageDialog = true
                    },
                    onDeleteStudent = {
                        showStudentMenu = false
                        pendingConfirmAction = StudentMenuConfirmAction.Delete
                    },
                    canDeleteStudent = canArchiveStudent,
                    onGenerateReport = {
                        showStudentMenu = false
                        if (instId == null) {
                            Toast.makeText(context, "Institute information is unavailable.", Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch {
                                isGeneratingStudentPdf = true
                                try {
                                    val institute = withContext(Dispatchers.IO) { db.instituteDao().getInstitute(instId) }
                                    if (institute == null) {
                                        Toast.makeText(context, "Institute information is unavailable.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val file = withContext(Dispatchers.IO) {
                                            generateStudentReportPdf(
                                                context, institute, s, batches, monthAttendance, paymentHistory,
                                                totalPaid, computedTotalDue
                                            )
                                        }
                                        studentPdfExport = StudentPdfExport(
                                            file = file,
                                            title = "Student Report Ready",
                                            description = "A polished, print-ready PDF report has been created with student, attendance, batch and payment details.",
                                            shareLabel = "Student performance report"
                                        )
                                    }
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Could not create the student report.", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isGeneratingStudentPdf = false
                                }
                            }
                        }
                    },
                    onRegistrationForm = {
                        showStudentMenu = false
                        if (instId == null) {
                            Toast.makeText(context, "Institute information is unavailable.", Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch {
                                isGeneratingAdmissionForm = true
                                try {
                                    val institute = withContext(Dispatchers.IO) {
                                        db.instituteDao().getInstitute(instId)
                                    }
                                    if (institute == null) {
                                        Toast.makeText(context, "Institute information is unavailable.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        admissionFormFile = withContext(Dispatchers.IO) {
                                            generateStudentAdmissionFormPdf(context, institute, s)
                                        }
                                    }
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Could not create the admission form.", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isGeneratingAdmissionForm = false
                                }
                            }
                        }
                    },
                    onFeeSummary = {
                        showStudentMenu = false
                        if (instId == null) {
                            Toast.makeText(context, "Institute information is unavailable.", Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch {
                                isGeneratingStudentPdf = true
                                try {
                                    val institute = withContext(Dispatchers.IO) { db.instituteDao().getInstitute(instId) }
                                    if (institute == null) {
                                        Toast.makeText(context, "Institute information is unavailable.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val file = withContext(Dispatchers.IO) {
                                            generateStudentFeeSummaryPdf(
                                                context, institute, s, batches, feeHistory, paymentHistory,
                                                totalPaid, computedTotalDue
                                            )
                                        }
                                        studentPdfExport = StudentPdfExport(
                                            file = file,
                                            title = "Fee Summary Ready",
                                            description = "A polished, print-ready PDF fee summary has been created with the complete ledger, payments and pending due.",
                                            shareLabel = "Student fee summary"
                                        )
                                    }
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Could not create the fee summary.", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isGeneratingStudentPdf = false
                                }
                            }
                        }
                    },
                    onGenerateIdCard = {
                        showStudentMenu = false
                        onGenerateIdCard?.invoke()
                    }
                )
            }

            if (isGeneratingAdmissionForm) {
                AlertDialog(
                    onDismissRequest = {},
                    containerColor = CardBg,
                    title = { Text("Creating admission form", color = TextWhite, fontWeight = FontWeight.Bold) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Cyan, strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Preparing the print-ready PDF…", color = TextMuted)
                        }
                    },
                    confirmButton = {}
                )
            }

            if (isGeneratingStudentPdf) {
                AlertDialog(
                    onDismissRequest = {},
                    containerColor = CardBg,
                    title = { Text("Creating PDF", color = TextWhite, fontWeight = FontWeight.Bold) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Cyan, strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Preparing your professional PDF…", color = TextMuted)
                        }
                    },
                    confirmButton = {}
                )
            }

            admissionFormFile?.let { file ->
                AdmissionFormReadyDialog(
                    onDismiss = { admissionFormFile = null },
                    onPrint = {
                        if (!openStudentAdmissionFormPdf(context, file)) {
                            Toast.makeText(context, "No PDF app available for printing.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onWhatsApp = {
                        if (!shareStudentAdmissionFormToWhatsApp(context, file)) {
                            Toast.makeText(context, "WhatsApp is not installed.", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            studentPdfExport?.let { export ->
                StudentPdfReadyDialog(
                    title = export.title,
                    description = export.description,
                    onDismiss = { studentPdfExport = null },
                    onPrint = {
                        if (!openStudentPdf(context, export.file, export.shareLabel)) {
                            Toast.makeText(context, "No PDF app available for printing.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onWhatsApp = {
                        if (!shareStudentPdfToWhatsApp(context, export.file, export.shareLabel)) {
                            Toast.makeText(context, "WhatsApp is not installed.", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            if (showMessageDialog) {
                StudentMessageDialog(
                    message = directMessage,
                    onMessageChange = { directMessage = it },
                    onUseAdmissionWelcome = {
                        directMessage = buildStudentAdmissionWelcomeMessage(s, instituteName, instituteContact)
                    },
                    onDismiss = { showMessageDialog = false },
                    onSendSms = {
                        sendStudentMessage(context, s.phone, directMessage, instituteSignature, useWhatsApp = false)
                        showMessageDialog = false
                    },
                    onSendWhatsApp = {
                        sendStudentMessage(context, s.phone, directMessage, instituteSignature, useWhatsApp = true)
                        showMessageDialog = false
                    }
                )
            }

            pendingConfirmAction?.let { action ->
                AlertDialog(
                    onDismissRequest = {
                        if (!isDeletingStudent && !isUpdatingStudentStatus) pendingConfirmAction = null
                    },
                    containerColor = CardBgAlt,
                    shape = RoundedCornerShape(14.dp),
                    title = {
                        Text(
                            when (action) {
                                StudentMenuConfirmAction.Delete -> "Archive student safely?"
                                StudentMenuConfirmAction.Activate -> "Activate student?"
                                StudentMenuConfirmAction.Close -> "Mark student inactive?"
                            },
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            when (action) {
                                StudentMenuConfirmAction.Delete ->
                                    "The student account will be disabled and hidden, while fees, payments, receipts, enrolments, attendance, results and media remain retained for recovery and audit."
                                StudentMenuConfirmAction.Activate ->
                                    "The student will return to active lists and attendance. Existing batches, fees and history will remain unchanged."
                                StudentMenuConfirmAction.Close ->
                                    "The student will be removed from active lists and attendance. Fees, receipts and complete history will remain safe."
                            },
                            color = TextMuted
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val currentStudent = student ?: return@TextButton
                                scope.launch {
                                    if (action == StudentMenuConfirmAction.Delete) {
                                        isDeletingStudent = true
                                        try {
                                            studentDeletionRepository.archive(currentStudent)
                                            pendingConfirmAction = null
                                            onBack()
                                        } catch (_: Exception) {
                                            Toast.makeText(
                                                context,
                                                "Student could not be archived safely. Check your connection and refresh; do not repeat a permanent-delete attempt.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } finally {
                                            isDeletingStudent = false
                                        }
                                    } else {
                                        isUpdatingStudentStatus = true
                                        try {
                                            val becomingActive = action == StudentMenuConfirmAction.Activate
                                            val updated = currentStudent.copy(
                                                status = if (becomingActive) "active" else "inactive",
                                                updatedAtMs = System.currentTimeMillis()
                                            )
                                            // Status is a cloud-first operational change. Do not let a
                                            // failed request create a local-only inactive/active state.
                                            StudentSyncHelper.upsertStudentOrThrow(updated)
                                            db.studentDao().updateStudent(updated)
                                            StaffActivityLogger.logCompletedAction(
                                                db,
                                                if (becomingActive) "student_activated" else "student_inactivated",
                                                "students",
                                                if (becomingActive) "Activated student ${updated.fullName}" else "Marked student ${updated.fullName} inactive"
                                            )
                                            Toast.makeText(
                                                context,
                                                if (becomingActive) "Student is active again." else "Student marked inactive.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            pendingConfirmAction = null
                                        } catch (_: Exception) {
                                            Toast.makeText(
                                                context,
                                                "Student status could not be updated. Check your connection and try again.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } finally {
                                            isUpdatingStudentStatus = false
                                        }
                                    }
                                }
                            },
                            enabled = !isDeletingStudent && !isUpdatingStudentStatus
                        ) {
                            Text(
                                when {
                                    isDeletingStudent -> "Securing..."
                                    isUpdatingStudentStatus -> "Saving..."
                                    action == StudentMenuConfirmAction.Delete -> "Archive safely"
                                    action == StudentMenuConfirmAction.Activate -> "Activate"
                                    else -> "Mark inactive"
                                },
                                color = if (action == StudentMenuConfirmAction.Activate) Teal else Color(0xFFEF4444)
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { pendingConfirmAction = null },
                            enabled = !isDeletingStudent && !isUpdatingStudentStatus
                        ) {
                            Text("Cancel", color = TextMuted)
                        }
                    }
                )
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────
private enum class StudentMenuConfirmAction {
    Activate,
    Close,
    Delete
}

@Composable
private fun StudentPasswordDialog(
    isReset: Boolean,
    onDismiss: () -> Unit,
    onSetPassword: (String, () -> Unit, (String) -> Unit) -> Unit,
    onPasswordSet: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val dismiss = {
        if (!isSaving) {
            password = ""
            confirmPassword = ""
            errorMessage = null
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = CardBg,
        icon = { Icon(Icons.Filled.Key, contentDescription = null, tint = Cyan, modifier = Modifier.size(32.dp)) },
        title = {
            Text(
                if (isReset) "Reset Student Password" else "Set Student Password",
                color = TextWhite,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The password is stored only by the secure student account service. It will be shown once after it is set.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("New password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = errorMessage != null,
                    colors = darkFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        errorMessage = null
                    },
                    label = { Text("Confirm new password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = errorMessage != null,
                    colors = darkFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let { Text(it, color = DangerRed, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        password.length !in 6..128 ->
                            errorMessage = "Password must contain 6 to 128 characters."
                        password != confirmPassword ->
                            errorMessage = "Passwords do not match."
                        else -> {
                            val passwordToRevealOnce = password
                            isSaving = true
                            onSetPassword(
                                passwordToRevealOnce,
                                {
                                    isSaving = false
                                    password = ""
                                    confirmPassword = ""
                                    errorMessage = null
                                    onPasswordSet(passwordToRevealOnce)
                                },
                                { message ->
                                    isSaving = false
                                    errorMessage = message
                                }
                            )
                        }
                    }
                },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(if (isReset) "Reset Password" else "Set Password")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = !isSaving) {
                Text("Cancel", color = TextMuted)
            }
        }
    )
}

@Composable
private fun OneTimeStudentPasswordDialog(
    student: StudentEntity,
    password: String,
    instituteSignature: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Teal, modifier = Modifier.size(32.dp)) },
        title = { Text("Password Set", color = TextWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This new password is shown only now. Copy or share it before closing this dialog.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                Text("Student ID", color = TextMuted, fontSize = 12.sp)
                Text(student.studentCode, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("New password", color = TextMuted, fontSize = 12.sp)
                Text(
                    password,
                    color = Cyan,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DashboardSoft)
                        .border(1.dp, DashboardLine, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { copyTextToClipboard(context, "Student password", password) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = Cyan, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Copy", color = Cyan)
                }
                TextButton(
                    onClick = {
                        shareStudentText(
                            context,
                            "Student Login",
                            buildStudentLoginInfoText(student, instituteSignature, password)
                        )
                    }
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, tint = Cyan, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Share", color = Cyan)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = TextMuted) }
        }
    )
}

private data class StudentBatchMenuItem(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
    val isDestructive: Boolean = false
)

@Composable
private fun StudentBatchMenuDialog(
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onAssignBatch: () -> Unit,
    onShiftBatch: () -> Unit,
    isStudentInactive: Boolean,
    onChangeStudentActivity: () -> Unit,
    onShareLogin: () -> Unit,
    onMessage: () -> Unit,
    onDeleteStudent: () -> Unit,
    canDeleteStudent: Boolean,
    onGenerateReport: () -> Unit,
    onRegistrationForm: () -> Unit,
    onFeeSummary: () -> Unit,
    onGenerateIdCard: () -> Unit
) {
    val items = buildList {
        addAll(listOf(
        StudentBatchMenuItem("Edit Student", "You can edit student details here", Icons.Filled.Edit, onEdit),
        StudentBatchMenuItem("Assign Batch", "You can assign new batch here", Icons.Filled.Groups, onAssignBatch),
        StudentBatchMenuItem("Shift Batch", "You can shift this student to another batch", Icons.Filled.SwapHoriz, onShiftBatch),
        StudentBatchMenuItem(
            title = if (isStudentInactive) "Activate Student" else "Mark Inactive",
            subtitle = if (isStudentInactive) {
                "Return this student to active lists and attendance."
            } else {
                "Stop attendance without losing history."
            },
            icon = if (isStudentInactive) Icons.Filled.CheckCircle else Icons.Filled.Close,
            onClick = onChangeStudentActivity,
            isDestructive = !isStudentInactive
        ),
        StudentBatchMenuItem("Share Login Info", "Share student ID and app access status", Icons.Filled.Share, onShareLogin),
        StudentBatchMenuItem("Message", "Send a direct message", Icons.Filled.Email, onMessage),
        StudentBatchMenuItem("Generate Report", "Create a professional PDF student report", Icons.Filled.Article, onGenerateReport),
        StudentBatchMenuItem("Registration Form", "You can generate student registration form here", Icons.Filled.Article, onRegistrationForm),
        StudentBatchMenuItem("Fees Summary", "Create a professional PDF with fees, payments and pending due", Icons.Filled.Article, onFeeSummary),
        StudentBatchMenuItem("Generate ID card", "You can generate student ID card.", Icons.Filled.Badge, onGenerateIdCard)
        ))
        if (canDeleteStudent) {
            add(StudentBatchMenuItem("Archive student", "Retain history with recovery and audit", Icons.Filled.Delete, onDeleteStudent, isDestructive = true))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(14.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Student Batch Menu",
                    color = Cyan,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFFEF4444), modifier = Modifier.size(30.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                items.forEachIndexed { index, item ->
                    StudentBatchMenuRow(item)
                    if (index != items.lastIndex) {
                        HorizontalDivider(color = DashboardLine)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun StudentBatchMenuRow(item: StudentBatchMenuItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 74.dp)
            .clickable(onClick = item.onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            item.icon,
            contentDescription = null,
            tint = if (item.isDestructive) DangerRed else Cyan,
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(item.subtitle, color = TextMuted.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun AdmissionFormReadyDialog(
    onDismiss: () -> Unit,
    onPrint: () -> Unit,
    onWhatsApp: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = Cyan, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text("Admission Form Ready", color = TextWhite, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Text(
                "A print-ready PDF admission form has been created with institute and student details.",
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onWhatsApp,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, WAGreen),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WAGreen),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Filled.Whatsapp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("WhatsApp", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Button(
                    onClick = onPrint,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color(0xFF07111F))
                ) {
                    Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Print", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close", color = TextMuted)
            }
        }
    )
}

@Composable
private fun StudentPdfReadyDialog(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    onPrint: () -> Unit,
    onWhatsApp: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = Cyan, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, color = TextWhite, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Text(description, color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onWhatsApp,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, WAGreen),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WAGreen),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Filled.Whatsapp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("WhatsApp", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Button(
                    onClick = onPrint,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color(0xFF07111F))
                ) {
                    Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Print", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close", color = TextMuted)
            }
        }
    )
}

@Composable
private fun StudentMessageDialog(
    message: String,
    onMessageChange: (String) -> Unit,
    onUseAdmissionWelcome: () -> Unit,
    onDismiss: () -> Unit,
    onSendSms: () -> Unit,
    onSendWhatsApp: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(14.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Message", color = AccentAmber, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFFEF4444), modifier = Modifier.size(30.dp))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Cyan.copy(alpha = 0.08f))
                        .border(1.dp, Cyan.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Text("Quick message", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Send a ready-made admission welcome and congratulations message.", color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onUseAdmissionWelcome,
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Cyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                    ) {
                        Icon(Icons.Filled.Celebration, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Use Admission Welcome", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = onMessageChange,
                    placeholder = { Text("Write a custom message", color = TextMuted.copy(alpha = 0.7f)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    colors = darkFieldColors(),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onSendWhatsApp,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, AccentAmber),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber)
                ) {
                    Text("WhatsApp", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onSendSms,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color(0xFF201A05))
                ) {
                    Text("SMS", fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

private fun shareStudentText(context: android.content.Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

private fun copyTextToClipboard(context: android.content.Context, label: String, value: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) {
        Toast.makeText(context, "Could not access the clipboard.", Toast.LENGTH_SHORT).show()
        return
    }
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

private const val BATCHFEE_PLAY_STORE_URL =
    "https://play.google.com/store/apps/details?id=com.batchfee.edu"

private fun buildStudentLoginInfoText(
    student: StudentEntity,
    instituteSignature: String,
    oneTimePassword: String? = null
): String = appendInstituteSignature(
    buildString {
        appendLine("Student Login")
        appendLine("Student: ${student.fullName}")
        appendLine("Student ID: ${student.studentCode}")
        appendLine("App Access: ${if (student.isAppAccessEnabled) "Enabled" else "Not enabled"}")
        if (oneTimePassword != null) {
            appendLine("New Password: $oneTimePassword")
            append("Keep this password secure. It is shown only once in the app.")
        } else {
            append("For security, the current password is not shared. Reset it from Login Access if needed.")
        }
        appendLine()
        appendLine()
        appendLine("Download BatchFee App:")
        append(BATCHFEE_PLAY_STORE_URL)
    },
    instituteSignature
)

private fun printStudentText(context: android.content.Context, title: String, text: String) {
    val reportTitle = title.ifBlank { "Student Report" }
    val webView = android.webkit.WebView(context)
    webView.webViewClient = object : android.webkit.WebViewClient() {
        override fun onPageFinished(view: android.webkit.WebView, url: String?) {
            val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
            printManager.print(
                reportTitle,
                view.createPrintDocumentAdapter(reportTitle),
                android.print.PrintAttributes.Builder().build()
            )
        }
    }
    val body = android.text.Html.escapeHtml(text).replace("\n", "<br/>")
    val html = """
        <html>
            <body style="font-family:sans-serif;padding:24px;color:#111;">
                <h2>$reportTitle</h2>
                <p style="font-size:14px;line-height:1.55;">$body</p>
            </body>
        </html>
    """.trimIndent()
    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
}

private fun sendStudentMessage(
    context: android.content.Context,
    phone: String?,
    message: String,
    instituteSignature: String,
    useWhatsApp: Boolean
) {
    val body = appendInstituteSignature(message.trim(), instituteSignature)
    if (useWhatsApp) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(buildWhatsAppUrl(phone, body))))
    } else {
        val cleanPhone = phone?.filter(Char::isDigit).orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$cleanPhone")).apply {
            putExtra("sms_body", body)
        }
        context.startActivity(intent)
    }
}

private fun buildStudentReportText(
    student: StudentEntity,
    batches: List<BatchEntity>,
    totalPaid: Double,
    totalDue: Double,
    instituteSignature: String
): String =
    buildString {
        appendLine("Student Report")
        appendLine("Name: ${student.fullName}")
        appendLine("ID: ${student.studentCode}")
        appendLine("Status: ${student.status}")
        appendLine("Phone: ${student.phone ?: "N/A"}")
        appendLine("Class: ${student.className ?: "N/A"}")
        appendLine("Batches: ${batches.joinToString { it.name }.ifBlank { "N/A" }}")
        appendLine("Collected Fees: ${totalPaid.toLong()}")
        appendLine("Due Fees: ${totalDue.toLong()}")
        if (instituteSignature.isNotBlank()) appendLine(instituteSignature)
    }

private fun buildStudentRegistrationText(student: StudentEntity, instituteSignature: String): String =
    buildString {
        appendLine("Student Registration Form")
        appendLine("Name: ${student.fullName}")
        appendLine("Student ID: ${student.studentCode}")
        appendLine("Phone: ${student.phone ?: "N/A"}")
        appendLine("Guardian: ${student.guardianName ?: "N/A"}")
        appendLine("Address: ${student.address ?: "N/A"}")
        appendLine("School: ${student.schoolName ?: "N/A"}")
        appendLine("Class: ${student.className ?: "N/A"}")
        if (instituteSignature.isNotBlank()) appendLine(instituteSignature)
    }

private fun buildStudentFeeSummaryText(student: StudentEntity, totalPaid: Double, totalDue: Double, instituteSignature: String): String =
    buildString {
        appendLine("Student Fee Summary")
        appendLine("Name: ${student.fullName}")
        appendLine("ID: ${student.studentCode}")
        appendLine("Collected Fees: ${totalPaid.toLong()}")
        appendLine("Pending Dues: ${totalDue.toLong()}")
        if (instituteSignature.isNotBlank()) appendLine(instituteSignature)
    }

@Composable
private fun StudentDashboardContent(
    student: StudentEntity,
    batches: List<BatchEntity>,
    totalPaid: Double,
    totalDue: Double,
    paymentHistory: List<PaymentEntity>,
    monthAttendance: List<com.batchfee.edu.data.models.AttendanceEntity>,
    activeEnrollments: List<BatchStudentEntity>,
    context: android.content.Context,
    instituteSignature: String,
    insightsVisible: Boolean,
    onToggleInsights: () -> Unit,
    onAssignBatch: () -> Unit,
    onSetCustomMonthlyFee: (() -> Unit)?,
    onSetOrResetPassword: () -> Unit,
    onShareLoginInfo: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val primaryBatch = batches.firstOrNull()
    val primaryEnrollment = activeEnrollments.firstOrNull { it.batchId == primaryBatch?.id }
    val whatsappNumber = student.notes?.let { notes ->
        notes.lineSequence()
            .firstOrNull { it.startsWith("WhatsApp: ") }
            ?.removePrefix("WhatsApp: ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    } ?: student.phone.orEmpty()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Brush.horizontalGradient(listOf(CardBgAlt, Color(0xFF102235))))
            .border(1.dp, DashboardLine, RoundedCornerShape(15.dp))
            .clickable(onClick = onAssignBatch)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Cyan.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Groups, contentDescription = null, tint = Cyan, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Assign Batch", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (batches.isEmpty()) "No batch selected" else "${batches.size} batch${if (batches.size == 1) "" else "es"} enrolled",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(ElectricBlue, Cyan))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF101B2F), CardBg)))
            .border(1.dp, DashboardLine, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(ElectricBlue, Cyan)))
                        .border(2.dp, SkyBlue.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (!student.photoUri.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(FirebaseStorageImageUploadHelper.displaySource(context, student.photoUri))
                                .crossfade(true)
                                .build(),
                            contentDescription = "Student photo",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Filled.School, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            student.status.replaceFirstChar { it.uppercase() },
                            color = WAGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(WAGreen.copy(alpha = 0.12f))
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        primaryBatch?.name ?: "No Batch Assigned",
                        color = TextWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val displayMonthlyFee = primaryEnrollment?.customMonthlyFeeAmount
                        ?: primaryBatch?.monthlyFeeAmount
                    Text(
                        if (displayMonthlyFee != null) "Monthly fee · BDT ${displayMonthlyFee.toLong()}" else "Assign a batch to see fee details",
                        color = TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = DashboardLine)
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ProfileMetric(Icons.Filled.CalendarMonth, "Joined", dateFormat.format(Date(student.admissionDateMs)), Modifier.weight(1f))
                ProfileMetric(
                    Icons.Filled.CalendarMonth,
                    "Ends",
                    primaryBatch?.endDateMs?.let { dateFormat.format(Date(it)) } ?: "Running",
                    Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ProfileMetric(Icons.Filled.Savings, "Collected", "BDT ${totalPaid.toLong()}", Modifier.weight(1f))
                ProfileMetric(Icons.Filled.Paid, "Due", "BDT ${totalDue.toLong()}", Modifier.weight(1f))
            }
            if (primaryBatch != null && onSetCustomMonthlyFee != null) {
                Spacer(Modifier.height(10.dp))
                val customAmount = primaryEnrollment?.customMonthlyFeeAmount
                val shownAmount = customAmount ?: primaryBatch.monthlyFeeAmount
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(Cyan.copy(alpha = 0.07f))
                        .border(1.dp, DashboardLine, RoundedCornerShape(11.dp))
                        .clickable(onClick = onSetCustomMonthlyFee)
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Payments, contentDescription = null, tint = Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Monthly fee", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (customAmount != null) "Custom · ${primaryEnrollment.customFeeReason ?: "Adjusted fee"}" else "Batch standard fee",
                            color = TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text("BDT ${shownAmount.toLong()}", color = if (customAmount != null) AccentAmber else Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Edit, contentDescription = "Edit monthly fee", tint = Cyan, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(ElectricBlue, Cyan)))
                    .clickable(onClick = onToggleInsights),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (insightsVisible) Icons.Filled.KeyboardArrowUp else Icons.Filled.Analytics,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (insightsVisible) "Hide Reports & Details" else "View Reports & Details",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (insightsVisible) {
        Spacer(Modifier.height(14.dp))
        StudentInsightsPanel(
            student = student,
            monthAttendance = monthAttendance,
            paymentHistory = paymentHistory,
            totalPaid = totalPaid,
            totalDue = totalDue,
            context = context,
            instituteSignature = instituteSignature
        )
    }

    Spacer(Modifier.height(14.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SmallDashboardTile("Exams (0)", Modifier.weight(1f))
        SmallDashboardTile("Homework (0)", Modifier.weight(1f))
    }

    Spacer(Modifier.height(14.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1426)),
        border = BorderStroke(1.dp, DashboardLine)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ProfileSectionTitle("Personal details")
            Spacer(Modifier.height(10.dp))
            TwoColumnInfo(
                leftLabel = "Guardian",
                leftValue = student.guardianName ?: "N/A",
                rightLabel = "Date of birth",
                rightValue = student.dateOfBirthMs?.let { dateFormat.format(Date(it)) } ?: "N/A"
            )
            Spacer(Modifier.height(8.dp))
            TwoColumnInfo(
                "Gender",
                student.gender ?: "N/A",
                "Blood group",
                student.bloodGroup ?: "N/A"
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = DashboardLine)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileSectionTitle("Contact", Modifier.weight(1f))
                IconButton(onClick = {
                    student.phone?.takeIf { it.isNotBlank() }?.let { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it"))) }
                }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Phone, contentDescription = "Call student", tint = Cyan, modifier = Modifier.size(19.dp)) }
                IconButton(onClick = {
                    val email = student.email.orEmpty()
                    if (email.isNotBlank()) context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
                }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Email, contentDescription = "Email student", tint = Cyan, modifier = Modifier.size(19.dp)) }
                IconButton(onClick = {
                    if (whatsappNumber.isNotBlank()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(buildWhatsAppUrl(whatsappNumber, ""))))
                }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Whatsapp, contentDescription = "Message on WhatsApp", tint = WAGreen, modifier = Modifier.size(19.dp)) }
            }
            Spacer(Modifier.height(10.dp))
            CompactContactInfoRow("Phone", student.phone ?: "N/A", "WhatsApp", whatsappNumber.ifBlank { "N/A" })
            student.email?.takeIf { it.isNotBlank() }?.let { email ->
                Spacer(Modifier.height(8.dp))
                ProfileInfoBlock("Email", email, Modifier.fillMaxWidth(), singleLineValue = true)
            }
            Spacer(Modifier.height(8.dp))
            ProfileInfoBlock("Address", student.address ?: "N/A", Modifier.fillMaxWidth())

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = DashboardLine)
            ProfileSectionTitle("Academic")
            Spacer(Modifier.height(10.dp))
            TwoColumnInfo(
                "Class",
                student.className ?: "N/A",
                "Admission",
                dateFormat.format(Date(student.admissionDateMs))
            )
            Spacer(Modifier.height(8.dp))
            ProfileInfoBlock("Institute", student.schoolName ?: "N/A", Modifier.fillMaxWidth())
        }
    }

    Spacer(Modifier.height(14.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1426)),
        border = BorderStroke(1.dp, DashboardLine)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ProfileSectionTitle("Login access")
            Spacer(Modifier.height(10.dp))
            LoginAccessStatusRow(isEnabled = student.isAppAccessEnabled)
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = DashboardLine
            )
            CopyableProfileInfoBlock(
                label = "Student ID",
                value = student.studentCode,
                onCopy = { copyTextToClipboard(context, "Student ID", student.studentCode) },
                modifier = Modifier.fillMaxWidth(),
                singleLineValue = true
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSetOrResetPassword,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
            ) {
                Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (student.isAppAccessEnabled) "Reset Password" else "Set New Password",
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onShareLoginInfo,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                border = BorderStroke(1.dp, DashboardLine)
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share Login Info", fontWeight = FontWeight.Bold)
            }
        }
    }

    Spacer(Modifier.height(24.dp))
}

@Composable
private fun CustomMonthlyFeeDialog(
    enrollments: List<BatchStudentEntity>,
    batches: List<BatchEntity>,
    onDismiss: () -> Unit,
    onSave: (BatchStudentEntity, () -> Unit, (String) -> Unit) -> Unit
) {
    var selectedEnrollmentId by remember(enrollments) { mutableStateOf(enrollments.firstOrNull()?.id) }
    val selectedEnrollment = enrollments.firstOrNull { it.id == selectedEnrollmentId }
    val selectedBatch = batches.firstOrNull { it.id == selectedEnrollment?.batchId }
    var amountText by remember { mutableStateOf("") }
    var reasonText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val templates = remember {
        listOf(
            "Sibling discount",
            "Financial hardship",
            "Merit scholarship",
            "Staff family",
            "Special offer",
            "Other adjustment"
        )
    }
    val effectivePeriod = remember { MonthlyDueCalculator.periodFor(System.currentTimeMillis()) }

    LaunchedEffect(selectedEnrollment?.id) {
        amountText = selectedEnrollment?.customMonthlyFeeAmount?.let { amount ->
            if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()
        }.orEmpty()
        reasonText = selectedEnrollment?.customFeeReason.orEmpty()
        errorMessage = null
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor = CardBg,
        icon = { Icon(Icons.Filled.Payments, contentDescription = null, tint = Cyan, modifier = Modifier.size(28.dp)) },
        title = { Text("Set Monthly Fee", color = TextWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (enrollments.size > 1) {
                    Text("Select batch", color = TextMuted, fontSize = 12.sp)
                    enrollments.forEach { enrollment ->
                        val batch = batches.firstOrNull { it.id == enrollment.batchId }
                        FilterChip(
                            selected = enrollment.id == selectedEnrollmentId,
                            onClick = { if (!isSaving) selectedEnrollmentId = enrollment.id },
                            label = { Text(batch?.name ?: "Batch", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.18f),
                                selectedLabelColor = Cyan,
                                containerColor = CardBgAlt,
                                labelColor = TextMuted
                            )
                        )
                    }
                }
                if (selectedEnrollment == null || selectedBatch == null) {
                    Text("No active batch is available for this student.", color = DangerRed, fontSize = 13.sp)
                } else {
                    Text("${selectedBatch.name} · Batch fee BDT ${selectedBatch.monthlyFeeAmount.toLong()}", color = TextMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it; errorMessage = null },
                        label = { Text("Custom monthly fee (BDT)") },
                        placeholder = { Text("e.g. 700") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        colors = darkFieldColors()
                    )
                    Text("Reason", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    templates.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { template ->
                                FilterChip(
                                    selected = reasonText.equals(template, ignoreCase = true),
                                    onClick = { reasonText = template; errorMessage = null },
                                    label = { Text(template, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Cyan.copy(alpha = 0.18f),
                                        selectedLabelColor = Cyan,
                                        containerColor = CardBgAlt,
                                        labelColor = TextMuted
                                    )
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    OutlinedTextField(
                        value = reasonText,
                        onValueChange = { reasonText = it.take(120); errorMessage = null },
                        label = { Text("Reason details") },
                        placeholder = { Text("Choose a template or write your own") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = darkFieldColors()
                    )
                    Text(
                        "Applies from $effectivePeriod. Previous payments and receipts will not change. The reason stays private to the institute.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
                errorMessage?.let { Text(it, color = DangerRed, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val enrollment = selectedEnrollment ?: return@Button
                    val batch = selectedBatch ?: return@Button
                    val amount = amountText.trim().toDoubleOrNull()
                    when {
                        amount == null || amount <= 0.0 -> errorMessage = "Enter a valid monthly fee."
                        amount > batch.monthlyFeeAmount -> errorMessage = "Custom fee cannot be more than the batch fee."
                        reasonText.trim().length < 3 -> errorMessage = "Choose or write a reason for the reduced fee."
                        else -> {
                            isSaving = true
                            onSave(
                                enrollment.copy(
                                    customMonthlyFeeAmount = amount,
                                    customFeeReason = reasonText.trim(),
                                    customFeeEffectiveFromPeriod = effectivePeriod
                                ),
                                { isSaving = false; onDismiss() },
                                { message -> isSaving = false; errorMessage = message }
                            )
                        }
                    }
                },
                enabled = !isSaving && selectedEnrollment != null && selectedBatch != null,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
            ) {
                if (isSaving) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                else Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (selectedEnrollment?.customMonthlyFeeAmount != null) {
                    TextButton(
                        onClick = {
                            val enrollment = selectedEnrollment ?: return@TextButton
                            isSaving = true
                            onSave(
                                enrollment.copy(
                                    customMonthlyFeeAmount = null,
                                    customFeeReason = null,
                                    customFeeEffectiveFromPeriod = null
                                ),
                                { isSaving = false; onDismiss() },
                                { message -> isSaving = false; errorMessage = message }
                            )
                        },
                        enabled = !isSaving
                    ) { Text("Use batch fee", color = AccentAmber) }
                }
                TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel", color = TextMuted) }
            }
        }
    )
}

@Composable
private fun StudentInsightsPanel(
    student: StudentEntity,
    monthAttendance: List<com.batchfee.edu.data.models.AttendanceEntity>,
    paymentHistory: List<PaymentEntity>,
    totalPaid: Double,
    totalDue: Double,
    context: android.content.Context,
    instituteSignature: String
) {
    val monthLabel = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date()) }
    val present = monthAttendance.count { it.status.equals("present", ignoreCase = true) }
    val absent = monthAttendance.count { it.status.equals("absent", ignoreCase = true) }
    val leave = monthAttendance.count { it.status.equals("leave", ignoreCase = true) }
    val holiday = monthAttendance.count { it.status.equals("holiday", ignoreCase = true) }
    val marked = monthAttendance.size.coerceAtLeast(1)
    val attendanceText = buildMonthlyAttendanceReportText(student, monthLabel, present, absent, leave, holiday, monthAttendance.size, instituteSignature)
    val feeText = buildDetailedFeeReportText(student, paymentHistory, totalPaid, totalDue, instituteSignature)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1426)),
        border = BorderStroke(1.dp, DashboardLine)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Monthly Attendance", color = TextWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(monthLabel, color = TextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                InsightStat("Present", present, WAGreen, Modifier.weight(1f))
                InsightStat("Absent", absent, Color(0xFFEF4444), Modifier.weight(1f))
                InsightStat("Leave", leave, SkyBlue, Modifier.weight(1f))
                InsightStat("Holiday", holiday, TextMuted, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { present.toFloat() / marked.toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(20.dp)),
                color = WAGreen,
                trackColor = BorderSub
            )
            Spacer(Modifier.height(12.dp))
            ReportActionRow(
                context = context,
                phone = student.phone,
                title = "Monthly Attendance Report",
                body = attendanceText,
                instituteSignature = instituteSignature
            )

            Spacer(Modifier.height(22.dp))
            HorizontalDivider(color = DashboardLine)
            Spacer(Modifier.height(18.dp))

            Text("Fee Details", color = TextWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Submitted fee history", color = TextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            if (paymentHistory.isEmpty()) {
                Text("No fee payments found yet.", color = TextMuted, fontSize = 14.sp)
            } else {
                paymentHistory.take(8).forEach { payment ->
                    FeePaymentRow(payment)
                    if (payment != paymentHistory.take(8).last()) {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ProfileInfoBlock("Total Paid", totalPaid.toLong().toString(), Modifier.weight(1f))
                ProfileInfoBlock("Total Due", totalDue.toLong().toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            ReportActionRow(
                context = context,
                phone = student.phone,
                title = "Student Fee Details",
                body = feeText,
                instituteSignature = instituteSignature
            )
        }
    }
}

@Composable
private fun InsightStat(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value.toString(), color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun FeePaymentRow(payment: PaymentEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBgAlt)
            .border(1.dp, DashboardLine, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Cyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.ReceiptLong, contentDescription = null, tint = Cyan, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(payment.paymentDateMs)),
                color = TextWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text("${payment.paymentMethod.replaceFirstChar { it.uppercase() }} • ${payment.receiptNumber}", color = TextMuted, fontSize = 12.sp)
        }
        Text(payment.amount.toLong().toString(), color = WAGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReportActionRow(
    context: android.content.Context,
    phone: String?,
    title: String,
    body: String,
    instituteSignature: String
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ReportActionButton(Icons.Filled.Share, "Share", Modifier.weight(1f)) {
            shareStudentText(context, title, body)
        }
        ReportActionButton(Icons.Filled.Whatsapp, "WhatsApp", Modifier.weight(1f)) {
            sendStudentMessage(context, phone, body, instituteSignature, useWhatsApp = true)
        }
        ReportActionButton(Icons.Filled.Sms, "SMS", Modifier.weight(1f)) {
            sendStudentMessage(context, phone, body, instituteSignature, useWhatsApp = false)
        }
        ReportActionButton(Icons.Filled.Print, "Print", Modifier.weight(1f)) {
            printStudentText(context, title, body)
        }
    }
}

@Composable
private fun ReportActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DashboardSoft)
            .border(1.dp, DashboardLine, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (label == "WhatsApp") WAGreen else Cyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = TextWhite, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun currentMonthRangeMs(): Pair<Long, Long> {
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    val start = calendar.timeInMillis
    calendar.add(Calendar.MONTH, 1)
    calendar.add(Calendar.MILLISECOND, -1)
    return start to calendar.timeInMillis
}

private fun buildMonthlyAttendanceReportText(
    student: StudentEntity,
    monthLabel: String,
    present: Int,
    absent: Int,
    leave: Int,
    holiday: Int,
    totalMarked: Int,
    instituteSignature: String
): String =
    buildString {
        appendLine("Monthly Attendance Report")
        appendLine("Student: ${student.fullName}")
        appendLine("ID: ${student.studentCode}")
        appendLine("Month: $monthLabel")
        appendLine("Present: $present")
        appendLine("Absent: $absent")
        appendLine("Leave: $leave")
        appendLine("Holiday: $holiday")
        appendLine("Total marked days: $totalMarked")
        if (instituteSignature.isNotBlank()) appendLine(instituteSignature)
    }

private fun buildDetailedFeeReportText(
    student: StudentEntity,
    payments: List<PaymentEntity>,
    totalPaid: Double,
    totalDue: Double,
    instituteSignature: String
): String =
    buildString {
        appendLine("Student Fee Details")
        appendLine("Student: ${student.fullName}")
        appendLine("ID: ${student.studentCode}")
        appendLine("Total Paid: ${totalPaid.toLong()}")
        appendLine("Total Due: ${totalDue.toLong()}")
        appendLine()
        if (payments.isEmpty()) {
            appendLine("No payments found.")
        } else {
            appendLine("Payment History:")
            payments.forEach { payment ->
                appendLine("- ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(payment.paymentDateMs))}: ${payment.amount.toLong()} (${payment.paymentMethod}, ${payment.receiptNumber})")
            }
        }
        if (instituteSignature.isNotBlank()) appendLine(instituteSignature)
    }

@Composable
private fun ProfileMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Cyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Cyan, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(7.dp))
        Column {
            Text(label, color = TextMuted, fontSize = 10.sp, maxLines = 1)
            Text(value, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun SmallDashboardTile(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(CardBgAlt, Color(0xFF0C2032))))
            .border(1.dp, DashboardLine, RoundedCornerShape(13.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Cyan.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.MenuBook, contentDescription = null, tint = Cyan, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(title, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun ProfileSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        color = TextWhite,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

@Composable
private fun TwoColumnInfo(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
    singleLineValues: Boolean = false,
    onRightCopy: (() -> Unit)? = null
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        ProfileInfoBlock(leftLabel, leftValue, Modifier.weight(1f), singleLineValue = singleLineValues)
        if (onRightCopy == null) {
            ProfileInfoBlock(rightLabel, rightValue, Modifier.weight(1f), singleLineValue = singleLineValues)
        } else {
            CopyableProfileInfoBlock(
                label = rightLabel,
                value = rightValue,
                onCopy = onRightCopy,
                modifier = Modifier.weight(1f),
                singleLineValue = singleLineValues
            )
        }
    }
}

private fun buildStudentAdmissionWelcomeMessage(student: StudentEntity, instituteName: String = "", instituteContact: String = ""): String {
    val template = com.example.domain.MessageTemplateStore.defaultFor(com.example.domain.MessageTemplateStore.TYPE_WELCOME)
    return template?.let {
        com.example.domain.MessageTemplateStore.apply(
            it,
            mapOf(
                "guardianName" to "Guardian",
                "studentName" to student.fullName,
                "studentCode" to student.studentCode.ifBlank { student.id },
                "className" to student.className.orEmpty(),
                "instituteName" to instituteName,
                "instituteContact" to instituteContact
            )
        )
    } ?: buildString {
        append("Dear Guardian,\n\nWelcome! ")
        append(student.fullName)
        append(" is now admitted to our institute.")
        append("\n\nStudent ID: ${student.studentCode.ifBlank { student.id }}")
        student.className?.takeIf { it.isNotBlank() }?.let { append("\nClass: $it") }
        if (instituteContact.isNotBlank()) append("\nContact: $instituteContact")
    }
}

@Composable
private fun CompactContactInfoRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        CompactContactBlock(leftLabel, leftValue, Modifier.weight(1f))
        CompactContactBlock(rightLabel, rightValue, Modifier.weight(1f))
    }
}

@Composable
private fun CompactContactBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CardBgAlt.copy(alpha = 0.72f))
            .border(1.dp, BorderSub, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(label, color = TextMuted, fontSize = 10.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = TextWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun ProfileInfoBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    singleLineValue: Boolean = false
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardBgAlt.copy(alpha = 0.72f))
            .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
            .padding(
                horizontal = if (singleLineValue) 10.dp else 12.dp,
                vertical = 9.dp
            )
    ) {
        Text(label, color = TextMuted, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = TextWhite,
            fontSize = if (singleLineValue) 10.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = if (singleLineValue) 1 else Int.MAX_VALUE,
            softWrap = !singleLineValue,
            overflow = TextOverflow.Ellipsis
        )
    }
}

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

@Composable
private fun CopyableProfileInfoBlock(
    label: String,
    value: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    singleLineValue: Boolean = false
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardBgAlt.copy(alpha = 0.72f))
            .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
            .padding(
                start = if (singleLineValue) 10.dp else 12.dp,
                top = 8.dp,
                end = 6.dp,
                bottom = 8.dp
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextMuted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy $label",
                    tint = Cyan,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Text(
            value,
            color = TextWhite,
            fontSize = if (singleLineValue) 9.sp else 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = if (singleLineValue) 1 else Int.MAX_VALUE,
            softWrap = !singleLineValue,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LoginAccessStatusRow(isEnabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("App Access", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (isEnabled) "Student can sign in to the app" else "No student sign-in is active",
                color = TextMuted,
                fontSize = 12.sp
            )
        }
        val statusColor = if (isEnabled) Teal else TextMuted
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(statusColor.copy(alpha = 0.14f))
                .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isEnabled) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                if (isEnabled) "Enabled" else "Not enabled",
                color = statusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
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

