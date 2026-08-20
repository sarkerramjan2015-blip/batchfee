package com.batchfee.edu.ui.dashboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.media.FirebaseStorageImageUploadHelper
import com.batchfee.edu.data.firestore.EnquirySyncHelper
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.firestore.InstituteRefreshScope
import com.batchfee.edu.data.models.BatchEntity
import com.batchfee.edu.data.models.BatchStudentEntity
import com.batchfee.edu.data.models.FeeEntity
import com.batchfee.edu.data.models.InstituteEntity
import com.batchfee.edu.data.models.StudentEntity

import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.MonthlyDueCalculator
import com.google.firebase.firestore.FirebaseFirestore
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.batchfee.edu.ui.components.BatchFeeBottomNav
import com.batchfee.edu.domain.AccessControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import com.batchfee.edu.ui.attendance.AttendanceViewModel
import com.batchfee.edu.ui.attendance.AttendanceViewModelFactory
import com.batchfee.edu.ui.attendance.BatchAttendanceSummary
import com.batchfee.edu.ui.attendance.StaffAttendanceSummary
import com.batchfee.edu.ui.attendance.getCurrentMonthRange

private val DashboardBg = Color(0xFF07111F)
private val DashboardCard = Color(0xFF0F172A)
private val DashboardCardAlt = Color(0xFF111827)
private val DashboardStroke = Color(0xFF1E293B)
private val AccentBlue = Color(0xFF3B82F6)
private val AccentCyan = Color(0xFF22D3EE)
private val AccentSky = Color(0xFF38BDF8)
private val AccentViolet = Color(0xFF6366F1)
private val AccentGray  = Color(0xFF64748B)
private val AccentGreen = Color(0xFF22C55E)
private val AccentRed = Color(0xFFEF4444)
private val AccentAmber = Color(0xFFF59E0B)
private val WarningAmber   = Color(0xFFD97706)
private val AccentOrange   = Color(0xFFF97316)
private val AccentPink = Color(0xFFEC4899)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)
private val TextMuted = Color(0xFF64748B)

data class UpcomingBirthday(
    val studentName: String,
    val className: String?,
    val daysUntil: Int,
    val photoUri: String?
)

data class BirthdayReminderSummary(
    val today: List<UpcomingBirthday> = emptyList(),
    val tomorrowCount: Int = 0,
    val upcomingCount: Int = 0
)
data class ActivityItem(val title: String, val subtitle: String, val timeMs: Long, val icon: ImageVector)
private data class AddMenuOption(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String
)

private data class DashboardBootstrapSnapshot(
    val institute: InstituteEntity,
    val studentCount: Int,
    val batchCount: Int,
    val staffCount: Int
)

private data class DashboardDueInput(
    val fees: List<FeeEntity>,
    val students: List<StudentEntity>,
    val batches: List<BatchEntity>,
    val billingEnrollments: List<BatchStudentEntity>
)

private data class DashboardDueResult(
    val pendingFeesCount: Int,
    val summary: DueFeeSummary
)

private fun formatRelativeTime(timeMs: Long): String {
    val diff = System.currentTimeMillis() - timeMs
    return when {
        diff < 60000 -> "just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        else -> "${diff / 86400000}d ago"
    }
}

data class FinancialSummary(
    val todayIncome: Double = 0.0,
    val todayExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
    val monthExpense: Double = 0.0,
    val lifetimeIncome: Double = 0.0,
    val lifetimeExpense: Double = 0.0
)

data class DueFeeSummary(
    val activeCount: Int = 0,
    val activeAmount: Double = 0.0,
    val closeCount: Int = 0,
    val closeAmount: Double = 0.0
)

data class EnquirySummary(
    val total: Int = 0,
    val active: Int = 0,
    val close: Int = 0,
    val followUp: Int = 0,
    val todayFollowUp: Int = 0,
    val overdueFollowUp: Int = 0
)

private fun dashboardStartOfDay(timeMs: Long): Long = Calendar.getInstance().apply {
    timeInMillis = timeMs
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dashboardMillisecondsUntilNextDay(now: Long): Long {
    val next = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }
    return (next.timeInMillis - now).coerceAtLeast(1_000L)
}

private fun isClosedStudentStatus(status: String?): Boolean {
    val normalized = status.orEmpty().trim().lowercase()
    return normalized == "close" || normalized == "closed" || normalized == "inactive"
}

/** Pure dashboard math runs on Dispatchers.Default, never on Compose's UI thread. */
private fun calculateDashboardDue(input: DashboardDueInput): DashboardDueResult {
    val studentsById = input.students.associateBy { it.id }
    val batchesById = input.batches.associateBy { it.id }
    val enrollmentsByStudent = input.billingEnrollments
        .groupBy { it.studentId }
        .mapValues { (_, enrollments) ->
            enrollments.mapNotNull { enrollment ->
                batchesById[enrollment.batchId]?.let { batch -> enrollment to batch }
            }
        }
    val monthlyFeesByStudentAndBatch = input.fees
        .filter { fee -> MonthlyDueCalculator.isMonthlyFeeType(fee.feeType) }
        .groupBy { fee -> fee.studentId to fee.batchId }

    val activeStudentIds = mutableSetOf<String>()
    val closedStudentIds = mutableSetOf<String>()
    var activeNonMonthlyDue = 0.0
    var closedNonMonthlyDue = 0.0
    var activeMonthlyDue = 0.0
    var closedMonthlyDue = 0.0

    input.fees.asSequence()
        .filter { fee -> fee.dueAmount > 0.0 && !MonthlyDueCalculator.isMonthlyFeeType(fee.feeType) }
        .forEach { fee ->
            // Ignore orphaned historic records, matching the Due Fees screen.
            val student = studentsById[fee.studentId] ?: return@forEach
            if (isClosedStudentStatus(student.status)) {
                closedNonMonthlyDue += fee.dueAmount
                closedStudentIds += student.id
            } else {
                activeNonMonthlyDue += fee.dueAmount
                activeStudentIds += student.id
            }
        }

    input.fees.asSequence()
        .filter { fee ->
            fee.dueAmount > 0.0 &&
                MonthlyDueCalculator.isMonthlyInstallmentDue(fee.feeType, fee.feePeriod)
        }
        .forEach { fee ->
            val student = studentsById[fee.studentId] ?: return@forEach
            if (isClosedStudentStatus(student.status)) {
                closedMonthlyDue += fee.dueAmount
                closedStudentIds += student.id
            } else {
                activeMonthlyDue += fee.dueAmount
                activeStudentIds += student.id
            }
        }

    input.students.forEach { student ->
        val isClosed = isClosedStudentStatus(student.status)
        enrollmentsByStudent[student.id].orEmpty().forEach { (enrollment, batch) ->
            if (batch.monthlyFeeAmount <= 0.0) return@forEach
            val items = MonthlyDueCalculator.computeMonthlyOutstandingItems(
                admissionDateMs = enrollment.joinedAtMs,
                monthlyFeeAmount = batch.monthlyFeeAmount,
                batchId = batch.id,
                batchName = batch.name,
                existingMonthlyFees = monthlyFeesByStudentAndBatch[student.id to batch.id].orEmpty(),
                firstMonthFeePeriod = enrollment.firstMonthFeePeriod,
                firstMonthFeeAmount = enrollment.firstMonthFeeAmount,
                customMonthlyFeeAmount = enrollment.customMonthlyFeeAmount,
                customFeeEffectiveFromPeriod = enrollment.customFeeEffectiveFromPeriod,
                billingEndedAtMs = enrollment.leftAtMs
            )
            items.forEach { item ->
                if (isClosed) {
                    closedMonthlyDue += item.outstanding
                    closedStudentIds += student.id
                } else {
                    activeMonthlyDue += item.outstanding
                    activeStudentIds += student.id
                }
            }

            // Admission fee is immediately due for an active enrollment even
            // before its first collection creates the canonical fee document.
            val admissionAlreadyCreated = input.fees.any { fee ->
                fee.studentId == student.id && fee.batchId == batch.id &&
                    fee.feeType.equals("admission_fee", ignoreCase = true)
            }
            if (enrollment.status.equals("active", ignoreCase = true) &&
                batch.admissionFeeAmount > 0.0 && !admissionAlreadyCreated) {
                if (isClosed) {
                    closedNonMonthlyDue += batch.admissionFeeAmount
                    closedStudentIds += student.id
                } else {
                    activeNonMonthlyDue += batch.admissionFeeAmount
                    activeStudentIds += student.id
                }
            }
        }
    }

    return DashboardDueResult(
        pendingFeesCount = (activeStudentIds + closedStudentIds).size,
        summary = DueFeeSummary(
            activeCount = activeStudentIds.size,
            activeAmount = activeNonMonthlyDue + activeMonthlyDue,
            closeCount = closedStudentIds.size,
            closeAmount = closedNonMonthlyDue + closedMonthlyDue
        )
    )
}

