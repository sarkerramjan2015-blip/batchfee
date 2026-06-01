package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.auth.AuthScreen
import com.example.ui.billing.BillingScreen
import com.example.ui.dashboard.DashboardScreen
import com.example.ui.navigation.*
import com.example.ui.pricing.PricingScreen
import com.example.ui.superadmin.SuperAdminScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appDb = (application as BatchFeeApp).database
        
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = AuthRoute) {
                        composable<AuthRoute> {
                            AuthScreen(
                                db = appDb,
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
                                onNavigate = { route ->
                                    if (route == "DashboardRoute" || route == "More") {
                                        currentTab = route
                                    } else {
                                        when (route) {
                                            "StudentsRoute" -> navController.navigate(StudentsRoute)
                                            "AddStudentRoute" -> navController.navigate(AddStudentRoute)
                                            "BatchesRoute" -> navController.navigate(BatchesRoute)
                                            "AddBatchRoute" -> navController.navigate(AddBatchRoute)
                                            "FeeDashboardRoute" -> navController.navigate(FeeDashboardRoute)
                                            "DueFeesRoute" -> navController.navigate(DueFeesRoute)
                                            "CreateFeeRoute" -> navController.navigate(CreateFeeRoute)
                                            "AttendanceRoute" -> navController.navigate(com.example.ui.navigation.AttendanceRoute)
                                            "AttendanceReportRoute" -> navController.navigate(com.example.ui.navigation.AttendanceReportRoute)
                                            "ReportsRoute" -> navController.navigate(com.example.ui.navigation.ReportsRoute)
                                            "ReminderTemplatesRoute" -> navController.navigate(com.example.ui.navigation.ReminderTemplatesRoute)
                                            "StaffRoute" -> navController.navigate(com.example.ui.navigation.StaffRoute)
                                            "SalaryRoute" -> navController.navigate(com.example.ui.navigation.SalaryRoute)
                                            "ExpensesRoute" -> navController.navigate(com.example.ui.navigation.ExpensesRoute)
                                            "ProfitLossRoute" -> navController.navigate(com.example.ui.navigation.ProfitLossRoute)
                                            "ExamsRoute" -> navController.navigate(com.example.ui.navigation.ExamsRoute)
                                            "IdCardGeneratorRoute" -> navController.navigate(com.example.ui.navigation.IdCardGeneratorRoute)
                                            "BirthdayReminderRoute" -> navController.navigate(com.example.ui.navigation.BirthdayReminderRoute)
                                            "SettingsRoute" -> navController.navigate(com.example.ui.navigation.SettingsRoute)
                                        }
                                    }
                                },
                                onNavigatePricing = { navController.navigate(PricingRoute) },
                                onNavigateBilling = { navController.navigate(BillingRoute) },
                                onLogout = {
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
                                onNavigateToProfile = { studentId -> navController.navigate(StudentProfileRoute(studentId)) }
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
                                onEdit = { navController.navigate(EditStudentRoute(route.studentId)) }
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
                        
                        composable<BatchDetailRoute> { backStackEntry ->
                            val route = backStackEntry.toRoute<BatchDetailRoute>()
                            com.example.ui.batches.BatchDetailScreen(
                                db = appDb,
                                batchId = route.batchId,
                                onBack = { navController.popBackStack() },
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
                                onNavigateDueFees = { navController.navigate(DueFeesRoute) },
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
                                onBack = { navController.popBackStack() },
                                onCollectPayment = { feeId -> navController.navigate(CollectPaymentRoute(feeId)) }
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
                            com.example.ui.staff.AddEditStaffScreen(db = appDb, onBack = { navController.popBackStack() })
                        }
                        
                        composable<StaffProfileRoute> { backStackEntry ->
                            val route = backStackEntry.toRoute<StaffProfileRoute>()
                            com.example.ui.staff.StaffProfileScreen(
                                db = appDb,
                                staffId = route.staffId,
                                onBack = { navController.popBackStack() },
                                onEdit = { navController.navigate(AddStaffRoute) } // Navigate to edit — TODO: pass staffId for edit mode
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
                                onNavigateToPricing = { navController.navigate(PricingRoute) }
                            )
                        }
                        
                        composable<CreateExamRoute> {
                            com.example.ui.exams.CreateExamScreen(db = appDb, onBack = { navController.popBackStack() })
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
                                    // Subscription logic placeholder
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
                                    navController.navigate(AuthRoute) {
                                        popUpTo(navController.graph.id) { inclusive = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
