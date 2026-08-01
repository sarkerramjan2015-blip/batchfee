package com.batchfee.edu

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.batchfee.edu.domain.AccessControl
import com.batchfee.edu.domain.ForceUpdateChecker
import com.batchfee.edu.domain.PasswordHasher
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.ThemePreferences
import com.batchfee.edu.ui.auth.AuthScreen
import com.batchfee.edu.ui.billing.BillingScreen
import com.batchfee.edu.ui.dashboard.DashboardScreen
import com.batchfee.edu.ui.legal.PrivacyPolicyScreen
import com.batchfee.edu.ui.legal.TermsConditionsScreen
import com.batchfee.edu.ui.navigation.*
import com.batchfee.edu.ui.pricing.PricingScreen
import com.batchfee.edu.ui.superadmin.SuperAdminScreen
import com.batchfee.edu.ui.subscription.SubscriptionExpiredScreen
import com.batchfee.edu.ui.theme.MyApplicationTheme
import com.batchfee.edu.ui.update.ForceUpdateScreen
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionManager.markActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appDb = (application as BatchFeeApp).database
        
        setContent {
            val darkMode by ThemePreferences.isDarkMode.collectAsState()
            MyApplicationTheme(darkTheme = darkMode ?: isSystemInDarkTheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var forceUpdate by remember { mutableStateOf<Int?>(null) }

                    // ── Force Update Check ──
                    if (forceUpdate != null) {
                        ForceUpdateScreen(requiredVersion = forceUpdate!!)
                    } else {
                        LaunchedEffect(Unit) {
                            val result = ForceUpdateChecker.check()
                            if (result is ForceUpdateChecker.UpdateResult.UpdateRequired) {
                                forceUpdate = result.requiredVersion
                            }
                        }

                        MainAppContent(appDb)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainAppContent(appDb: com.batchfee.edu.data.database.AppDatabase) {
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current
    val isLoggedIn by SessionManager.currentUserId.collectAsState()
    val sessionNotice by SessionManager.sessionNotice.collectAsState()
    val lastActivityAtMs by SessionManager.lastActivityAtMs.collectAsState()
    var wasLoggedIn by remember { mutableStateOf(false) }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn == null && wasLoggedIn) {
            navController.navigate(AuthRoute) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
        wasLoggedIn = isLoggedIn != null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && SessionManager.isLoggedIn()) {
                if (SessionManager.isSessionInactive()) {
                    SessionManager.expireSession()
                } else {
                    SessionManager.markActivity()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Firebase can invalidate a credential independently of the inactivity timer.
    // Treat that as one centralized expired-session event while a local session exists.
    DisposableEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            if (SessionManager.isLoggedIn() && firebaseAuth.currentUser == null) {
                SessionManager.expireSession()
            }
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    LaunchedEffect(isLoggedIn) {
        val uid = isLoggedIn ?: return@LaunchedEffect
        val role = SessionManager.currentUserRole.value ?: return@LaunchedEffect
        if (role == "SuperAdmin") return@LaunchedEffect
        val instId = SessionManager.currentInstituteId.value ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                FirebaseFirestore.getInstance()
                    .collection("institutes").document(instId)
                    .update("lastActiveAt", System.currentTimeMillis())
                    .await()
            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
        while (true) {
            delay(5 * 60 * 1000L)
            if (SessionManager.currentUserId.value != uid) break
            withContext(Dispatchers.IO) {
                try {
                    FirebaseFirestore.getInstance()
                        .collection("institutes").document(instId)
                        .update("lastActiveAt", System.currentTimeMillis())
                        .await()
                } catch (e: Exception) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }
            // Periodically check if subscription has expired (institute owner only — staff are not subscription-gated)
            if (role != "Staff" && SessionManager.currentUserId.value == uid) {
                val expired = checkSubscriptionExpired(instId, appDb)
                if (expired) {
                    SessionManager.expireSession()
                    break
                }
            }
        }
    }

    LaunchedEffect(isLoggedIn, lastActivityAtMs) {
        val activeUserId = isLoggedIn ?: return@LaunchedEffect
        val elapsedMs = System.currentTimeMillis() - lastActivityAtMs
        val remainingMs = (SessionManager.SESSION_TIMEOUT_MS - elapsedMs).coerceAtLeast(0)
        delay(remainingMs)
        if (SessionManager.currentUserId.value == activeUserId && SessionManager.isSessionInactive()) {
            SessionManager.expireSession()
        }
    }

    NavHost(navController = navController, startDestination = AuthRoute) {
        composable<AuthRoute> {
            val scope = rememberCoroutineScope()
            AuthScreen(
                db = appDb,
                sessionNotice = sessionNotice,
                onNavigateDashboard = {
                    scope.launch {
                        val instituteId = SessionManager.currentInstituteId.value
                        val role = SessionManager.currentUserRole.value
                        if (role != "SuperAdmin" && role != "Staff" && instituteId != null) {
                            val isExpired = checkSubscriptionExpired(instituteId, appDb)
                            if (isExpired) {
                                navController.navigate(SubscriptionExpiredRoute) {
                                    popUpTo(navController.graph.id) { inclusive = true }
                                }
                                return@launch
                            }
                        }
                        navController.navigate(DashboardRoute) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    }
                },
                onNavigateSuperAdmin = {
                    navController.navigate(SuperAdminRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
                onNavigatePrivacyPolicy = {
                    navController.navigate(PrivacyPolicyRoute)
                },
                onNavigateTermsConditions = {
                    navController.navigate(TermsConditionsRoute)
                }
            )
        }

        composable<PrivacyPolicyRoute> {
            PrivacyPolicyScreen(onBack = { navController.popBackStack() })
        }

        composable<TermsConditionsRoute> {
            TermsConditionsScreen(onBack = { navController.popBackStack() })
        }
        
        composable<DashboardRoute> {
            var currentTab by remember { mutableStateOf("DashboardRoute") }
            com.batchfee.edu.ui.dashboard.DashboardTabsScreen(
                db = appDb,
                currentRoute = currentTab,
                onNavigate = navigate@ { route ->
                    if (route == "DashboardRoute" || route == "More") {
                        currentTab = route
                    } else {
                        if (!AccessControl.canAccessRoute(route)) return@navigate
                        when (route) {
                            "StudentsRoute" -> navController.navigate(StudentsRoute)
                            "AddStudentRoute" -> navController.navigate(AddStudentRoute)
                            "BatchesRoute" -> navController.navigate(BatchesRoute)
                            "AddBatchRoute" -> navController.navigate(AddBatchRoute)
                            "FeeDashboardRoute" -> navController.navigate(FeeDashboardRoute)
                            "DueFeesRoute" -> navController.navigate(DueFeesRoute)
                            "CreateFeeRoute" -> navController.navigate(CreateFeeRoute)
                            "UnifiedCollectRoute" -> navController.navigate(UnifiedCollectRoute)
                            "AttendanceRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.AttendanceRoute)
                            "AttendanceReportRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.AttendanceReportRoute)
                            "ReportsRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.ReportsRoute)
                            "ReportsRoute?period=today" -> navController.navigate(com.batchfee.edu.ui.navigation.ReportsRoute(period = "today"))
                            "ReportsRoute?period=month" -> navController.navigate(com.batchfee.edu.ui.navigation.ReportsRoute(period = "month"))
                            "ReportsRoute?period=lifetime" -> navController.navigate(com.batchfee.edu.ui.navigation.ReportsRoute(period = "lifetime"))
                            "ReminderTemplatesRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.ReminderTemplatesRoute)
                            "StaffRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.StaffRoute)
                            "AddStaffRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.AddStaffRoute)
                            "StaffAttendanceRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.StaffAttendanceRoute)
                            "SalaryRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.SalaryRoute)
                            "ExpensesRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.ExpensesRoute)
                            "AddExpenseRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.AddExpenseRoute)
                            "ProfitLossRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.ProfitLossRoute)
                            "ExamsRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.ExamsRoute)
                            "CreateExamRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.CreateExamRoute)
                            "IdCardGeneratorRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.IdCardGeneratorRoute)
                            "BirthdayReminderRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.BirthdayReminderRoute)
                            "SettingsRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.SettingsRoute)
                            "EnquiryListRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.EnquiryListRoute)
                            else -> {
                                if (route.startsWith("TakeAttendanceRoute:")) {
                                    route.substringAfter(":").takeIf { it.isNotBlank() }?.let { batchId ->
                                        navController.navigate(TakeAttendanceRoute(batchId))
                                    }
                                }
                            }
                        }
                    }
                },
                onNavigatePricing = { navController.navigate(PricingRoute) },
                onNavigateBilling = { navController.navigate(BillingRoute) },
                onLogout = {
                    SessionManager.logout()
                    navController.navigate(AuthRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }
            )
        }
        
        composable<StudentsRoute> {
            com.batchfee.edu.ui.students.StudentListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddStudent = { navController.navigate(AddStudentRoute) },
                onNavigateToProfile = { studentId -> navController.navigate(StudentProfileRoute(studentId)) },
                onNavigateToIdCards = { navController.navigate(IdCardGeneratorRoute) }
            )
        }
        
        composable<AddStudentRoute> {
            com.batchfee.edu.ui.students.AddEditStudentScreen(db = appDb, onBack = { navController.popBackStack() })
        }

        composable<EditStudentRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EditStudentRoute>()
            com.batchfee.edu.ui.students.AddEditStudentScreen(
                db = appDb,
                studentId = route.studentId,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<StudentProfileRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<StudentProfileRoute>()
            com.batchfee.edu.ui.students.StudentProfileScreen(
                db = appDb,
                studentId = route.studentId,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(EditStudentRoute(route.studentId)) },
                onGenerateIdCard = { navController.navigate(IdCardPreviewRoute("student", route.studentId)) }
            )
        }
        
        composable<BatchesRoute> {
            com.batchfee.edu.ui.batches.BatchListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddBatch = { navController.navigate(AddBatchRoute) },
                onNavigateToBatch = { batchId -> navController.navigate(BatchDetailRoute(batchId)) }
            )
        }
        
        composable<AddBatchRoute> {
            com.batchfee.edu.ui.batches.AddEditBatchScreen(db = appDb, onBack = { navController.popBackStack() })
        }

        composable<EditBatchRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EditBatchRoute>()
            com.batchfee.edu.ui.batches.AddEditBatchScreen(
                db = appDb,
                batchId = route.batchId,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<BatchDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<BatchDetailRoute>()
            com.batchfee.edu.ui.batches.BatchDetailScreen(
                db = appDb,
                batchId = route.batchId,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(EditBatchRoute(route.batchId)) },
                onEnroll = { navController.navigate(EnrollStudentsRoute(route.batchId)) }
            )
        }
        
        composable<EnrollStudentsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EnrollStudentsRoute>()
            com.batchfee.edu.ui.batches.EnrollStudentsScreen(
                db = appDb,
                batchId = route.batchId,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<FeeDashboardRoute> {
            com.batchfee.edu.ui.fees.FeeDashboardScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onNavigateDueFees = { navController.navigate(UnifiedCollectRoute) },
                onCreateFee = { navController.navigate(CreateFeeRoute) },
                onCollectPayment = { feeId -> navController.navigate(CollectPaymentRoute(feeId)) }
            )
        }
        
        composable<CreateFeeRoute> {
            com.batchfee.edu.ui.fees.CreateFeeScreen(db = appDb, onBack = { navController.popBackStack() })
        }
        
        composable<DueFeesRoute> {
            com.batchfee.edu.ui.fees.DueFeeListScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<CollectPaymentRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<CollectPaymentRoute>()
            com.batchfee.edu.ui.fees.CollectPaymentScreen(
                db = appDb,
                feeId = route.feeId,
                onBack = { navController.popBackStack() },
                onNavigateReceipt = { paymentId -> 
                    navController.popBackStack()
                    navController.navigate(ReceiptDetailRoute(paymentId))
                }
            )
        }
        
        composable<ReceiptDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ReceiptDetailRoute>()
            com.batchfee.edu.ui.fees.ReceiptDetailScreen(db = appDb, paymentId = route.paymentId, onBack = { navController.popBackStack() })
        }
        
        composable<UnifiedCollectRoute> {
            com.batchfee.edu.ui.fees.UnifiedCollectScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onCollectPayment = { feeId -> navController.navigate(CollectPaymentRoute(feeId)) }
            )
        }
        
        composable<ReportsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ReportsRoute>()
            com.batchfee.edu.ui.reports.ReportsScreen(db = appDb, period = route.period, onBack = { navController.popBackStack() })
        }
        
        composable<ReminderTemplatesRoute> {
            com.batchfee.edu.ui.reminders.ReminderTemplatesScreen(db = appDb, onBack = { navController.popBackStack() })
        }
        
        composable<AttendanceRoute> {
            com.batchfee.edu.ui.attendance.AttendanceBatchSelectScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onSelectBatch = { batchId -> navController.navigate(TakeAttendanceRoute(batchId)) }
            )
        }
        
        composable<TakeAttendanceRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TakeAttendanceRoute>()
            com.batchfee.edu.ui.attendance.TakeAttendanceScreen(db = appDb, batchId = route.batchId, onBack = { navController.popBackStack() })
        }
        
        composable<AttendanceReportRoute> {
            com.batchfee.edu.ui.attendance.AttendanceReportScreen(db = appDb, onBack = { navController.popBackStack() })
        }
        
        composable<StaffRoute> {
            com.batchfee.edu.ui.staff.StaffListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddStaff = { navController.navigate(AddStaffRoute) },
                onNavigateToProfile = { id -> navController.navigate(StaffProfileRoute(id)) },
                onNavigateToPricing = { navController.navigate(PricingRoute) }
            )
        }
        
        composable<AddStaffRoute> {
            val staffId = runCatching {
                navController.previousBackStackEntry
                    ?.toRoute<StaffProfileRoute>()
                    ?.staffId
            }.getOrNull()
            com.batchfee.edu.ui.staff.AddEditStaffScreen(
                db = appDb,
                staffId = staffId,
                onBack = { navController.popBackStack() }
            )
        }

        composable<EditStaffRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EditStaffRoute>()
            com.batchfee.edu.ui.staff.AddEditStaffScreen(
                db = appDb,
                staffId = route.staffId,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<StaffProfileRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<StaffProfileRoute>()
            com.batchfee.edu.ui.staff.StaffProfileScreen(
                db = appDb,
                staffId = route.staffId,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(EditStaffRoute(route.staffId)) }
            )
        }

        composable<StaffAttendanceRoute> {
            com.batchfee.edu.ui.staff.StaffAttendanceScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }

        composable<StaffAttendanceReportRoute> {
            com.batchfee.edu.ui.staff.StaffAttendanceScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<SalaryRoute> {
            com.batchfee.edu.ui.staff.SalaryDashboardScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onGenerate = { navController.navigate(GenerateSalaryRoute) },
                onNavigateToPricing = { navController.navigate(PricingRoute) }
            )
        }
        
        composable<GenerateSalaryRoute> {
            com.batchfee.edu.ui.staff.GenerateSalaryScreen(db = appDb, onBack = { navController.popBackStack() })
        }
        
        composable<ExpensesRoute> {
            com.batchfee.edu.ui.expenses.ExpenseListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddExpense = { navController.navigate(AddExpenseRoute) },
                onNavigateToPricing = { navController.navigate(PricingRoute) }
            )
        }
        
        composable<AddExpenseRoute> {
            com.batchfee.edu.ui.expenses.AddEditExpenseScreen(db = appDb, onBack = { navController.popBackStack() })
        }
        
        composable<ProfitLossRoute> {
            com.batchfee.edu.ui.reports.ProfitLossScreen(db = appDb, onBack = { navController.popBackStack() }, onNavigateToPricing = { navController.navigate(PricingRoute) })
        }
        
        composable<ExamsRoute> {
            com.batchfee.edu.ui.exams.ExamListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddExam = { navController.navigate(CreateExamRoute) },
                onNavigateToDetail = { examId -> navController.navigate(ExamDetailRoute(examId)) },
                onNavigateToPricing = { navController.navigate(PricingRoute) }
            )
        }
        
        composable<CreateExamRoute> {
            com.batchfee.edu.ui.exams.AddEditExamScreen(db = appDb, onBack = { navController.popBackStack() })
        }

        composable<EditExamRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EditExamRoute>()
            com.batchfee.edu.ui.exams.AddEditExamScreen(
                db = appDb,
                examId = route.examId,
                onBack = { navController.popBackStack() }
            )
        }

        composable<ExamDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ExamDetailRoute>()
            com.batchfee.edu.ui.exams.ExamDetailScreen(
                db = appDb,
                examId = route.examId,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(EditExamRoute(route.examId)) }
            )
        }
        
        composable<IdCardGeneratorRoute> {
            com.batchfee.edu.ui.students.IdCardGeneratorScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onNavigateToPreview = { type, id -> navController.navigate(IdCardPreviewRoute(type, id)) },
                onNavigateToPricing = { navController.navigate(PricingRoute) }
            )
        }
        
        composable<IdCardPreviewRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<IdCardPreviewRoute>()
            com.batchfee.edu.ui.students.IdCardPreviewScreen(db = appDb, type = route.type, studentId = route.id, onBack = { navController.popBackStack() })
        }
        
        composable<BirthdayReminderRoute> {
            com.batchfee.edu.ui.students.BirthdayReminderScreen(db = appDb, onBack = { navController.popBackStack() }, onNavigateToPricing = { navController.navigate(PricingRoute) })
        }
        
        composable<EnquiryListRoute> {
            com.batchfee.edu.ui.enquiries.EnquiryListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddEnquiry = { /* handled by Dashboard dialog */ }
            )
        }

        composable<BackupRestoreRoute> {
            com.batchfee.edu.ui.dashboard.BackupRestoreScreen(onBack = { navController.popBackStack() }, onNavigateToPricing = { navController.navigate(PricingRoute) })
        }
        
        composable<SettingsRoute> {
            com.batchfee.edu.ui.dashboard.SettingsScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onNavigate = { routeStr ->
                    when(routeStr) {
                        "BillingRoute" -> navController.navigate(BillingRoute)
                        "ReminderTemplatesRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.ReminderTemplatesRoute)
                        "BackupRestoreRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.BackupRestoreRoute)
                        "StudentRegistrationRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.StudentRegistrationRoute)
                    }
                }
            )
        }
        
        composable<StudentRegistrationRoute> {
            com.batchfee.edu.ui.registrations.RegistrationListScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<PricingRoute> {
            PricingScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onSubscribe = { planId ->
                    navController.popBackStack()
                }
            )
        }
        
        composable<BillingRoute> {
            BillingScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onUpgrade = { navController.navigate(PricingRoute) }
            )
        }
        
        composable<SuperAdminRoute> {
            SuperAdminScreen(
                db = appDb,
                onLogout = {
                    SessionManager.logout()
                    navController.navigate(AuthRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }
            )
        }

        composable<SubscriptionExpiredRoute> {
            SubscriptionExpiredScreen(
                db = appDb,
                onRenew = {
                    navController.navigate(PricingRoute)
                },
                onLogout = {
                    navController.navigate(AuthRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }
            )
        }
    }
}

