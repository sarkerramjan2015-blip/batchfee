package com.example.ui.dashboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.data.models.InstituteEntity
import com.example.domain.SessionManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.example.ui.components.AnimatedGlowBorder
import com.example.ui.components.BatchFeeBottomNav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import com.example.ui.attendance.AttendanceViewModel
import com.example.ui.attendance.AttendanceViewModelFactory
import com.example.ui.attendance.BatchAttendanceSummary
import com.example.ui.attendance.StaffAttendanceSummary
import com.example.ui.attendance.getCurrentMonthRange

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
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)
private val TextMuted = Color(0xFF64748B)

data class FinancialSummary(
    val todayIncome: Double = 0.0,
    val todayExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
    val monthExpense: Double = 0.0,
    val lifetimeIncome: Double = 0.0,
    val lifetimeExpense: Double = 0.0
)

class DashboardViewModel(private val db: AppDatabase) : ViewModel() {
    private val _institute = MutableStateFlow<InstituteEntity?>(null)
    val institute = _institute.asStateFlow()
    
    private val _trialDaysLeft = MutableStateFlow(0)
    val trialDaysLeft = _trialDaysLeft.asStateFlow()

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

    // Logged-in admin/owner user (for profile popup). Read-only; no schema change.
    private val _currentUser = MutableStateFlow<com.example.data.models.UserEntity?>(null)
    val currentUser = _currentUser.asStateFlow()