private fun calculateFinancialSummary(
    payments: List<com.batchfee.edu.data.models.PaymentEntity>,
    expenses: List<com.batchfee.edu.data.models.ExpenseEntity>
): FinancialSummary {
    val now = java.util.Calendar.getInstance()
    val startOfDay = now.apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val startOfMonth = now.apply {
        set(java.util.Calendar.DAY_OF_MONTH, 1)
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    var todayIncome = 0.0
    var todayExpense = 0.0
    var monthIncome = 0.0
    var monthExpense = 0.0
    var lifetimeIncome = 0.0
    var lifetimeExpense = 0.0

    // A reversed payment remains in history for audit, but it is not income.
    payments.filter { it.status == "completed" }.forEach { payment ->
        lifetimeIncome += payment.amount
        if (payment.paymentDateMs >= startOfMonth) monthIncome += payment.amount
        if (payment.paymentDateMs >= startOfDay) todayIncome += payment.amount
    }
    expenses.forEach { expense ->
        lifetimeExpense += expense.amount
        if (expense.expenseDateMs >= startOfMonth) monthExpense += expense.amount
        if (expense.expenseDateMs >= startOfDay) todayExpense += expense.amount
    }
    return FinancialSummary(
        todayIncome, todayExpense, monthIncome, monthExpense, lifetimeIncome, lifetimeExpense
    )
}

/**
 * Birthdays are calendar dates, never times.  Normalising both values to the
 * start of the local day prevents a birthday from disappearing after midnight.
 */
private fun daysUntilNextBirthday(dobMs: Long, today: java.util.Calendar): Int {
    val dob = java.util.Calendar.getInstance().apply { timeInMillis = dobMs }
    val startOfToday = (today.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val next = java.util.Calendar.getInstance().apply {
        val month = dob.get(java.util.Calendar.MONTH)
        val day = dob.get(java.util.Calendar.DAY_OF_MONTH)
        val isFeb29 = month == java.util.Calendar.FEBRUARY && day == 29
        val year = startOfToday.get(java.util.Calendar.YEAR)
        clear()
        if (isFeb29 && !isLeapYear(year)) {
            set(year, java.util.Calendar.FEBRUARY, 28)
        } else {
            set(year, month, day)
        }
        if (before(startOfToday)) {
            val nextYear = year + 1
            clear()
            if (isFeb29 && !isLeapYear(nextYear)) {
                set(nextYear, java.util.Calendar.FEBRUARY, 28)
            } else {
                set(nextYear, month, day)
            }
        }
    }
    return ((next.timeInMillis - startOfToday.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
}

private fun isLeapYear(year: Int) =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

class DashboardViewModel(private val db: AppDatabase) : ViewModel() {
    private val _isBootstrapReady = MutableStateFlow(false)
    val isBootstrapReady = _isBootstrapReady.asStateFlow()

    private val _bootstrapError = MutableStateFlow<String?>(null)
    val bootstrapError = _bootstrapError.asStateFlow()

    private val _institute = MutableStateFlow<InstituteEntity?>(null)
    val institute = _institute.asStateFlow()
    
    private val _trialDaysLeft = MutableStateFlow(0)
    val trialDaysLeft = _trialDaysLeft.asStateFlow()

    private val _subscriptionRemainingDays = MutableStateFlow(0)
    val subscriptionRemainingDays = _subscriptionRemainingDays.asStateFlow()

    private val _studentCount = MutableStateFlow(0)
    val studentCount = _studentCount.asStateFlow()

    private val _batchCount = MutableStateFlow(0)
    val batchCount = _batchCount.asStateFlow()
    
    private val _staffCount = MutableStateFlow(0)
    val staffCount = _staffCount.asStateFlow()
    
    private val _financialSummary = MutableStateFlow(FinancialSummary())
    val financialSummary = _financialSummary.asStateFlow()

    private val _pendingFeesCount = MutableStateFlow(0)
    val pendingFeesCount = _pendingFeesCount.asStateFlow()

    private val _dueFeeSummary = MutableStateFlow(DueFeeSummary())
    val dueFeeSummary = _dueFeeSummary.asStateFlow()

    private val _examCount = MutableStateFlow(0)
    val examCount = _examCount.asStateFlow()

    private val _birthdaySummary = MutableStateFlow(BirthdayReminderSummary())
    val birthdaySummary = _birthdaySummary.asStateFlow()
    private val birthdayDayTick = MutableStateFlow(System.currentTimeMillis())

    private val _enquirySummary = MutableStateFlow(EnquirySummary())
    val enquirySummary = _enquirySummary.asStateFlow()

    private val _homeWorkCount = MutableStateFlow(0)
    val homeWorkCount = _homeWorkCount.asStateFlow()
    private val _assignmentCount = MutableStateFlow(0)
    val assignmentCount = _assignmentCount.asStateFlow()

    // Logged-in admin/owner user (for profile popup). Read-only; no schema change.
    private val _currentUser = MutableStateFlow<com.batchfee.edu.data.models.UserEntity?>(null)
    val currentUser = _currentUser.asStateFlow()

    // Active subscription plan for the institute (for profile popup).
    private val _currentPlan = MutableStateFlow<com.batchfee.edu.data.models.SubscriptionPlanEntity?>(null)
    val currentPlan = _currentPlan.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                birthdayDayTick.value = now
                delay(dashboardMillisecondsUntilNextDay(now))
            }
        }
        loadData()
    }

    fun retryBootstrap() {
        if (_isBootstrapReady.value) return
        _bootstrapError.value = null
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val sessionUserId = SessionManager.currentUserId.value ?: return@launch
            val instId = SessionManager.currentInstituteId.value ?: return@launch

            // Render Room's cached data first. A slow or offline Firestore refresh must never
            // hold the dashboard hostage on the loading spinner.
            var bootstrap = readBootstrapSnapshot(instId)
            if (bootstrap == null) {
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(8_000L) {
                        com.batchfee.edu.data.firestore.InstituteSyncHelper
                            .syncInstituteFromFirestore(db, instId)
                    }
                }
                bootstrap = readBootstrapSnapshot(instId)
            }
            if (bootstrap == null) {
                _bootstrapError.value = "We could not prepare your dashboard. Check your connection and try again."
                return@launch
            }

            // A completed request for a previous account must never unlock this dashboard.
            if (
                SessionManager.currentUserId.value != sessionUserId ||
                SessionManager.currentInstituteId.value != instId
            ) return@launch

            _institute.value = bootstrap.institute
            _studentCount.value = bootstrap.studentCount
            _batchCount.value = bootstrap.batchCount
            _staffCount.value = bootstrap.staffCount
            _isBootstrapReady.value = true

            // Keep dashboard sync narrow. Downloading every operational collection here made
            // every login compete with 14 independent Firestore syncs.
            launch(Dispatchers.IO) {
                try {
                    InstituteCacheRefreshManager.prefetchHighUseData(db, instId)
                    InstituteCacheRefreshManager.refreshScopeIfStale(
                        db, instId, InstituteRefreshScope.FINANCE
                    )
                } catch (_: Exception) {
                    // Cached Room data remains available; individual sync helpers log failures.
                }
            }

            launch {
                db.instituteDao().getInstituteFlow(instId).collect { inst ->
                    _institute.value = inst
                    if (inst != null && inst.subscriptionStatus == "trial") {
                        val remainingMs = inst.trialEndDateMs - System.currentTimeMillis()
                        val days = (remainingMs / (1000 * 60 * 60 * 24)).coerceAtLeast(0).toInt()
                        _trialDaysLeft.value = days
                    }
                    if (inst != null) {
                        val remainingMs = inst.currentPeriodEndMs - System.currentTimeMillis()
                        val remainingDays = (remainingMs / (1000 * 60 * 60 * 24)).coerceAtLeast(0).toInt()
                        _subscriptionRemainingDays.value = remainingDays
                    }
                    if (inst != null) {
                        _currentPlan.value = db.subscriptionPlanDao().getPlanById(inst.currentPlanId)
                    }
                }
            }
            SessionManager.currentUserId.value?.let { uid ->
                launch {
                    db.userDao().getUserFlow(uid).collect { _currentUser.value = it }
                }
            }
            launch {
                db.studentDao().countStudents(instId).collect { _studentCount.value = it }
            }
            launch {
                db.batchDao().getBatchesByInstitute(instId).collect { _batchCount.value = it.size }
            }
            launch {
                db.staffDao().countStaff(instId).collect { _staffCount.value = it }
            }
            launch {
                db.examDao().getExamsByInstitute(instId).collect { exams ->
                    _examCount.value = exams.size
                }
            }
            launch {
                db.studentDao().getStudentsByInstitute(instId)
                    .combine(birthdayDayTick) { students, now ->
                        students to java.util.Calendar.getInstance().apply { timeInMillis = now }
                    }
                    .collect { (students, today) ->
                    val upcoming = students.mapNotNull { student ->
                        if (isClosedStudentStatus(student.status)) return@mapNotNull null
                        student.dateOfBirthMs?.let { dob ->
                            val daysUntil = daysUntilNextBirthday(dob, today)
                            if (daysUntil in 0..30) {
                                UpcomingBirthday(
                                    studentName = student.fullName,
                                    className = student.className,
                                    daysUntil = daysUntil,
                                    photoUri = student.photoUri
                                )
                            } else null
                        }
                    }.sortedBy { it.daysUntil }
                    _birthdaySummary.value = BirthdayReminderSummary(
                        today = upcoming.filter { it.daysUntil == 0 },
                        tomorrowCount = upcoming.count { it.daysUntil == 1 },
                        upcomingCount = upcoming.size
                    )
                }
            }
            launch {
                db.enquiryDao().getEnquiriesByInstitute(instId).collect { enquiries ->
                    val active = enquiries.count { it.status.equals("active", ignoreCase = true) }
                    val close = enquiries.count { it.status.equals("close", ignoreCase = true) || it.status.equals("closed", ignoreCase = true) }
                    val followUps = enquiries.filter {
                        it.status.equals("follow_up", ignoreCase = true) || it.status.equals("follow up", ignoreCase = true)
                    }
                    val startOfToday = dashboardStartOfDay(System.currentTimeMillis())
                    val startOfTomorrow = Calendar.getInstance().apply {
                        timeInMillis = startOfToday
                        add(Calendar.DAY_OF_YEAR, 1)
                    }.timeInMillis
                    _enquirySummary.value = EnquirySummary(
                        total = enquiries.size,
                        active = active,
                        close = close,
                        followUp = followUps.size,
                        todayFollowUp = followUps.count { enquiry ->
                            enquiry.followUpDateMs?.let { it in startOfToday until startOfTomorrow } == true
                        },
                        overdueFollowUp = followUps.count { enquiry ->
                            enquiry.followUpDateMs?.let { it < startOfToday } == true
                        }
                    )
                }
            }
            launch {
                db.workDao().getActiveWorks(instId).collect { works ->
                    _homeWorkCount.value = works.count { it.type == "HOMEWORK" }
                    _assignmentCount.value = works.count { it.type == "ASSIGNMENT" }
                }
            }
            launch {
                kotlinx.coroutines.flow.combine(
                    db.paymentDao().getRecentPayments(instId),
                    db.expenseDao().getExpensesByInstitute(instId)
                ) { payments, expenses -> payments to expenses }
                    .collectLatest { (payments, expenses) ->
                        _financialSummary.value = withContext(Dispatchers.Default) {
                            calculateFinancialSummary(payments, expenses)
                        }
                    }
            }
            launch {
                kotlinx.coroutines.flow.combine(
                    db.feeDao().getAllFees(instId),
                    db.studentDao().getStudentsByInstitute(instId),
                    db.batchDao().getBatchesByInstitute(instId),
                    db.batchStudentDao().getBillingEnrollmentsForInstitute(instId)
                ) { fees, students, batches, billingEnrollments ->
                    DashboardDueInput(fees, students, batches, billingEnrollments)
                }.collectLatest { input ->
                    val result = withContext(Dispatchers.Default) {
                        calculateDashboardDue(input)
                    }
                    _pendingFeesCount.value = result.pendingFeesCount
                    _dueFeeSummary.value = result.summary
                }
            }
        }
    }

    private suspend fun readBootstrapSnapshot(instituteId: String): DashboardBootstrapSnapshot? =
        withContext(Dispatchers.IO) {
            val institute = db.instituteDao().getInstitute(instituteId) ?: return@withContext null
            DashboardBootstrapSnapshot(
                institute = institute,
                studentCount = db.studentDao().getStudentsByInstituteOnce(instituteId).size,
                batchCount = db.batchDao().getBatchesByInstituteOnce(instituteId).size,
                staffCount = db.staffDao().getStaffByInstituteAsList(instituteId).size
            )
        }

    fun addEnquiry(
        name: String,
        phone: String,
        address: String,
        subjectName: String,
        enquiryDateMs: Long,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val instId = SessionManager.currentInstituteId.value
        if (instId == null) {
            onError("No active institute found.")
            return
        }
        val cleanName = name.trim()
        val cleanPhone = phone.trim()
        val cleanSubject = subjectName.trim()
        if (cleanName.isBlank()) {
            onError("Name is required.")
            return
        }
        if (cleanPhone.isBlank()) {
            onError("Phone number is required.")
            return
        }
        if (cleanSubject.isBlank()) {
            onError("Subject name is required.")
            return
        }

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val enquiry = com.batchfee.edu.data.models.EnquiryEntity(
                    id = UUID.randomUUID().toString(),
                    instituteId = instId,
                    name = cleanName,
                    phone = cleanPhone,
                    address = address.trim().ifBlank { null },
                    subjectName = cleanSubject,
                    enquiryDateMs = enquiryDateMs,
                    status = "active",
                    createdAtMs = now,
                    updatedAtMs = now,
                    archivedAtMs = null
                )
                withContext(Dispatchers.IO) {
                    EnquirySyncHelper.upsertEnquiry(enquiry)
                    db.enquiryDao().insertEnquiry(enquiry)
                }
                onSuccess()
            } catch (e: Exception) {
                e.printStackTrace()
                onError("Could not save enquiry. Try again.")
            }
        }
    }
}

class DashboardViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) return DashboardViewModel(db) as T
        throw IllegalArgumentException()
    }
}

class MoreViewModel(private val db: AppDatabase) : ViewModel() {}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardTabsScreen(
    db: AppDatabase,
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onNavigatePricing: () -> Unit,
    onNavigateBilling: () -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        containerColor = DashboardBg,
        bottomBar = {
            BatchFeeBottomNav(currentRoute = currentRoute, onNavigate = onNavigate)
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DashboardBg)
                .padding(paddingValues)
        ) {
            when (currentRoute) {
                "DashboardRoute" -> DashboardScreen(db, onNavigatePricing, onNavigateBilling, onLogout, onNavigate)
                "More" -> MoreScreen(onLogout, onNavigate)
            }
        }
    }
}


@Composable
private fun SubscriptionWarningBanner(
    subscriptionStatus: String?,
    trialDaysLeft: Int,
    subscriptionRemainingDays: Int,
    modifier: Modifier = Modifier
) {
    if (subscriptionStatus == null) return
    val remainingDays = if (subscriptionStatus == "trial") trialDaysLeft else subscriptionRemainingDays
    if (remainingDays > 7 || remainingDays <= 0) return
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    val isCritical = remainingDays <= 3
    val bgColor = if (isCritical) AccentRed.copy(alpha = 0.12f) else AccentAmber.copy(alpha = 0.14f)
    val borderColor = if (isCritical) AccentRed.copy(alpha = 0.45f) else WarningAmber.copy(alpha = 0.50f)
    val iconColor = if (isCritical) AccentRed else AccentOrange
    val textColor = if (isCritical) Color(0xFFFCA5A5) else Color(0xFFFDE68A)

    val message = when (subscriptionStatus) {
        "trial" -> "আপনার ট্রায়ালের মেয়াদ আর মাত্র $remainingDays দিন পর শেষ হবে। সাবস্ক্রাইব করে সব ফিচার চালু রাখুন।"
        else -> "আপনার সাবস্ক্রিপশনের মেয়াদ আর মাত্র $remainingDays দিন পর শেষ হবে। নবায়ন করুন।"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isCritical) Icons.Filled.Warning else Icons.Filled.Notifications,
                contentDescription = "Subscription warning",
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { dismissed = true },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = iconColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun DashboardBootstrapLoading(
    errorMessage: String?,
    onRetry: () -> Unit,
    onLogout: () -> Unit
) {
    val loaderTransition = rememberInfiniteTransition(label = "dashboardBootstrapLoader")
    val ringRotation by loaderTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1450, easing = LinearEasing)
        ),
        label = "dashboardBootstrapRotation"
    )
    val ringSweep by loaderTransition.animateFloat(
        initialValue = 78f,
        targetValue = 286f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dashboardBootstrapSweep"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DashboardBg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (errorMessage == null) {
                Canvas(modifier = Modifier.size(132.dp)) {
                    val ringStroke = 8.dp.toPx()
                    val glowStroke = 20.dp.toPx()
                    val diameter = size.minDimension - glowStroke
                    val topLeft = Offset(
                        x = (size.width - diameter) / 2f,
                        y = (size.height - diameter) / 2f
                    )
                    val ringSize = Size(diameter, diameter)
                    val ringColors = listOf(AccentCyan, AccentSky, AccentBlue)

                    drawArc(
                        color = DashboardStroke.copy(alpha = 0.9f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = ringSize,
                        style = Stroke(width = ringStroke)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(ringColors.map { it.copy(alpha = 0.20f) }),
                        startAngle = ringRotation,
                        sweepAngle = ringSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = ringSize,
                        style = Stroke(width = glowStroke, cap = StrokeCap.Round)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(ringColors),
                        startAngle = ringRotation,
                        sweepAngle = ringSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = ringSize,
                        style = Stroke(width = ringStroke, cap = StrokeCap.Round)
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Preparing your institute\u2026",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = "Loading your latest data securely",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            } else {
                Text(errorMessage, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) { Text("Try again") }
                    OutlinedButton(onClick = onLogout) { Text("Logout", color = TextSecondary) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    db: AppDatabase,
    onNavigatePricing: () -> Unit,
    onNavigateBilling: () -> Unit,
    onLogout: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val viewModel: DashboardViewModel = viewModel(factory = DashboardViewModelFactory(db))
    val institute by viewModel.institute.collectAsState()
    val trialDays by viewModel.trialDaysLeft.collectAsState()
    val studentCount by viewModel.studentCount.collectAsState()
    val batchCount by viewModel.batchCount.collectAsState()
    val staffCount by viewModel.staffCount.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val currentPlan by viewModel.currentPlan.collectAsState()
    val subscriptionRemainingDays by viewModel.subscriptionRemainingDays.collectAsState()
    val isBootstrapReady by viewModel.isBootstrapReady.collectAsState()
    val bootstrapError by viewModel.bootstrapError.collectAsState()

    if (!isBootstrapReady) {
        DashboardBootstrapLoading(
            errorMessage = bootstrapError,
            onRetry = viewModel::retryBootstrap,
            onLogout = onLogout
        )
        return
    }

    var showFabMenu by remember { mutableStateOf(false) }
    var showProfilePopup by remember { mutableStateOf(false) }
    val snappbarcoroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val financialSummary by viewModel.financialSummary.collectAsState()
    val pendingFeesCount by viewModel.pendingFeesCount.collectAsState()
    val dueFeeSummary by viewModel.dueFeeSummary.collectAsState()
    val examCount by viewModel.examCount.collectAsState()
    val birthdaySummary by viewModel.birthdaySummary.collectAsState()
    val enquirySummary by viewModel.enquirySummary.collectAsState()
    val homeWorkCount by viewModel.homeWorkCount.collectAsState()
    val assignmentCount by viewModel.assignmentCount.collectAsState()
    var showEnquiryForm by remember { mutableStateOf(false) }
    val currentRole by SessionManager.currentUserRole.collectAsState()
    val currentStaffPermissions by SessionManager.currentStaffPermissions.collectAsState()
    val hasAddActions = remember(currentRole, currentStaffPermissions) {
        listOf("AddStudentRoute", "AddStaffRoute", "AddBatchRoute", "CreateExamRoute", "AddExpenseRoute", "UnifiedCollectRoute")
            .any { AccessControl.canAccessRoute(it) }
    }

    // ── Edit / Image / Switch state for profile popup ────────
    var showEditDialog by remember { mutableStateOf(false) }
    var showPhotoPicker by remember { mutableStateOf(false) }
    var isSavingProfile by remember { mutableStateOf(false) }
    var editOwnerName by remember { mutableStateOf("") }
    var editInstituteName by remember { mutableStateOf("") }
    var editPhone by remember { mutableStateOf("") }
    var editAddress by remember { mutableStateOf("") }
    var editProfilePhotoUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val savedProfilePhotoUri = remember(institute?.profilePhotoUri) {
        institute?.profilePhotoUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }
    val openEditDialog: () -> Unit = {
        editOwnerName = currentUser?.name.orEmpty()
        editInstituteName = institute?.name.orEmpty()
        editPhone = institute?.phone.orEmpty()
        editAddress = institute?.address.orEmpty()
        editProfilePhotoUri = savedProfilePhotoUri
        showEditDialog = true
    }

    // Camera/gallery launcher for profile photo
    val tempPhotoFile = remember { File(context.cacheDir, "profile_photo_${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() } }
    val tempPhotoUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempPhotoFile)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success -> if (success) editProfilePhotoUri = Uri.fromFile(tempPhotoFile) }

    // Gallery picker launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            snappbarcoroutineScope.launch {
                try {
                    editProfilePhotoUri = withContext(Dispatchers.IO) {
                        FirebaseStorageImageUploadHelper.cacheSelectedImage(context, uri, "institute_logo")
                    }
                } catch (error: Exception) {
                    snackbarHostState.showSnackbar(
                        error.message ?: "Could not read this image. Please choose it again."
                    )
                }
            }
        }
    }

    // ── Attendance state (shared with dialog) ──────────────
    val attVM: AttendanceViewModel = viewModel(factory = AttendanceViewModelFactory(db))
    val attSummaries by attVM.dailyBatchSummaries.collectAsState()
    val staffSum by attVM.dailyStaffAttendanceSummary.collectAsState()
    var attLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { attVM.loadDailySummaries(); attLoading = false }
    val todayLabel = remember {
        SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(java.util.Calendar.getInstance().time)
    }
    val studentOverall = remember(attSummaries) {
        if (attSummaries.isEmpty()) null else BatchAttendanceSummary(
            batchName = "All Batches",
            totalStudents = attSummaries.sumOf { it.totalStudents },
            presentCount = attSummaries.sumOf { it.presentCount },
            absentCount = attSummaries.sumOf { it.absentCount },
            leaveCount = attSummaries.sumOf { it.leaveCount },
            holidayCount = attSummaries.sumOf { it.holidayCount },
            expectedStudentDays = attSummaries.sumOf { it.expectedStudentDays },
            attendanceDays = 1
        )
    }
    var selectedBatchId by remember { mutableStateOf<String?>(null) }

    val safeNavigate: (String) -> Unit = { route ->
        if (!AccessControl.isKnownRoute(route)) {
            snappbarcoroutineScope.launch { snackbarHostState.showSnackbar("Coming soon") }
        } else if (AccessControl.canAccessRoute(route)) {
            onNavigate(route)
        } else {
            snappbarcoroutineScope.launch { snackbarHostState.showSnackbar("You do not have permission for this feature.") }
        }
    }
    val showComingSoon: (String) -> Unit = { label ->
        snappbarcoroutineScope.launch {
            snackbarHostState.showSnackbar("$label will be added next.")
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactLayout = maxWidth < 380.dp
        val tabletLayout = maxWidth >= 840.dp
        val contentModifier = if (tabletLayout) {
            Modifier.fillMaxWidth().widthIn(max = 760.dp)
        } else {
            Modifier.fillMaxWidth()
        }
        Scaffold(
            containerColor = DashboardBg,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets(0.dp),
        floatingActionButton = {
            if (hasAddActions) {
                CuteAddFab(
                    expanded = showFabMenu,
                    onClick = { showFabMenu = !showFabMenu }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = contentModifier.verticalScroll(rememberScrollState())
            ) {
                DashboardHeader(
                    institute = institute,
                    ownerName = currentUser?.name,
                    savedProfilePhotoUri = savedProfilePhotoUri,
                    todayLabel = todayLabel,
                    planLabel = if (institute?.subscriptionStatus == "trial") {
                        "Trial: $trialDays days"
                    } else {
                        currentPlan?.name ?: "Active plan"
                    },
                    compactLayout = compactLayout,
                    onProfileClick = { showProfilePopup = true },
                    onSettingsClick = { safeNavigate("SettingsRoute") }
                )

                SubscriptionWarningBanner(
                    subscriptionStatus = institute?.subscriptionStatus,
                    trialDaysLeft = trialDays,
                    subscriptionRemainingDays = subscriptionRemainingDays
                )

                Column(modifier = Modifier.padding(horizontal = if (compactLayout) 12.dp else 16.dp, vertical = 14.dp)) {
                GlobalNotificationCard()
                // Overview Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DashboardCard),
                    border = borderStroke()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Institute Snapshot", color = TextPrimary, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("Tap to manage", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // Students row — navigates to Students list
                        OverviewRow(
                            icon = Icons.Filled.School,
                            label = "Students",
                            active = studentCount,
                            inactive = 0,
                            onClick = { safeNavigate("StudentsRoute") }
                        )
                        HorizontalDivider(color = DashboardStroke, modifier = Modifier.padding(vertical = 8.dp))
                        // Batches row — navigates to Batch list
                        OverviewRow(
                            icon = Icons.Filled.Class,
                            label = "Batches",
                            active = batchCount,
                            inactive = 0,
                            onClick = { safeNavigate("BatchesRoute") }
                        )
                        HorizontalDivider(color = DashboardStroke, modifier = Modifier.padding(vertical = 8.dp))
                        // Staff row — navigates to Staff list
                        OverviewRow(
                            icon = Icons.Filled.Group,
                            label = "Staff",
                            active = staffCount,
                            inactive = 0,
                            onClick = { safeNavigate("StaffRoute") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Owner-only quick actions. Staff attendance remains in its own screen.
                if (SessionManager.isAdmin()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DashboardQuickActionCard(
                            title = "Staff Activity",
                            subtitle = "Login & work logs",
                            icon = Icons.Filled.History,
                            accent = AccentBlue,
                            modifier = Modifier.weight(1f),
                            onClick = { safeNavigate("StaffActivityRoute") }
                        )
                        DashboardQuickActionCard(
                            title = "Create Routine",
                            subtitle = "Batch schedule PDF",
                            icon = Icons.Filled.CalendarMonth,
                            accent = AccentCyan,
                            modifier = Modifier.weight(1f),
                            onClick = { safeNavigate("RoutineRoute") }
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // ── Live Attendance Summary ────────────────────

                // ── Main attendance card ───────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth().shadow(3.dp, RoundedCornerShape(16.dp), spotColor = AccentCyan.copy(0.10f)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DashboardCard),
                    border = borderStroke()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Attendance", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("Today", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (attLoading) {
                            // Shimmer placeholder
                            Column(Modifier.fillMaxWidth().height(90.dp), verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AccentCyan, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("Loading...", color = TextSecondary, fontSize = 12.sp)
                            }
                        } else if (studentOverall != null && studentOverall.markedCount > 0) {
                            // ── Student segmented bar ──────────────
                            PolishedAttendanceOverview(studentOverall, staffSum)
                            // ── Staff segmented bar ────────────────
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Filled.CalendarMonth, null, tint = TextSecondary.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("No attendance recorded yet", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ── Mini Cards (Student + Staff marking) ────────
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val sMarked = studentOverall?.markedCount ?: 0
                    val sTotal = studentOverall?.totalStudents ?: 0
                    AttendanceMiniCard("Student", Icons.Filled.School, sMarked, sTotal, "marked today", AccentGreen, { safeNavigate("AttendanceRoute") }, Modifier.weight(1f))
                    val stMarked = staffSum.markedCount
                    val stTotal = staffSum.totalStaff
                    AttendanceMiniCard("Staff", Icons.Filled.Group, stMarked, stTotal, "marked today", AccentSky, { safeNavigate("StaffAttendanceRoute") }, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Financial Collection Cards ────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Today Collection
                    Card(
                        modifier = Modifier.weight(1f).premiumClickable {
                            safeNavigate("ReportsRoute?period=today")
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DashboardCard),
                        border = borderStroke()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                            Text("Today", color = TextSecondary, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            AnimatedCounter(target = financialSummary.todayIncome, prefix = "BDT ")
                            Text("Collected", color = AccentCyan, fontSize = 10.sp)
                        }
                    }
                    // Monthly Collection
                    Card(
                        modifier = Modifier.weight(1f).premiumClickable {
                            safeNavigate("ReportsRoute?period=month")
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DashboardCard),
                        border = borderStroke()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                            Text("Monthly", color = TextSecondary, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            AnimatedCounter(target = financialSummary.monthIncome, prefix = "BDT ")
                            Text("Collected", color = AccentSky, fontSize = 10.sp)
                        }
                    }
                    // Lifetime Collection
                    Card(
                        modifier = Modifier.weight(1f).premiumClickable {
                            safeNavigate("ReportsRoute?period=lifetime")
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DashboardCard),
                        border = borderStroke()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                            Text("Lifetime", color = TextSecondary, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            AnimatedCounter(target = financialSummary.lifetimeIncome, prefix = "BDT ")
                            Text("Collected", color = AccentViolet, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                InstituteExpenseSummaryCard(
                    monthExpense = financialSummary.monthExpense,
                    todayExpense = financialSummary.todayExpense,
                    onClick = { safeNavigate("ExpensesRoute") }
                )

                Spacer(modifier = Modifier.height(16.dp))

                CompactDueFeesCard(
                    summary = dueFeeSummary,
                    onClick = { safeNavigate("DueFeesRoute") }
                )
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader(title = "Tools & reminders")
                Spacer(modifier = Modifier.height(10.dp))
                HomeEngagementSection(
                    examCount = examCount,
                    birthdaySummary = birthdaySummary,
                    homeWorkCount = homeWorkCount,
                    assignmentCount = assignmentCount,
                    enquirySummary = enquirySummary,
                    compactLayout = compactLayout,
                    onOpenExams = { safeNavigate("ExamsRoute") },
                    onOpenBirthdays = { safeNavigate("BirthdayReminderRoute") },
                    onOpenHomeWorks = { safeNavigate("HomeworkListRoute") },
                    onOpenAssignments = { safeNavigate("AssignmentListRoute") },
                    onOpenEnquiry = { safeNavigate("EnquiryListRoute") },
                    onComingSoon = showComingSoon
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // ── Batch Detail Dialog ────────────────────────────
        if (selectedBatchId != null) {
            val bid = selectedBatchId!!
            val batchSum = attSummaries.find { it.batchId == bid }
            AlertDialog(
                onDismissRequest = { selectedBatchId = null },
                containerColor = DashboardCard,
                shape = RoundedCornerShape(20.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Class, null, tint = AccentCyan, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(batchSum?.batchName ?: "Batch", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    batchSum?.let { sum ->
                        Column {
                            AttendanceSegmentedBar(sum, "Students")
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                dialogStat("Present", "${"%.0f".format(sum.presentPct)}%", AccentGreen, sum.presentCount)
                                dialogStat("Absent", "${"%.0f".format(sum.absentPct)}%", AccentRed, sum.absentCount)
                                dialogStat("Leave", "${"%.0f".format(sum.leavePct)}%", AccentSky, sum.leaveCount)
                                dialogStat("Holiday", "${"%.0f".format(sum.holidayPct)}%", AccentGray, sum.holidayCount)
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    selectedBatchId = null
                                    onNavigate("TakeAttendanceRoute:$bid")
                                },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                            ) { Icon(Icons.Filled.HowToReg, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Take Attendance") }
                        }
                    } ?: Text("No data available.", color = TextSecondary)
                },
                confirmButton = { TextButton(onClick = { selectedBatchId = null }) { Text("Close", color = AccentCyan) } }
            )
        }

        if (showEnquiryForm) {
            AddEnquiryDialog(
                onDismiss = { showEnquiryForm = false },
                onSave = { name, phone, address, subjectName, enquiryDateMs ->
                    viewModel.addEnquiry(
                        name = name,
                        phone = phone,
                        address = address,
                        subjectName = subjectName,
                        enquiryDateMs = enquiryDateMs,
                        onSuccess = {
                            showEnquiryForm = false
                            snappbarcoroutineScope.launch {
                                snackbarHostState.showSnackbar("Enquiry saved.")
                            }
                        },
                        onError = { message ->
                            snappbarcoroutineScope.launch {
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                }
            )
        }
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = showFabMenu && hasAddActions,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.94f),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.94f),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showFabMenu = false },
            contentAlignment = Alignment.Center
        ) {
            AddNewMenuPanel(
                onClose = { showFabMenu = false },
                onNavigate = { route ->
                    showFabMenu = false
                    safeNavigate(route)
                }
            )
        }
    }
    
    androidx.compose.animation.AnimatedVisibility(
        visible = showProfilePopup,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.9f),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.9f),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showProfilePopup = false },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF0F1629))
                    .border(1.dp, Color(0xFF1E2A45), RoundedCornerShape(24.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                ) {
                    // Header Banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(Color(0xFF1A265E), Color(0xFF0F1629))
                                )
                            )
                    ) {
                        IconButton(
                            onClick = { showProfilePopup = false },
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                        }

                        Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                            // Edit button: opens edit profile dialog for institute name + photo
                            androidx.compose.material3.OutlinedButton(
                                onClick = openEditDialog,
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Edit", color = AccentCyan, fontSize = 11.sp)
                            }
                            if (AccessControl.canAccessRoute("PricingRoute")) {
                                Spacer(Modifier.width(6.dp))
                                // Switch button: navigates to pricing screen to switch plans
                                androidx.compose.material3.OutlinedButton(
                                    onClick = {
                                        showProfilePopup = false
                                        onNavigatePricing()
                                    },
                                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Filled.Group, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Switch", color = AccentCyan, fontSize = 11.sp)
                                }
                            }
                        }
                        
                        // Avatar — clickable to change profile photo via camera or gallery
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 24.dp)
                                .offset(y = 36.dp)
                                .size(72.dp)
                                .shadow(12.dp, CircleShape, spotColor = AccentBlue, ambientColor = AccentBlue)
                                .clip(CircleShape)
                                .background(Color(0xFF0F1629))
                                .border(2.dp, AccentBlue, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            val photo = savedProfilePhotoUri
                            if (photo != null) {
                                coil.compose.AsyncImage(
                                    model = photo,
                                    contentDescription = "Profile photo",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Text(
                                    (institute?.name ?: "B").take(1).uppercase(),
                                    color = AccentCyan,
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(40.dp))
                    
                    // Profile Info (compact)
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            institute?.name.orEmpty(),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            currentUser?.name ?: "Institute Owner",
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            currentUser?.email ?: "owner@batchfee.app",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                        
                        Spacer(Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Code", color = TextSecondary, fontSize = 12.sp)
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF1A265E), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(institute?.id ?: "DEMO", color = AccentBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Phone, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                institute?.phone?.takeIf { it.isNotBlank() } ?: "Not added",
                                color = if (institute?.phone.isNullOrBlank()) TextSecondary else Color.White,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                institute?.address?.takeIf { it.isNotBlank() } ?: "Not added",
                                color = if (institute?.address.isNullOrBlank()) TextSecondary else Color.White,
                                fontSize = 12.sp
                            )
                        }
                        
                        Spacer(Modifier.height(14.dp))
                        Text("Current Subscription", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        
                        // Subscription Card (compact)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(Color(0xFF161D35), Color(0xFF0D1322))
                                    )
                                )
                                .border(1.dp, Color(0xFF1E2A45), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                val isTrial = institute?.subscriptionStatus == "trial"
                                val isActive = institute?.subscriptionStatus == "active"
                                val statusLabel = when {
                                    isTrial -> "Trial"
                                    isActive -> "Active"
                                    else -> institute?.subscriptionStatus ?: "Unknown"
                                }
                                val remainingDays = if (isTrial) trialDays else subscriptionRemainingDays
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(26.dp).background(Color(0xFF4C5DDB), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(currentPlan?.name ?: if (isTrial) "Free Trial" else "Active Plan", color = AccentBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        Text("$statusLabel · $remainingDays days remaining", color = TextSecondary, fontSize = 10.sp)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Group, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (isTrial) "$studentCount Students \u2022 Unlimited during trial"
                                        else "$studentCount of ${currentPlan?.maxStudents ?: 100} Students",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider(color = Color(0xFF1E2A45))
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CalendarToday, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text(if (isTrial) "Trial started" else "Next Renewal", color = TextSecondary, fontSize = 10.sp)
                                        val dateLabel = if (isTrial) {
                                            val endDate = java.util.Date(institute?.trialEndDateMs ?: 0L)
                                            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(endDate)
                                        } else {
                                            val endDate = java.util.Date(institute?.currentPeriodEndMs ?: 0L)
                                            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(endDate)
                                        }
                                        Text(dateLabel, color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(6.dp))
                        
                        // View Subscription Plan - Primary Premium Button with Animated Glow
                        val infiniteTransition = rememberInfiniteTransition(label = "glow")
                        val glowAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 0.8f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1500, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "glowAlpha"
                        )
                        val shimmerOffset by infiniteTransition.animateFloat(
                            initialValue = -200f,
                            targetValue = 800f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(2000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "shimmer"
                        )
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF1A265E))
                                .border(
                                    width = 1.5.dp,
                                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF2196F3).copy(alpha = glowAlpha),
                                            Color(0xFF00BCD4).copy(alpha = glowAlpha),
                                            Color(0xFF2196F3).copy(alpha = glowAlpha)
                                        )
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    if (AccessControl.canAccessRoute("PricingRoute")) {
                                        showProfilePopup = false
                                        try { onNavigatePricing() } catch (e: Exception) {
                                            snappbarcoroutineScope.launch { snackbarHostState.showSnackbar("Subscription plan screen coming soon") }
                                        }
                                    } else {
                                        snappbarcoroutineScope.launch { snackbarHostState.showSnackbar("Only admins can change subscription plans.") }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // Gradient background
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = listOf(
                                                Color(0xFF1565C0).copy(alpha = 0.6f),
                                                Color(0xFF00838F).copy(alpha = 0.6f)
                                            )
                                        )
                                    )
                            )
                            // Shimmer effect
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                Color.White.copy(alpha = 0.08f),
                                                Color.Transparent
                                            ),
                                            start = androidx.compose.ui.geometry.Offset(shimmerOffset - 100f, 0f),
                                            end = androidx.compose.ui.geometry.Offset(shimmerOffset + 100f, 0f)
                                        )
                                    )
                            )
                            // Content
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("View Subscription Plan", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        
                        // Contact with Developer - open WhatsApp with institute name only
                        OutlinedButton(
                            onClick = {
                                // Build WhatsApp deep-link: wa.me phone + URL-encoded message
                                val instituteName = institute?.name.orEmpty()
                                val message = "Hello Developer, Institute: $instituteName"
                                val encodedMessage = URLEncoder.encode(message, "UTF-8")
                                val url = "https://wa.me/8801518657869?text=$encodedMessage"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                // Uses ACTION_VIEW — WhatsApp will handle if installed, browser fallback otherwise
                                context.startActivity(intent)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
                        ) {
                            Icon(Icons.Filled.Phone, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Contact with Developer", color = AccentGreen, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                        
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // ── Edit Institute Dialog ────────────────────────────────
    if (showPhotoPicker) {
        AlertDialog(
            onDismissRequest = { showPhotoPicker = false },
            title = { Text("Profile Photo", color = Color.White) },
            text = { Text("Choose an image to update your institute profile.", color = TextSecondary) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showPhotoPicker = false
                            try {
                                cameraLauncher.launch(tempPhotoUri)
                            } catch (_: Exception) {
                            }
                        }
                    ) {
                        Text("Camera", color = AccentCyan)
                    }
                    TextButton(
                        onClick = {
                            showPhotoPicker = false
                            galleryLauncher.launch("image/*")
                        }
                    ) {
                        Text("Gallery", color = AccentCyan)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPhotoPicker = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = Color(0xFF0F1629)
        )
    }

    if (showEditDialog) {
        val originalOwnerName = currentUser?.name?.trim().orEmpty()
        val originalInstituteName = institute?.name?.trim().orEmpty()
        val originalPhone = institute?.phone?.trim().orEmpty()
        val originalAddress = institute?.address?.trim().orEmpty()
        val originalPhotoUri = institute?.profilePhotoUri.orEmpty()
        val hasProfileChanges =
            editOwnerName.trim() != originalOwnerName ||
            editInstituteName.trim() != originalInstituteName ||
            editPhone.trim() != originalPhone ||
            editAddress.trim() != originalAddress ||
            editProfilePhotoUri?.toString().orEmpty() != originalPhotoUri

        AlertDialog(
            onDismissRequest = {
                if (!isSavingProfile) {
                    showEditDialog = false
                }
            },
            title = { Text("Update Institute Info", color = Color.White) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF111827))
                                .border(1.dp, AccentBlue.copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (editProfilePhotoUri != null) {
                                AsyncImage(
                                    model = editProfilePhotoUri,
                                    contentDescription = "Institute photo preview",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                            } else {
                                Text(
                                    editInstituteName.ifBlank { institute?.name ?: "B" }.take(1).uppercase(),
                                    color = AccentCyan,
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Edit text or image, then save once to apply everything together.",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            OutlinedButton(
                                onClick = { showPhotoPicker = true },
                                border = borderStroke(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                            ) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (editProfilePhotoUri == null) "Upload Photo" else "Change Photo")
                            }
                        }
                    }

                    Text(
                        "Institute Details",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedTextField(
                        value = editOwnerName,
                        onValueChange = { editOwnerName = it },
                        label = { Text("Owner Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DashboardStroke,
                            focusedContainerColor = Color(0xFF111827),
                            unfocusedContainerColor = Color(0xFF111827),
                            cursorColor = AccentCyan
                        )
                    )

                    OutlinedTextField(
                        value = editInstituteName,
                        onValueChange = { editInstituteName = it },
                        label = { Text("Institute Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DashboardStroke,
                            focusedContainerColor = Color(0xFF111827),
                            unfocusedContainerColor = Color(0xFF111827),
                            cursorColor = AccentCyan
                        )
                    )

                    OutlinedTextField(
                        value = editPhone,
                        onValueChange = { editPhone = it },
                        label = { Text("Phone Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DashboardStroke,
                            focusedContainerColor = Color(0xFF111827),
                            unfocusedContainerColor = Color(0xFF111827),
                            cursorColor = AccentCyan
                        )
                    )

                    OutlinedTextField(
                        value = editAddress,
                        onValueChange = { editAddress = it },
                        label = { Text("Address") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DashboardStroke,
                            focusedContainerColor = Color(0xFF111827),
                            unfocusedContainerColor = Color(0xFF111827),
                            cursorColor = AccentCyan
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isSavingProfile && hasProfileChanges && editInstituteName.isNotBlank() && editOwnerName.isNotBlank(),
                    onClick = {
                        val inst = institute
                        val owner = currentUser
                        if (inst == null || owner == null) {
                            snappbarcoroutineScope.launch {
                                snackbarHostState.showSnackbar("Unable to load institute information right now.")
                            }
                            return@TextButton
                        }

                        isSavingProfile = true
                        snappbarcoroutineScope.launch {
                            try {
                                val profilePhotoUri = when {
                                    editProfilePhotoUri == null -> {
                                        deleteLocalInstituteProfilePhoto(inst.profilePhotoUri)
                                        null
                                    }
                                    editProfilePhotoUri.toString() == inst.profilePhotoUri -> inst.profilePhotoUri
                                    else -> FirebaseStorageImageUploadHelper.uploadInstituteLogo(
                                        context,
                                        editProfilePhotoUri!!,
                                        replacesReference = inst.profilePhotoUri
                                    )
                                }

                                val updated = inst.copy(
                                    name = editInstituteName.trim(),
                                    phone = editPhone.trim().ifBlank { null },
                                    address = editAddress.trim().ifBlank { null },
                                    profilePhotoUri = profilePhotoUri
                                )

                                // Save locally FIRST — Firestore sync is best-effort after
                                db.instituteDao().updateInstitute(updated)
                                db.userDao().updateUser(owner.copy(name = editOwnerName.trim()))
                                if (profilePhotoUri != inst.profilePhotoUri) {
                                    deleteLocalInstituteProfilePhoto(inst.profilePhotoUri)
                                }

                                // Firestore sync in background — don't block UI
                                try {
                                    withContext(Dispatchers.IO) {
                                        com.batchfee.edu.data.firestore.InstituteSyncHelper.syncInstituteToFirestore(updated)
                                    }
                                } catch (_: Exception) {
                                    // Local is already saved — Firestore will sync on next refresh
                                }

                                showEditDialog = false
                                snackbarHostState.showSnackbar("Institute information updated.")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar(e.message ?: "Failed to update institute information.")
                            } finally {
                                isSavingProfile = false
                            }
                        }
                    }
                ) {
                    Text(if (isSavingProfile) "Saving..." else "Save", color = AccentCyan)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSavingProfile,
                    onClick = { showEditDialog = false }
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = Color(0xFF0F1629)
        )
    }
}
}
}

private fun deleteLocalInstituteProfilePhoto(photoUri: String?) {
    val uri = photoUri?.let(Uri::parse) ?: return
    if (uri.scheme == "file") {
        uri.path?.let(::File)?.delete()
    }
}

@Composable
private fun borderStroke() = androidx.compose.foundation.BorderStroke(1.dp, DashboardStroke)

/** A shared, restrained press treatment for dashboard actions. */
@Composable
private fun Modifier.premiumClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.982f else 1f,
        animationSpec = tween(durationMillis = if (pressed) 90 else 150, easing = FastOutSlowInEasing),
        label = "dashboardCardPress"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

/** A low-frequency highlight reserved for high-priority financial summaries. */
@Composable
private fun BoxScope.FinancialCardSheen(accent: Color) {
    val transition = rememberInfiniteTransition(label = "financialCardSheen")
    val offset by transition.animateFloat(
        initialValue = -260f,
        targetValue = 680f,
        animationSpec = infiniteRepeatable(
            animation = tween(5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "financialSheenOffset"
    )
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        accent.copy(alpha = 0.035f),
                        accent.copy(alpha = 0.09f),
                        accent.copy(alpha = 0.035f),
                        Color.Transparent
                    ),
                    start = Offset(offset - 80f, 0f),
                    end = Offset(offset + 80f, 76f)
                )
            )
    )
}

@Composable
private fun CuteAddFab(
    expanded: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "cuteFabShine")
    val shineOffset by infiniteTransition.animateFloat(
        initialValue = -90f,
        targetValue = 150f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shineOffset"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.38f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier
            .size(50.dp)
            .shadow(
                elevation = 14.dp,
                shape = RoundedCornerShape(18.dp),
                spotColor = AccentCyan.copy(alpha = glowAlpha)
            )
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        AccentSky,
                        AccentCyan,
                        Color(0xFF67E8F9)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(110f, 110f)
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.20f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.42f),
                            Color.Transparent
                        ),
                        start = Offset(shineOffset, -10f),
                        end = Offset(shineOffset + 42f, 88f)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, DashboardBg.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
        )
        Icon(
            if (expanded) Icons.Filled.Close else Icons.Filled.Add,
            contentDescription = if (expanded) "Close add menu" else "Open add menu",
            tint = DashboardBg,
            modifier = Modifier.size(if (expanded) 24.dp else 28.dp)
        )
    }
}

@Composable
private fun AddNewMenuPanel(
    onClose: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val currentRole by SessionManager.currentUserRole.collectAsState()
    val currentStaffPermissions by SessionManager.currentStaffPermissions.collectAsState()
    val addMenuItems = remember(currentRole, currentStaffPermissions) {
        listOf(
            AddMenuOption("Student", "Create a new student profile", Icons.Filled.School, "AddStudentRoute"),
            AddMenuOption("Staff", "Add a teacher or staff member", Icons.Filled.PersonAddAlt1, "AddStaffRoute"),
            AddMenuOption("Batch", "Create a batch and class schedule", Icons.Filled.Groups, "AddBatchRoute"),
            AddMenuOption("Exams", "Schedule an exam or result entry", Icons.Filled.Assignment, "CreateExamRoute"),
            AddMenuOption("Expense", "Record an institute expense", Icons.Filled.ReceiptLong, "AddExpenseRoute"),
            AddMenuOption("Collection Fee", "Collect student fee payment", Icons.Filled.Payments, "UnifiedCollectRoute")
        ).filter { AccessControl.canAccessRoute(it.route) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.90f)
            .shadow(18.dp, RoundedCornerShape(24.dp), spotColor = AccentCyan.copy(alpha = 0.18f))
            .clip(RoundedCornerShape(24.dp))
            .background(DashboardCardAlt)
            .border(1.dp, AccentCyan.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            DashboardCardAlt,
                            AccentCyan.copy(alpha = 0.10f),
                            DashboardCardAlt
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(420f, 80f)
                    )
                )
                .padding(start = 18.dp, top = 16.dp, end = 12.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AccentCyan.copy(alpha = 0.14f))
                    .border(1.dp, AccentCyan.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Add New",
                    color = TextPrimary,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Choose what you want to create",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(AccentRed.copy(alpha = 0.10f))
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close add menu",
                    tint = Color(0xFFFFA3A3),
                    modifier = Modifier.size(23.dp)
                )
            }
        }

        HorizontalDivider(color = DashboardStroke.copy(alpha = 0.85f))

        if (addMenuItems.isEmpty()) {
            Text(
                "No create actions available for this account.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(18.dp)
            )
        } else {
            addMenuItems.forEachIndexed { index, item ->
                AddMenuActionRow(
                    item = item,
                    onClick = { onNavigate(item.route) }
                )
                if (index != addMenuItems.lastIndex) {
                    HorizontalDivider(
                        color = DashboardStroke.copy(alpha = 0.70f),
                        modifier = Modifier.padding(start = 70.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AddMenuActionRow(
    item: AddMenuOption,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AccentCyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(item.icon, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                color = TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                item.subtitle,
                color = TextSecondary.copy(alpha = 0.76f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AccentCyan.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DashboardHeader(
    institute: InstituteEntity?,
    ownerName: String?,
    savedProfilePhotoUri: Uri?,
    todayLabel: String,
    planLabel: String,
    compactLayout: Boolean,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DashboardCard)
            .padding(
                horizontal = if (compactLayout) 12.dp else 16.dp,
                vertical = if (compactLayout) 12.dp else 14.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(if (compactLayout) 50.dp else 54.dp)
                    .clip(CircleShape)
                    .background(if (savedProfilePhotoUri == null) AccentCyan else DashboardCardAlt)
                    .border(1.dp, AccentCyan.copy(alpha = 0.70f), CircleShape)
                    .clickable(onClick = onProfileClick),
                contentAlignment = Alignment.Center
            ) {
                if (savedProfilePhotoUri != null) {
                    AsyncImage(
                        model = savedProfilePhotoUri,
                        contentDescription = "Institute profile photo",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Text(
                        (institute?.name ?: "B").take(1).uppercase(),
                        color = DashboardBg,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
            Spacer(modifier = Modifier.width(if (compactLayout) 12.dp else 14.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onProfileClick)
            ) {
                Text(
                    institute?.name.orEmpty(),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ownerName?.takeIf { it.isNotBlank() } ?: "Institute Owner",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
            }
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .size(if (compactLayout) 42.dp else 44.dp)
                    .clip(RoundedCornerShape(if (compactLayout) 12.dp else 14.dp))
                    .background(DashboardCardAlt)
                    .border(1.dp, DashboardStroke, RoundedCornerShape(if (compactLayout) 12.dp else 14.dp))
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextPrimary, modifier = Modifier.size(if (compactLayout) 20.dp else 22.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DashboardHeaderPill(
                icon = Icons.Filled.CalendarToday,
                text = todayLabel,
                accent = AccentSky,
                compactLayout = compactLayout,
                modifier = Modifier.weight(1f)
            )
            DashboardHeaderPill(
                icon = Icons.Filled.WorkspacePremium,
                text = planLabel,
                accent = AccentGreen,
                compactLayout = compactLayout,
                modifier = Modifier.weight(1f)
            )
        }

    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(AccentCyan, AccentGreen, AccentBlue)
                )
            )
    )
}

@Composable
private fun DashboardHeaderPill(
    icon: ImageVector,
    text: String,
    accent: Color,
    compactLayout: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(if (compactLayout) 32.dp else 34.dp)
            .clip(RoundedCornerShape(if (compactLayout) 11.dp else 12.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(if (compactLayout) 11.dp else 12.dp))
            .padding(horizontal = if (compactLayout) 9.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(if (compactLayout) 15.dp else 16.dp))
        Spacer(Modifier.width(if (compactLayout) 6.dp else 7.dp))
        Text(
            text,
            color = TextPrimary,
            fontSize = if (compactLayout) 10.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AccentCyan.copy(alpha = 0.55f))
        )
    }
}

@Composable
private fun InstituteExpenseSummaryCard(
    monthExpense: Double,
    todayExpense: Double,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().premiumClickable(onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCard),
        border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.38f))
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            FinancialCardSheen(AccentOrange)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AccentOrange.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ReceiptLong,
                    contentDescription = null,
                    tint = AccentOrange,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Institute Expenses",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("This month", color = TextSecondary, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatDashboardAmount(monthExpense),
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Today ${formatDashboardAmount(todayExpense)}",
                    color = AccentOrange,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Open expenses",
                tint = AccentOrange,
                modifier = Modifier.size(20.dp)
            )
        }
        }
    }
}

@Composable
private fun CompactDueFeesCard(summary: DueFeeSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().premiumClickable(onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCard),
        border = borderStroke()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            FinancialCardSheen(AccentCyan)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                    .background(AccentCyan.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Calculate, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Due Fees", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${summary.activeCount} active · ${summary.closeCount} close students",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatDashboardAmount(summary.activeAmount), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Active due", color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ChevronRight, "Open due fees", tint = AccentCyan, modifier = Modifier.size(20.dp))
        }
        }
    }
}

@Composable
private fun DueSummaryBlock(count: Int, amount: Double, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DashboardCardAlt)
            .border(1.dp, DashboardStroke, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            formatDashboardAmount(amount),
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text("$count students", color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HomeEngagementSection(
    examCount: Int,
    birthdaySummary: BirthdayReminderSummary,
    homeWorkCount: Int,
    assignmentCount: Int,
    enquirySummary: EnquirySummary,
    compactLayout: Boolean,
    onOpenExams: () -> Unit,
    onOpenBirthdays: () -> Unit,
    onOpenHomeWorks: () -> Unit,
    onOpenAssignments: () -> Unit,
    onOpenEnquiry: () -> Unit,
    onComingSoon: (String) -> Unit
) {
    if (compactLayout) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HomeFeatureTile(
                title = "Exams",
                count = examCount,
                icon = Icons.Filled.Assignment,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenExams
            )
            BirthdayHomeFeatureTile(
                summary = birthdaySummary,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenBirthdays
            )
            HomeFeatureTile(
                title = "Homework",
                count = homeWorkCount,
                icon = Icons.Filled.ListAlt,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenHomeWorks
            )
            HomeFeatureTile(
                title = "Assignments",
                count = assignmentCount,
                icon = Icons.Filled.ListAlt,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenAssignments
            )
            EnquirySummaryCard(
                summary = enquirySummary,
                onClick = onOpenEnquiry
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeFeatureTile(
                    title = "Exams",
                    count = examCount,
                    icon = Icons.Filled.Assignment,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenExams
                )
                BirthdayHomeFeatureTile(
                    summary = birthdaySummary,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenBirthdays
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeFeatureTile(
                    title = "Homework",
                    count = homeWorkCount,
                    icon = Icons.Filled.ListAlt,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenHomeWorks
                )
                HomeFeatureTile(
                    title = "Assignments",
                    count = assignmentCount,
                    icon = Icons.Filled.ListAlt,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenAssignments
                )
            }
            EnquirySummaryCard(
                summary = enquirySummary,
                onClick = onOpenEnquiry
            )
        }
    }
}

@Composable
private fun HomeFeatureTile(
    title: String,
    count: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(68.dp)
            .premiumClickable(onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCardAlt),
        border = borderStroke()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "($count)",
                color = AccentCyan,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun HomeFullActionTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .premiumClickable(onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCardAlt),
        border = borderStroke()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EnquirySummaryCard(
    summary: EnquirySummary,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCardAlt),
        border = borderStroke()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AccentCyan.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.PersonAddAlt1, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Enquiry",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Add and track new student interest",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TodayFollowUpBeacon(count = summary.todayFollowUp)
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentCyan.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                }
            }

            if (summary.todayFollowUp > 0) {
                Spacer(Modifier.height(12.dp))
                TodayFollowUpReminder(
                    todayCount = summary.todayFollowUp,
                    overdueCount = summary.overdueFollowUp
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(DashboardBg.copy(alpha = 0.55f))
                    .border(1.dp, DashboardStroke.copy(alpha = 0.72f), RoundedCornerShape(13.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                EnquiryStat(summary.total, "Total", Modifier.weight(1f))
                EnquiryStat(summary.active, "Active", Modifier.weight(1f))
                EnquiryStat(summary.close, "Close", Modifier.weight(1f))
                EnquiryStat(summary.followUp, "Follow up", Modifier.weight(1f), AccentAmber)
            }
        }
    }
}

/** Keeps the original compact dashboard tile; the badge only signals birthdays due today. */
@Composable
private fun BirthdayHomeFeatureTile(
    summary: BirthdayReminderSummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(68.dp)
            .premiumClickable(onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCardAlt),
        border = borderStroke()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Cake, contentDescription = null, tint = AccentPink, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Birthdays",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (summary.today.isNotEmpty()) "Today" else "Next 30 days",
                    color = if (summary.today.isNotEmpty()) AccentPink else TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            TodayBirthdayBeacon(summary.today.size)
        }
    }
}

@Composable
private fun BirthdayReminderCard(
    summary: BirthdayReminderSummary,
    onClick: () -> Unit
) {
    val todayCount = summary.today.size
    val hasBirthdayToday = todayCount > 0
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .premiumClickable(onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCardAlt),
        border = BorderStroke(
            1.dp,
            if (hasBirthdayToday) AccentPink.copy(alpha = 0.42f) else DashboardStroke
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AccentPink.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Cake,
                        contentDescription = null,
                        tint = AccentPink,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Today's birthdays",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (hasBirthdayToday) "Make their day special" else "No birthday to celebrate today",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TodayBirthdayBeacon(todayCount)
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Open birthday reminders",
                    tint = if (hasBirthdayToday) AccentPink else AccentCyan,
                    modifier = Modifier.size(21.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            if (hasBirthdayToday) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(AccentPink.copy(alpha = 0.09f))
                        .border(1.dp, AccentPink.copy(alpha = 0.26f), RoundedCornerShape(13.dp))
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BirthdayAvatarStack(summary.today.take(3))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            summary.today.take(2).joinToString(" · ") { it.studentName } +
                                if (todayCount > 2) " +${todayCount - 2} more" else "",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "$todayCount student${if (todayCount == 1) "" else "s"} celebrating today",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        "Wish",
                        color = AccentPink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(DashboardBg.copy(alpha = 0.55f))
                        .border(1.dp, DashboardStroke.copy(alpha = 0.72f), RoundedCornerShape(13.dp))
                        .padding(horizontal = 11.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.EventAvailable,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        when {
                            summary.tomorrowCount > 0 -> "${summary.tomorrowCount} birthday${if (summary.tomorrowCount == 1) "" else "s"} tomorrow"
                            summary.upcomingCount > 0 -> "Upcoming birthdays are ready to view"
                            else -> "Add students' dates of birth to see reminders"
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("View", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BirthdayAvatarStack(students: List<UpcomingBirthday>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        students.forEach { student ->
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(AccentPink, AccentOrange)))
                    .border(1.dp, DashboardCardAlt, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (!student.photoUri.isNullOrBlank()) {
                    AsyncImage(
                        model = student.photoUri,
                        contentDescription = student.studentName,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else {
                    Text(
                        student.studentName.trim().firstOrNull()?.uppercase() ?: "?",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun TodayBirthdayBeacon(count: Int) {
    val pulseTransition = rememberInfiniteTransition(label = "todayBirthdayPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "todayBirthdayScale"
    )
    val hasBirthdayToday = count > 0
    Box(
        modifier = Modifier.size(38.dp),
        contentAlignment = Alignment.Center
    ) {
        if (hasBirthdayToday) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                    .clip(CircleShape)
                    .background(AccentPink.copy(alpha = 0.22f))
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (hasBirthdayToday) Brush.linearGradient(listOf(AccentPink, AccentOrange))
                    else Brush.linearGradient(listOf(AccentCyan.copy(alpha = 0.28f), AccentBlue.copy(alpha = 0.22f)))
                ),
            contentAlignment = Alignment.Center
        ) {
            if (hasBirthdayToday) {
                Text(count.toString(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            } else {
                Icon(Icons.Filled.Cake, contentDescription = "No birthdays today", tint = AccentCyan, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun TodayFollowUpBeacon(count: Int) {
    val pulseTransition = rememberInfiniteTransition(label = "todayFollowUpPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "todayFollowUpScale"
    )
    val hasReminder = count > 0
    Box(
        modifier = Modifier.size(38.dp),
        contentAlignment = Alignment.Center
    ) {
        if (hasReminder) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                    .clip(CircleShape)
                    .background(AccentAmber.copy(alpha = 0.22f))
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (hasReminder) Brush.linearGradient(listOf(AccentOrange, AccentAmber))
                    else Brush.linearGradient(listOf(AccentCyan.copy(alpha = 0.28f), AccentBlue.copy(alpha = 0.22f)))
                ),
            contentAlignment = Alignment.Center
        ) {
            if (hasReminder) {
                Text(count.toString(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            } else {
                Icon(Icons.Filled.EventAvailable, contentDescription = "No follow-ups scheduled today", tint = AccentCyan, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun TodayFollowUpReminder(todayCount: Int, overdueCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(AccentAmber.copy(alpha = 0.11f))
            .border(1.dp, AccentAmber.copy(alpha = 0.34f), RoundedCornerShape(13.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text("Today's follow-ups", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                "$todayCount contact${if (todayCount == 1) "" else "s"} to make today",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
        if (overdueCount > 0) {
            Text(
                "$overdueCount overdue",
                color = AccentRed,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EnquiryStat(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value.toString(),
            color = valueColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEnquiryDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, phone: String, address: String, subjectName: String, enquiryDateMs: Long) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var subjectName by remember { mutableStateOf("") }
    var enquiryDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var attemptedSubmit by remember { mutableStateOf(false) }

    val nameError = attemptedSubmit && name.trim().isBlank()
    val phoneError = attemptedSubmit && phone.trim().isBlank()
    val subjectError = attemptedSubmit && subjectName.trim().isBlank()
    val formValid = !nameError && !phoneError && !subjectError &&
        name.trim().isNotBlank() && phone.trim().isNotBlank() && subjectName.trim().isNotBlank()
    val submitEnquiry = {
        attemptedSubmit = true
        if (formValid) {
            onSave(name, phone, address, subjectName, enquiryDateMs)
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = enquiryDateMs)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        enquiryDateMs = datePickerState.selectedDateMillis ?: enquiryDateMs
                        showDatePicker = false
                    }
                ) {
                    Text("Done", color = AccentCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            colors = DatePickerDefaults.colors(
                containerColor = DashboardCard,
                titleContentColor = TextPrimary,
                headlineContentColor = TextPrimary,
                weekdayContentColor = TextSecondary,
                subheadContentColor = TextSecondary,
                yearContentColor = TextSecondary,
                currentYearContentColor = AccentCyan,
                selectedYearContainerColor = AccentCyan,
                selectedDayContainerColor = AccentCyan,
                todayContentColor = AccentCyan,
                todayDateBorderColor = AccentCyan
            )
        ) {
            DatePicker(
                state = datePickerState,
                colors = DatePickerDefaults.colors(
                    containerColor = DashboardCard,
                    titleContentColor = TextPrimary,
                    headlineContentColor = TextPrimary,
                    weekdayContentColor = TextSecondary,
                    subheadContentColor = TextSecondary,
                    yearContentColor = TextSecondary,
                    currentYearContentColor = AccentCyan,
                    selectedYearContainerColor = AccentCyan,
                    selectedDayContainerColor = AccentCyan,
                    todayContentColor = AccentCyan,
                    todayDateBorderColor = AccentCyan
                )
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = DashboardBg,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DashboardCard)
                        .border(1.dp, DashboardStroke.copy(alpha = 0.8f))
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Add Enquiry",
                        color = TextPrimary,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            bottomBar = {
                Surface(color = DashboardBg) {
                    Button(
                        onClick = submitEnquiry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentCyan,
                            contentColor = DashboardBg
                        )
                    ) {
                        Text("Save Enquiry", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(DashboardBg, DashboardCardAlt.copy(alpha = 0.96f), DashboardBg)
                        )
                    )
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 20.dp)
            ) {
                Text(
                    "Add Enquiry",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Capture the student's first contact details.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )

                Spacer(Modifier.height(22.dp))

                EnquiryTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Student Name",
                    placeholder = "Enter name",
                    icon = Icons.Filled.Person,
                    isError = nameError,
                    errorText = "Name is required"
                )
                Spacer(Modifier.height(14.dp))

                EnquiryTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = "Phone Number",
                    placeholder = "Mobile number",
                    icon = Icons.Filled.Phone,
                    keyboardType = KeyboardType.Phone,
                    isError = phoneError,
                    errorText = "Phone number is required"
                )
                Spacer(Modifier.height(14.dp))

                EnquiryTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = "Address",
                    placeholder = "Enter address",
                    icon = Icons.Filled.Home,
                    singleLine = false,
                    minLines = 2
                )
                Spacer(Modifier.height(14.dp))

                EnquiryTextField(
                    value = subjectName,
                    onValueChange = { subjectName = it },
                    label = "Subject Name",
                    placeholder = "Main subject",
                    icon = Icons.Filled.MenuBook,
                    isError = subjectError,
                    errorText = "Subject name is required"
                )
                Spacer(Modifier.height(14.dp))

                EnquiryDateField(
                    dateMs = enquiryDateMs,
                    onClick = { showDatePicker = true }
                )
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = submitEnquiry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = DashboardBg
                    )
                ) {
                    Text("Save Enquiry", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun EnquiryTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    errorText: String? = null
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(icon, contentDescription = null, tint = AccentCyan) },
            singleLine = singleLine,
            minLines = minLines,
            isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = DashboardCardAlt,
                unfocusedContainerColor = DashboardCardAlt,
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = DashboardStroke,
                errorBorderColor = AccentRed,
                cursorColor = AccentCyan,
                focusedLabelColor = AccentCyan,
                unfocusedLabelColor = TextSecondary,
                focusedPlaceholderColor = TextMuted,
                unfocusedPlaceholderColor = TextMuted
            )
        )
        if (isError && errorText != null) {
            Spacer(Modifier.height(5.dp))
            Text(errorText, color = AccentRed, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun EnquiryDateField(
    dateMs: Long,
    onClick: () -> Unit
) {
    Column {
        Text("Enquiry Date", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DashboardCardAlt)
                .border(1.dp, DashboardStroke, RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatEnquiryDate(dateMs),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Filled.DateRange, contentDescription = "Pick enquiry date", tint = AccentCyan, modifier = Modifier.size(22.dp))
        }
    }
}

private fun formatEnquiryDate(dateMs: Long): String =
    SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(java.util.Date(dateMs))

private fun formatDashboardAmount(amount: Double): String =
    java.text.NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        maximumFractionDigits = 0
    }.format(amount)

@Composable
private fun DashboardQuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(86.dp)
            .premiumClickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = DashboardCard),
        shape = RoundedCornerShape(15.dp),
        border = borderStroke()
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 11.dp)) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.height(7.dp))
            Text(title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OverviewRow(
    icon: ImageVector,
    label: String,
    active: Int,
    inactive: Int,
    onClick: () -> Unit
) {
    // ── Glow / shining animation ───────────────────────────
    // ShimmerOffset sweeps a highlight across the button from left to right
    val infiniteTransition = rememberInfiniteTransition(label = "overviewGlow_$label")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -300f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(4400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )
    // Text / icon color gently pulses between cyan and a brighter white-cyan
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        // Subtle sweep highlight that glides across the row
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            AccentCyan.copy(alpha = 0.06f),
                            AccentCyan.copy(alpha = 0.12f),
                            AccentCyan.copy(alpha = 0.06f),
                            Color.Transparent
                        ),
                        start = Offset(shimmerOffset - 60f, 0f),
                        end = Offset(shimmerOffset + 60f, 0f)
                    )
                )
        )
        // Foreground row content above the glow layer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Glowing icon — color pulses with glowAlpha
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color(
                        red = AccentCyan.red,
                        green = AccentCyan.green,
                        blue = AccentCyan.blue,
                        alpha = glowAlpha
                    ),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                // Label text — gently pulses toward a lighter cyan-white
                Text(
                    label,
                    color = androidx.compose.ui.graphics.lerp(TextPrimary, AccentCyan, glowAlpha * 0.3f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentCyan.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text("$active", color = AccentCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(4.dp))
            Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, color = TextPrimary, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun MiniCard(title: String, subtitle: String, progress: Float, textProgress: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DashboardCard),
        border = borderStroke()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${(progress*100).toInt()}%", color = TextPrimary, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(textProgress, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = AccentGreen, trackColor = DashboardBg)
        }
    }
}

// ── New attendance composables ──────────────────────────────

@Composable
private fun PolishedAttendanceOverview(
    studentSummary: BatchAttendanceSummary,
    staffSummary: StaffAttendanceSummary
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 9.dp)) {
        CompactDetailedAttendanceRow(
            label = "Students",
            marked = studentSummary.markedCount,
            total = studentSummary.totalStudents,
            present = studentSummary.presentCount,
            absent = studentSummary.absentCount,
            leave = studentSummary.leaveCount,
            holiday = studentSummary.holidayCount
        )
        Spacer(Modifier.height(9.dp))
        CompactDetailedAttendanceRow(
            label = "Staff",
            marked = staffSummary.markedCount,
            total = staffSummary.totalStaff,
            present = staffSummary.presentCount,
            absent = staffSummary.absentCount,
            leave = staffSummary.leaveCount,
            holiday = staffSummary.holidayCount
        )
    }
}

@Composable
private fun CompactDetailedAttendanceRow(
    label: String,
    marked: Int,
    total: Int,
    present: Int,
    absent: Int,
    leave: Int,
    holiday: Int
) {
    val chartTotal = (present + absent + leave + holiday).coerceAtLeast(1)
    val values = listOf(present, absent, leave, holiday)
    val colors = listOf(AccentGreen, AccentRed, AccentSky, AccentGray)
    val captions = if (label == "Students") listOf("Present", "Absent", "Leave", "Holiday") else listOf("P", "A", "L", "H")
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("$marked/$total marked", color = TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(5.dp))
        Row(modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp))) {
            values.forEachIndexed { index, value ->
                if (value > 0) {
                    Box(Modifier.weight(value.toFloat()).fillMaxHeight().background(colors[index]))
                }
            }
            if (values.sum() == 0) Box(Modifier.fillMaxSize().background(DashboardCardAlt))
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            values.forEachIndexed { index, value ->
                val percent = value * 100 / chartTotal
                Text("● ${captions[index]} $percent%", color = colors[index], fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun AttendanceSegmentedBar(sum: BatchAttendanceSummary, label: String) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            Text("${sum.markedCount}/${sum.totalStudents} marked today", color = TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        val total = sum.chartTotal.toFloat().coerceAtLeast(1f)
        val pW = sum.presentCount / total; val aW = sum.absentCount / total
        val lW = sum.leaveCount / total; val hW = sum.holidayCount / total
        Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            val w = size.width; val barH = size.height; val r = barH / 2
            var x = 0f
            drawRoundRect(AccentGreen, Offset(x, 0f), Size(w * pW, barH), androidx.compose.ui.geometry.CornerRadius(r, r))
            x += w * pW
            drawRect(AccentRed, Offset(x, 0f), Size(w * aW, barH))
            x += w * aW
            drawRect(AccentSky, Offset(x, 0f), Size(w * lW, barH))
            x += w * lW
            drawRoundRect(AccentGray, Offset(x, 0f), Size(w * hW, barH), androidx.compose.ui.geometry.CornerRadius(r, r))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            LegendItem(AccentGreen, "Present", "${"%.0f".format(sum.presentPct)}%")
            LegendItem(AccentRed, "Absent", "${"%.0f".format(sum.absentPct)}%")
            LegendItem(AccentSky, "Leave", "${"%.0f".format(sum.leavePct)}%")
            LegendItem(AccentGray, "Holiday", "${"%.0f".format(sum.holidayPct)}%")
        }
    }
}

@Composable
private fun StaffSegmentedBar(sum: StaffAttendanceSummary) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Staff", color = TextSecondary, fontSize = 12.sp)
            Text("${sum.markedCount}/${sum.totalStaff} marked today", color = TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        val total = sum.chartTotal.toFloat().coerceAtLeast(1f)
        val pW = sum.presentCount / total; val aW = sum.absentCount / total
        val lW = sum.leaveCount / total; val hW = sum.holidayCount / total
        Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            val w = size.width; val barH = size.height; val r = barH / 2
            var x = 0f
            drawRoundRect(AccentGreen, Offset(x, 0f), Size(w * pW, barH), androidx.compose.ui.geometry.CornerRadius(r, r))
            x += w * pW
            drawRect(AccentRed, Offset(x, 0f), Size(w * aW, barH))
            x += w * aW
            drawRect(AccentSky, Offset(x, 0f), Size(w * lW, barH))
            x += w * lW
            drawRoundRect(AccentGray, Offset(x, 0f), Size(w * hW, barH), androidx.compose.ui.geometry.CornerRadius(r, r))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            LegendItem(AccentGreen, "P", "${"%.0f".format(sum.presentPct)}%")
            LegendItem(AccentRed, "A", "${"%.0f".format(sum.absentPct)}%")
            LegendItem(AccentSky, "L", "${"%.0f".format(sum.leavePct)}%")
            LegendItem(AccentGray, "H", "${"%.0f".format(sum.holidayPct)}%")
        }
    }
}

@Composable
private fun BatchMiniCard(name: String, total: Int, marked: Int, presentPct: Float, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(150.dp).height(80.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCardAlt),
        border = borderStroke()
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text("$marked/$total", color = TextMuted, fontSize = 11.sp)
            LinearProgressIndicator(
                progress = { presentPct / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = AccentGreen, trackColor = DashboardBg
            )
        }
    }
}

@Composable
private fun AttendanceMiniCard(label: String, icon: ImageVector, marked: Int, total: Int, detail: String, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.premiumClickable(onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCard),
        border = borderStroke()
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(5.dp))
            Text("$marked / $total $detail", color = TextSecondary, fontSize = 12.sp)
            if (marked > 0) {
                Spacer(Modifier.height(4.dp))
                val prog = if (total > 0) marked.toFloat() / total else 0f
                LinearProgressIndicator(
                    progress = { prog }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(3.dp)),
                    color = accent, trackColor = DashboardBg
                )
            }
        }
    }
}

@Composable
private fun dialogStat(label: String, pct: String, color: Color, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(pct, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("$count $label", color = TextSecondary, fontSize = 10.sp)
    }
}

@Composable
private fun TableRow(label: String, val1: String, val2: String, val3: String, isTotal: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = if (isTotal) TextPrimary else TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(val1, color = TextPrimary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(val2, color = TextPrimary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(val3, color = if (isTotal) AccentCyan else TextPrimary, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

// ── Animated counter that counts up on first render ─────────────
@Composable
private fun AnimatedCounter(
    target: Double,
    prefix: String = "",
    suffix: String = "",
    durationMillis: Int = 1200
) {
    val animatedValue by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing),
        label = "counter"
    )
    Text(
        "$prefix${animatedValue.toLong()}$suffix",
        color = TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun MoreScreen(
    onLogout: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val currentRole by SessionManager.currentUserRole.collectAsState()
    val currentStaffPermissions by SessionManager.currentStaffPermissions.collectAsState()
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    val moreItems = remember(currentRole, currentStaffPermissions) {
        listOf(
            "Staff Management" to "StaffRoute",
            "Staff Attendance" to "StaffAttendanceRoute",
            "Salary Management" to "SalaryRoute",
            "All Archives" to "AllArchivesRoute",
            "Expenses" to "ExpensesRoute",
            "Profit & Loss" to "ProfitLossRoute",
            "ID Card Generator" to "IdCardGeneratorRoute",
            "Take Attendance" to "AttendanceRoute",
            "Attendance Reports" to "AttendanceReportRoute",
            "Settings" to "SettingsRoute",
            "Reminder Templates" to "ReminderTemplatesRoute"
        ).filter { AccessControl.canAccessRoute(it.second) }
    }

    Column(modifier = Modifier.fillMaxSize().background(DashboardBg).padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("More Features", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
        Spacer(Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DashboardCard),
            border = borderStroke()
        ) {
            Column {
                if (moreItems.isEmpty()) {
                    Text(
                        "Your admin has not enabled any work permissions for this account yet.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    moreItems.forEachIndexed { index, item ->
                        ListItem(
                            headlineContent = { Text(item.first, color = TextPrimary) },
                            modifier = Modifier.fillMaxWidth().clickable { onNavigate(item.second) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        if (index != moreItems.lastIndex) {
                            HorizontalDivider(color = DashboardStroke)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { showLogoutConfirmation = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Logout", fontWeight = FontWeight.Bold, color = TextPrimary)
        }
        Spacer(Modifier.height(100.dp))
    }

    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            containerColor = DashboardCard,
            title = { Text("Log out?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("You will need to sign in again to access this institute.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirmation = false
                    onLogout()
                }) {
                    Text("Logout", color = AccentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmation = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}


