package com.example

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
import com.example.domain.AccessControl
import com.example.domain.ForceUpdateChecker
import com.example.domain.PasswordHasher
import com.example.domain.SessionManager
import com.example.domain.ThemePreferences
import com.example.ui.auth.AuthScreen
import com.example.ui.billing.BillingScreen
import com.example.ui.dashboard.DashboardScreen
import com.example.ui.navigation.*
import com.example.ui.pricing.PricingScreen
import com.example.ui.superadmin.SuperAdminScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.update.ForceUpdateScreen
import kotlinx.coroutines.delay

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
private fun MainAppContent(appDb: com.example.data.database.AppDatabase) {
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
            AuthScreen(
                db = appDb,
                sessionNotice = sessionNotice,
                onNavigateDashboard = { 
                    navController.navigate(DashboardRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    } 
                },
                onNavigateSuperAdmin = { 
                    navController.navigate(SuperAdminRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    } 
                }
            )
        }
        
        composable<DashboardRoute> {
            var currentTab by remember { mutableStateOf("DashboardRoute") }
            com.example.ui.dashboard.DashboardTabsScreen(
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
                            "AttendanceRoute" -> navController.navigate(com.example.ui.navigation.AttendanceRoute)
                            "AttendanceReportRoute" -> navController.navigate(com.example.ui.navigation.AttendanceReportRoute)
                            "ReportsRoute" -> navController.navigate(com.example.ui.navigation.ReportsRoute)
                            "ReminderTemplatesRoute" -> navController.navigate(com.example.ui.navigation.ReminderTemplatesRoute)
                            "StaffRoute" -> navController.navigate(com.example.ui.navigation.StaffRoute)
                            "AddStaffRoute" -> navController.navigate(com.example.ui.navigation.AddStaffRoute)
                            "StaffAttendanceRoute" -> navController.navigate(com.example.ui.navigation.StaffAttendanceRoute)
                            "SalaryRoute" -> navController.navigate(com.example.ui.navigation.SalaryRoute)
                            "ExpensesRoute" -> navController.navigate(com.example.ui.navigation.ExpensesRoute)
                            "AddExpenseRoute" -> navController.navigate(com.example.ui.navigation.AddExpenseRoute)
                            "ProfitLossRoute" -> navController.navigate(com.example.ui.navigation.ProfitLossRoute)
                            "ExamsRoute" -> navController.navigate(com.example.ui.navigation.ExamsRoute)
                            "CreateExamRoute" -> navController.navigate(com.example.ui.navigation.CreateExamRoute)
                            "IdCardGeneratorRoute" -> navController.navigate(com.example.ui.navigation.IdCardGeneratorRoute)
                            "BirthdayReminderRoute" -> navController.navigate(com.example.ui.navigation.BirthdayReminderRoute)
                            "SettingsRoute" -> navController.navigate(com.example.ui.navigation.SettingsRoute)
                            "EnquiryListRoute" -> navController.navigate(com.example.ui.navigation.EnquiryListRoute)
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
            com.example.ui.students.StudentListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddStudent = { navController.navigate(AddStudentRoute) },
                onNavigateToProfile = { studentId -> navController.navigate(StudentProfileRoute(studentId)) },
                onNavigateToIdCards = { navController.navigate(IdCardGeneratorRoute) }
            )
        }
        
        composable<AddStudentRoute> {
            com.example.ui.students.AddEditStudentScreen(db = appDb, onBack = { navController.popBackStack() })
        }

        composable<EditStudentRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EditStudentRoute>()
            com.example.ui.students.AddEditStudentScreen(
                db = appDb,
                studentId = route.studentId,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<StudentProfileRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<StudentProfileRoute>()
            com.example.ui.students.StudentProfileScreen(
                db = appDb,
                studentId = route.studentId,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(EditStudentRoute(route.studentId)) },
                onGenerateIdCard = { navController.navigate(IdCardPreviewRoute("student", route.studentId)) }
            )
        }
        
        composable<BatchesRoute> {
            com.example.ui.batches.BatchListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddBatch = { navController.navigate(AddBatchRoute) },
                onNavigateToBatch = { batchId -> navController.navigate(BatchDetailRoute(batchId)) }
            )
        }
        
        composable<AddBatchRoute> {
            com.example.ui.batches.AddEditBatchScreen(db = appDb, onBack = { navController.popBackStack() })
        }

        composable<EditBatchRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EditBatchRoute>()
            com.example.ui.batches.AddEditBatchScreen(
                db = appDb,
                batchId = route.batchId,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<BatchDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<BatchDetailRoute>()
            com.example.ui.batches.BatchDetailScreen(
                db = appDb,
                batchId = route.batchId,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(EditBatchRoute(route.batchId)) },
                onEnroll = { navController.navigate(EnrollStudentsRoute(route.batchId)) }
            )
        }
        
        composable<EnrollStudentsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EnrollStudentsRoute>()
            com.example.ui.batches.EnrollStudentsScreen(
                db = appDb,
                batchId = route.batchId,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<FeeDashboardRoute> {
            com.example.ui.fees.FeeDashboardScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onNavigateDueFees = { navController.navigate(UnifiedCollectRoute) },
                onCreateFee = { navController.navigate(CreateFeeRoute) },
                onCollectPayment = { feeId -> navController.navigate(CollectPaymentRoute(feeId)) }
            )
        }
        
        composable<CreateFeeRoute> {
            com.example.ui.fees.CreateFeeScreen(db = appDb, onBack = { navController.popBackStack() })
        }
        
        composable<DueFeesRoute> {
            com.example.ui.fees.DueFeeListScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<CollectPaymentRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<CollectPaymentRoute>()
            com.example.ui.fees.CollectPaymentScreen(
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
            com.example.ui.fees.ReceiptDetailScreen(db = appDb, paymentId = route.paymentId, onBack = { navController.popBackStack() })
        }
        
        composable<UnifiedCollectRoute> {
            com.example.ui.fees.UnifiedCollectScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onCollectPayment = { feeId -> navController.navigate(CollectPaymentRoute(feeId)) }
            )
        }
        
        composable<ReportsRoute> {
            com.example.ui.reports.ReportsScreen(db = appDb, onBack = { navController.popBackStack() })
        }
        
        composable<ReminderTemplatesRoute> {
            com.example.ui.reminders.ReminderTemplatesScreen(db = appDb, onBack = { navController.popBackStack() })
        }
        
        composable<AttendanceRoute> {
            com.example.ui.attendance.AttendanceBatchSelectScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onSelectBatch = { batchId -> navController.navigate(TakeAttendanceRoute(batchId)) }
            )
        }
        
        composable<TakeAttendanceRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TakeAttendanceRoute>()
            com.example.ui.attendance.TakeAttendanceScreen(db = appDb, batchId = route.batchId, onBack = { navController.popBackStack() })
        }
        
        composable<AttendanceReportRoute> {
            com.example.ui.attendance.AttendanceReportScreen(db = appDb, onBack = { navController.popBackStack() })
        }
        
        composable<StaffRoute> {
            com.example.ui.staff.StaffListScreen(
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
            com.example.ui.staff.AddEditStaffScreen(
                db = appDb,
                staffId = staffId,
                onBack = { navController.popBackStack() }
            )
        }

        composable<EditStaffRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EditStaffRoute>()
            com.example.ui.staff.AddEditStaffScreen(
                db = appDb,
                staffId = route.staffId,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<StaffProfileRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<StaffProfileRoute>()
            com.example.ui.staff.StaffProfileScreen(
                db = appDb,
                staffId = route.staffId,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(EditStaffRoute(route.staffId)) }
            )
        }

        composable<StaffAttendanceRoute> {
            com.example.ui.staff.StaffAttendanceScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }

        composable<StaffAttendanceReportRoute> {
            com.example.ui.staff.StaffAttendanceScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<SalaryRoute> {
            com.example.ui.staff.SalaryDashboardScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onGenerate = { navController.navigate(GenerateSalaryRoute) },
                onNavigateToPricing = { navController.navigate(PricingRoute) }
            )
        }
        
        composable<GenerateSalaryRoute> {
            com.example.ui.staff.GenerateSalaryScreen(db = appDb, onBack = { navController.popBackStack() })
        }
        
        composable<ExpensesRoute> {
            com.example.ui.expenses.ExpenseListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddExpense = { navController.navigate(AddExpenseRoute) },
                onNavigateToPricing = { navController.navigate(PricingRoute) }
            )
        }
        
        composable<AddExpenseRoute> {
            com.example.ui.expenses.AddEditExpenseScreen(db = appDb, onBack = { navController.popBackStack() })
        }
        
        composable<ProfitLossRoute> {
            com.example.ui.reports.ProfitLossScreen(db = appDb, onBack = { navController.popBackStack() }, onNavigateToPricing = { navController.navigate(PricingRoute) })
        }
        
        composable<ExamsRoute> {
            com.example.ui.exams.ExamListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddExam = { navController.navigate(CreateExamRoute) },
                onNavigateToDetail = { examId -> navController.navigate(ExamDetailRoute(examId)) },
                onNavigateToPricing = { navController.navigate(PricingRoute) }
            )
        }
        
        composable<CreateExamRoute> {
            com.example.ui.exams.CreateExamScreen(db = appDb, onBack = { navController.popBackStack() })
        }

        composable<ExamDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ExamDetailRoute>()
            com.example.ui.exams.ExamDetailScreen(
                db = appDb,
                examId = route.examId,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<IdCardGeneratorRoute> {
            com.example.ui.students.IdCardGeneratorScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onNavigateToPreview = { type, id -> navController.navigate(IdCardPreviewRoute(type, id)) },
                onNavigateToPricing = { navController.navigate(PricingRoute) }
            )
        }
        
        composable<IdCardPreviewRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<IdCardPreviewRoute>()
            com.example.ui.students.IdCardPreviewScreen(db = appDb, type = route.type, id = route.id, onBack = { navController.popBackStack() })
        }
        
        composable<BirthdayReminderRoute> {
            com.example.ui.students.BirthdayReminderScreen(db = appDb, onBack = { navController.popBackStack() }, onNavigateToPricing = { navController.navigate(PricingRoute) })
        }
        
        composable<EnquiryListRoute> {
            com.example.ui.enquiries.EnquiryListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddEnquiry = { /* handled by Dashboard dialog */ }
            )
        }

        composable<BackupRestoreRoute> {
            com.example.ui.dashboard.BackupRestoreScreen(onBack = { navController.popBackStack() }, onNavigateToPricing = { navController.navigate(PricingRoute) })
        }
        
        composable<SettingsRoute> {
            com.example.ui.dashboard.SettingsScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onNavigate = { routeStr ->
                    when(routeStr) {
                        "BillingRoute" -> navController.navigate(BillingRoute)
                        "ReminderTemplatesRoute" -> navController.navigate(com.example.ui.navigation.ReminderTemplatesRoute)
                        "BackupRestoreRoute" -> navController.navigate(com.example.ui.navigation.BackupRestoreRoute)
                    }
                }
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
    }
}