private suspend fun checkSubscriptionExpired(instituteId: String, db: com.batchfee.edu.data.database.AppDatabase): Boolean {
    return try {
        val doc = FirebaseFirestore.getInstance()
            .collection("institutes").document(instituteId)
            .get().await()
        val isActive = doc.getBoolean("isActive") ?: true
        if (!isActive) return true
        val now = System.currentTimeMillis()
        // Prefer currentPeriodEndMs (paid plan) over trialEndDate (trial plan).
        // A paid subscriber may have a stale trialEndDate in the past — ignore it
        // when a valid currentPeriodEndMs exists.
        val periodEnd = doc.getLong("currentPeriodEndMs")
        if (periodEnd != null && periodEnd > 0L) return now > periodEnd
        val trialEnd = doc.getLong("trialEndDate") ?: return false
        now > trialEnd
    } catch (e: Exception) {
        FirebaseCrashlytics.getInstance().recordException(e)
        // Offline fallback: check Room DB instead of allowing access
        val local = withContext(Dispatchers.IO) { db.instituteDao().getInstitute(instituteId) }
        if (local != null) {
            val now = System.currentTimeMillis()
            // Check status from local cache
            if (local.subscriptionStatus == "blocked") return true
            val endMs = local.currentPeriodEndMs.takeIf { it > 0L } ?: local.trialEndDateMs
            return now > endMs
        }
        // No data available at all: safest is to deny access
        true
    }
}