    // Active subscription plan for the institute (for profile popup).
    private val _currentPlan = MutableStateFlow<com.example.data.models.SubscriptionPlanEntity?>(null)
    val currentPlan = _currentPlan.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val instId = SessionManager.currentInstituteId.value ?: return@launch
            launch {
                db.instituteDao().getInstituteFlow(instId).collect { inst ->
                    _institute.value = inst
                    if (inst != null && inst.subscriptionStatus == "trial") {
                        val remainingMs = inst.trialEndDateMs - System.currentTimeMillis()
                        val days = (remainingMs / (1000 * 60 * 60 * 24)).coerceAtLeast(0).toInt()
                        _trialDaysLeft.value = days
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
                kotlinx.coroutines.flow.combine(
                    db.paymentDao().getRecentPayments(instId),
                    db.expenseDao().getExpensesByInstitute(instId)
                ) { payments, expenses ->
                    val now = java.util.Calendar.getInstance()
                    val startOfDay = now.apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
                    val startOfMonth = now.apply { set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0) }.timeInMillis
                    
                    var tI = 0.0; var tE = 0.0; var mI = 0.0; var mE = 0.0; var lI = 0.0; var lE = 0.0
                    
                    payments.forEach { p ->
                        lI += p.amount
                        if (p.paymentDateMs >= startOfMonth) mI += p.amount
                        if (p.paymentDateMs >= startOfDay) tI += p.amount
                    }
                    expenses.forEach { e ->
                        lE += e.amount
                        if (e.expenseDateMs >= startOfMonth) mE += e.amount
                        if (e.expenseDateMs >= startOfDay) tE += e.amount
                    }
                    FinancialSummary(tI, tE, mI, mE, lI, lE)
                }.collect { stats ->
                    _financialSummary.value = stats
                }
            }
            launch {
                db.feeDao().getDueFees(instId).collect { fees ->
                    _pendingFeesCount.value = fees.size
                }
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
        bottomBar = {
            BatchFeeBottomNav(currentRoute = currentRoute, onNavigate = onNavigate)
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (currentRoute) {
                "DashboardRoute" -> DashboardScreen(db, onNavigatePricing, onNavigateBilling, onLogout, onNavigate)
                "More" -> MoreScreen(onNavigateBilling, onLogout, onNavigate)
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

    var showFabMenu by remember { mutableStateOf(false) }
    var showProfilePopup by remember { mutableStateOf(false) }
    val snappbarcoroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val financialSummary by viewModel.financialSummary.collectAsState()
    val pendingFeesCount by viewModel.pendingFeesCount.collectAsState()

    // ── Edit / Image / Switch state for profile popup ────────
    var showEditDialog by remember { mutableStateOf(false) }
    var editInstituteName by remember { mutableStateOf(institute?.name ?: "") }
    var profilePhotoUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current

    // Camera/gallery launcher for profile photo
    val tempPhotoFile = remember { File(context.cacheDir, "profile_photo_${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() } }
    val tempPhotoUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempPhotoFile)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success -> if (success) profilePhotoUri = tempPhotoUri }

    // Gallery picker launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) profilePhotoUri = uri }

    // ── Attendance state (shared with dialog) ──────────────
    val attVM: AttendanceViewModel = viewModel(factory = AttendanceViewModelFactory(db))
    val attSummaries by attVM.batchSummaries.collectAsState()
    val staffSum by attVM.staffAttendanceSummary.collectAsState()
    var attLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { attVM.loadDashboardSummaries(); attLoading = false }
    val currentMonthLabel = remember {
        SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(java.util.Calendar.getInstance().time)
    }
    val studentOverall = remember(attSummaries) {
        if (attSummaries.isEmpty()) null else BatchAttendanceSummary(
            batchName = "All Batches",
            totalStudents = attSummaries.sumOf { it.totalStudents },
            presentCount = attSummaries.sumOf { it.presentCount },
            absentCount = attSummaries.sumOf { it.absentCount },
            leaveCount = attSummaries.sumOf { it.leaveCount },
            holidayCount = attSummaries.sumOf { it.holidayCount }
        )
    }
    var selectedBatchId by remember { mutableStateOf<String?>(null) }

    val safeNavigate: (String) -> Unit = { route ->
        val allowedRoutes = setOf("StudentsRoute", "AddStudentRoute", "BatchesRoute", "AddBatchRoute", "FeeDashboardRoute", "DueFeesRoute", "CreateFeeRoute", "AttendanceRoute", "AttendanceReportRoute", "ReportsRoute", "ReminderTemplatesRoute", "StaffRoute", "SalaryRoute", "ExpensesRoute", "ProfitLossRoute", "ExamsRoute", "IdCardGeneratorRoute", "BirthdayReminderRoute", "SettingsRoute")
        if (allowedRoutes.contains(route)) {
            onNavigate(route)
        } else {
            snappbarcoroutineScope.launch { snackbarHostState.showSnackbar("Coming soon") }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = DashboardBg,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (showFabMenu) {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 16.dp)) {
                        val actions = listOf(
                            "Add Student" to "AddStudentRoute",
                            "Create Batch" to "AddBatchRoute",
                            "Create Fee" to "CreateFeeRoute",
                            "Take Attendance" to "AttendanceRoute",
                            "Add Expense" to "ExpensesRoute",
                            "Add Staff" to "StaffRoute"
                        )
                        actions.forEach { (label, route) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                                Text(label, color = TextPrimary, style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.width(8.dp))
                                FloatingActionButton(
                                    onClick = { showFabMenu = false; safeNavigate(route) },
                                    containerColor = DashboardCard,
                                    contentColor = AccentCyan,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        when(label) {
                                            "Add Student" -> Icons.Filled.PersonAdd
                                            "Create Batch" -> Icons.Filled.Class
                                            "Create Fee" -> Icons.Filled.Payments
                                            "Take Attendance" -> Icons.Filled.HowToReg
                                            "Add Expense" -> Icons.Filled.Receipt
                                            else -> Icons.Filled.PersonAdd
                                        }, 
                                        contentDescription = label
                                    )
                                }
                            }
                        }
                    }
                }
                
                AnimatedGlowBorder(
                    cornerRadius = 16.dp,
                    backgroundColor = AccentCyan,
                    borderWidth = 2.dp,
                    animationDurationMillis = 3000
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clickable { showFabMenu = !showFabMenu },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (showFabMenu) Icons.Filled.Close else Icons.Filled.Add, contentDescription = "Add", tint = DashboardBg)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DashboardCard)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AccentCyan)
                        .clickable { showProfilePopup = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (institute?.name ?: "B").take(1).uppercase(),
                        color = DashboardBg,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f).clickable { showProfilePopup = true }) {
                    Text(
                        institute?.name ?: "BatchFee Institute",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("View Profile", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = { onNavigate("SettingsRoute") }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextPrimary)
                }
            }
            
            // Thin accent line
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(AccentCyan, AccentGreen, AccentBlue))
            ))

            Column(modifier = Modifier.padding(16.dp)) {
                if (institute?.subscriptionStatus == "trial") {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = DashboardCard),
                        border = borderStroke()
                    ) {
                        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = AccentCyan)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("30-day Free Trial", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                                Text("$trialDays days left", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = onNavigatePricing) {
                                Text("Upgrade", color = AccentCyan)
                            }
                        }
                    }
                }

                // Overview Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DashboardCard),
                    border = borderStroke()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Overview", color = TextPrimary, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("Active", color = AccentCyan, style = MaterialTheme.typography.labelSmall)
                                Text("Inactive", color = AccentRed, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
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

                Spacer(modifier = Modifier.height(16.dp))

                // Staff Logs Card
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { safeNavigate("StaffRoute") },
                    colors = CardDefaults.cardColors(containerColor = DashboardCard),
                    border = borderStroke()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.History, contentDescription = null, tint = AccentBlue)
                            Spacer(Modifier.width(12.dp))
                            Text("Staff Logs", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                        }
                        Text("View more", color = AccentCyan, style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Live Attendance Summary ────────────────────

                // ── Main attendance card ───────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(16.dp), spotColor = AccentCyan.copy(0.15f)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DashboardCard),
                    border = borderStroke()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Attendance Summary", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(currentMonthLabel, color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(12.dp))

                        if (attLoading) {
                            // Shimmer placeholder
                            Column(Modifier.fillMaxWidth().height(90.dp), verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AccentCyan, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("Loading…", color = TextSecondary, fontSize = 12.sp)
                            }
                        } else if (studentOverall != null && studentOverall.markedCount > 0) {
                            // ── Student segmented bar ──────────────
                            AttendanceSegmentedBar(studentOverall, "Students")
                            Spacer(Modifier.height(12.dp))
                            // ── Staff segmented bar ────────────────
                            val staffTot = staffSum.totalStaff.coerceAtLeast(1)
                            StaffSegmentedBar(staffSum)
                        } else {
                            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.CalendarMonth, null, tint = TextSecondary.copy(0.4f), modifier = Modifier.size(40.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("No attendance recorded yet.", color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // ── Per-batch cards ─────────────────────────────
                if (!attLoading && attSummaries.isNotEmpty()) {
                    // "Per Batch" label row
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Per Batch", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (attSummaries.size > 2) {
                            TextButton(onClick = { safeNavigate("AttendanceReportRoute") }, contentPadding = PaddingValues(0.dp)) {
                                Text("View All →", color = AccentCyan, fontSize = 12.sp)
                            }
                        }
                    }
                    // LazyRow of batch mini-cards
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(end = 8.dp)
                    ) {
                        items(attSummaries.take(5)) { batchSum ->
                            BatchMiniCard(
                                name = batchSum.batchName,
                                total = batchSum.totalStudents,
                                marked = batchSum.markedCount,
                                presentPct = batchSum.presentPct,
                                onClick = { selectedBatchId = batchSum.batchId }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // ── Mini Cards (Student + Staff marking) ────────
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val sMarked = studentOverall?.markedCount ?: 0
                    val sTotal = studentOverall?.totalStudents ?: 0
                    AttendanceMiniCard("Student", Icons.Filled.School, sMarked, sTotal, AccentGreen, { safeNavigate("AttendanceRoute") }, Modifier.weight(1f))
                    val stMarked = staffSum.markedCount
                    val stTotal = staffSum.totalStaff
                    AttendanceMiniCard("Staff", Icons.Filled.Group, stMarked, stTotal, AccentSky, { safeNavigate("StaffRoute") }, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Financial Collection Cards ────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Today Collection
                    Card(
                        modifier = Modifier.weight(1f).clickable {
                            safeNavigate("ReportsRoute")
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = DashboardCard),
                        border = borderStroke()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Today", color = TextSecondary, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            AnimatedCounter(target = financialSummary.todayIncome, prefix = "BDT ")
                            Text("Collected", color = AccentCyan, fontSize = 10.sp)
                        }
                    }
                    // Monthly Collection
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = DashboardCard),
                        border = borderStroke()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Monthly", color = TextSecondary, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            AnimatedCounter(target = financialSummary.monthIncome, prefix = "BDT ")
                            Text("Collected", color = AccentSky, fontSize = 10.sp)
                        }
                    }
                    // Lifetime Collection
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = DashboardCard),
                        border = borderStroke()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Lifetime", color = TextSecondary, fontSize = 11.sp)
                            Spacer(Modifier.height(4.dp))
                            AnimatedCounter(target = financialSummary.lifetimeIncome, prefix = "BDT ")
                            Text("Collected", color = AccentViolet, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Due Fees & Pending Card ─────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { safeNavigate("DueFeesRoute") },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DashboardCard),
                    border = borderStroke()
                ) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(AccentRed.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = AccentRed, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Pending Due Fees", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("$pendingFeesCount student(s) have unpaid fees", color = TextSecondary, fontSize = 12.sp)
                        }
                        Text("View →", color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Shortcut Grid ──────────────────────────────
                Text("Quick Actions", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                val shortcuts = listOf(
                    Triple("Add Student", Icons.Filled.PersonAdd, "AddStudentRoute"),
                    Triple("Create Batch", Icons.Filled.Class, "AddBatchRoute"),
                    Triple("Collect Fee", Icons.Filled.Payments, "DueFeesRoute"),
                    Triple("Attendance", Icons.Filled.HowToReg, "AttendanceRoute"),
                    Triple("Add Expense", Icons.Filled.Receipt, "ExpensesRoute"),
                    Triple("Add Staff", Icons.Filled.PersonAddAlt1, "StaffRoute")
                )
                // FIX: Replaced LazyVerticalGrid with manual Row/Column grid.
                // LazyVerticalGrid nested inside a scrollable Column receives
                // unbounded height constraints, causing a crash. A non-lazy
                // Column with Row rows avoids infinite-height measurement.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        shortcuts.take(3).forEach { (label, icon, route) ->
                            ShortcutItem(label, icon, Modifier.weight(1f), { safeNavigate(route) })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        shortcuts.drop(3).forEach { (label, icon, route) ->
                            ShortcutItem(label, icon, Modifier.weight(1f), { safeNavigate(route) })
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(120.dp))
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
                            AttendanceSegmentedBar(sum, "Students: ${sum.markedCount}/${sum.totalStudents}")
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                dialogStat("Present", "${"%.0f".format(sum.presentPct)}%", AccentGreen, sum.presentCount)
                                dialogStat("Absent", "${"%.0f".format(sum.absentPct)}%", AccentRed, sum.absentCount)
                                dialogStat("Leave", "${"%.0f".format(sum.leavePct)}%", AccentSky, sum.leaveCount)
                                dialogStat("Holiday", "${"%.0f".format(sum.holidayPct)}%", AccentGray, sum.holidayCount)
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { selectedBatchId = null; safeNavigate("TakeAttendanceRoute") },
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
                            .height(120.dp)
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
                                onClick = {
                                    editInstituteName = institute?.name ?: ""
                                    showEditDialog = true
                                },
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Edit", color = AccentCyan, fontSize = 11.sp)
                            }
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
                        
                        // Avatar — clickable to change profile photo via camera or gallery
                        var showPhotoPicker by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 24.dp)
                                .offset(y = 36.dp)
                                .size(72.dp)
                                .shadow(12.dp, CircleShape, spotColor = AccentBlue, ambientColor = AccentBlue)
                                .clip(CircleShape)
                                .background(Color(0xFF0F1629))
                                .border(2.dp, AccentBlue, CircleShape)
                                .clickable { showPhotoPicker = true },
                            contentAlignment = Alignment.Center
                        ) {
                            // Show photo if selected, otherwise initials
                            val photo = profilePhotoUri
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

                        // Photo picker dialog (camera or gallery)
                        if (showPhotoPicker) {
                            AlertDialog(
                                onDismissRequest = { showPhotoPicker = false },
                                title = { Text("Profile Photo", color = Color.White) },
                                text = { Text("Choose an option", color = TextSecondary) },
                                confirmButton = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = {
                                            showPhotoPicker = false
                                            try { cameraLauncher.launch(tempPhotoUri) } catch (_: Exception) {}
                                        }) { Text("Camera", color = AccentCyan) }
                                        TextButton(onClick = {
                                            showPhotoPicker = false
                                            galleryLauncher.launch("image/*")
                                        }) { Text("Gallery", color = AccentCyan) }
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
                    }
                    
                    Spacer(Modifier.height(48.dp))
                    
                    // Profile Info
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            institute?.name ?: "BatchFee Demo Institute",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            currentUser?.email ?: "owner@batchfee.app",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        
                        Spacer(Modifier.height(12.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Code", color = TextSecondary, fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF1A265E), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(institute?.id ?: "DEMO", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Phone, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Not added", color = TextSecondary, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Not added", color = TextSecondary, fontSize = 13.sp)
                        }
                        
                        Spacer(Modifier.height(20.dp))
                        Text("Current Subscription", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        
                        // Subscription Card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(
                                        colors = listOf(Color(0xFF161D35), Color(0xFF0D1322))
                                    )
                                )
                                .border(1.dp, Color(0xFF1E2A45), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Column {
                                val isTrial = institute?.subscriptionStatus == "trial"
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(30.dp).background(Color(0xFF4C5DDB), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.WorkspacePremium, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text("Current Plan", color = TextSecondary, fontSize = 11.sp)
                                        Text(currentPlan?.name ?: if (isTrial) "Free Trial" else "Active Plan", color = AccentBlue, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Group, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("${currentPlan?.maxStudents ?: 100} Students", color = TextSecondary, fontSize = 13.sp)
                                }
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = Color(0xFF1E2A45))
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CalendarToday, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(if (isTrial) "Trial active" else "Next Renewal", color = TextSecondary, fontSize = 11.sp)
                                        val remainingText = if (isTrial) "$trialDays days left" else "Active"
                                        Text(remainingText, color = AccentCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(24.dp))
                        
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
                                .height(54.dp)
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
                                    showProfilePopup = false
                                    try { onNavigatePricing() } catch (e: Exception) {
                                        snappbarcoroutineScope.launch { snackbarHostState.showSnackbar("Subscription plan screen coming soon") }
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
                                val instituteName = institute?.name ?: "BatchFee Institute"
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
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Institute", color = Color.White) },
            text = {
                Column {
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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Persist institute name update to DB
                    val inst = institute
                    if (inst != null && editInstituteName.isNotBlank()) {
                        val updated = inst.copy(name = editInstituteName.trim())
                        snappbarcoroutineScope.launch {
                            db.instituteDao().updateInstitute(updated)
                        }
                    }
                    showEditDialog = false
                }) { Text("Save", color = AccentCyan) }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = Color(0xFF0F1629)
        )
    }
}
}

@Composable
private fun borderStroke() = androidx.compose.foundation.BorderStroke(1.dp, DashboardStroke)

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
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )
    // Text / icon color gently pulses between cyan and a brighter white-cyan
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
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
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$active", color = AccentCyan, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                Text("$inactive", color = AccentRed, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
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
private fun AttendanceSegmentedBar(sum: BatchAttendanceSummary, label: String) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            Text("${sum.markedCount}/${sum.totalStudents}", color = TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        val total = sum.totalStudents.toFloat().coerceAtLeast(1f)
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
            Text("${sum.markedCount}/${sum.totalStaff}", color = TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        val total = sum.totalStaff.toFloat().coerceAtLeast(1f)
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
private fun AttendanceMiniCard(label: String, icon: ImageVector, marked: Int, total: Int, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DashboardCard),
        border = borderStroke()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text("$marked / $total marked", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            val prog = if (total > 0) marked.toFloat() / total else 0f
            LinearProgressIndicator(
                progress = { prog }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = accent, trackColor = DashboardBg
            )
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

@Composable
private fun ShortcutItem(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DashboardCard)
                .border(1.dp, DashboardStroke, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = AccentCyan, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, color = TextPrimary, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
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
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1
    )
}

@Composable
fun MoreScreen(
    onNavigateBilling: () -> Unit,
    onLogout: () -> Unit,
    onNavigate: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(DashboardBg).padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("More Features", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = TextPrimary)
        Spacer(Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DashboardCard),
            border = borderStroke()
        ) {
            Column {
                ListItem(headlineContent = { Text("Staff Management", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigate("StaffRoute") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(color = DashboardStroke)
                ListItem(headlineContent = { Text("Salary Management", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigate("SalaryRoute") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(color = DashboardStroke)
                ListItem(headlineContent = { Text("Expenses", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigate("ExpensesRoute") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(color = DashboardStroke)
                ListItem(headlineContent = { Text("Profit & Loss", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigate("ProfitLossRoute") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(color = DashboardStroke)
                ListItem(headlineContent = { Text("Exams & Results", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigate("ExamsRoute") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(color = DashboardStroke)
                ListItem(headlineContent = { Text("ID Card Generator", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigate("IdCardGeneratorRoute") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(color = DashboardStroke)
                ListItem(headlineContent = { Text("Birthday Reminders", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigate("BirthdayReminderRoute") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(color = DashboardStroke)
                ListItem(headlineContent = { Text("Take Attendance", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigate("AttendanceRoute") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(color = DashboardStroke)
                ListItem(headlineContent = { Text("Attendance Reports", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigate("AttendanceReportRoute") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(color = DashboardStroke)
                ListItem(headlineContent = { Text("Institute Reports", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigate("ReportsRoute") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(color = DashboardStroke)
                ListItem(headlineContent = { Text("Settings", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigate("SettingsRoute") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(color = DashboardStroke)
                ListItem(headlineContent = { Text("Reminder Templates", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigate("ReminderTemplatesRoute") }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider(color = DashboardStroke)
                ListItem(headlineContent = { Text("Billing & Subscription", color = TextPrimary) }, modifier = Modifier.fillMaxWidth().clickable { onNavigateBilling() }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onLogout, 
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Logout", fontWeight = FontWeight.Bold, color = TextPrimary)
        }
        Spacer(Modifier.height(100.dp))
    }
}

