package com.batchfee.student

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.batchfee.student.data.firebase.StudentFirestoreRepository
import com.batchfee.student.domain.SessionManager
import com.batchfee.student.ui.dashboard.DashboardTabsScreen
import com.batchfee.student.ui.fees.FeeDetailScreen
import com.batchfee.student.ui.fees.ReceiptViewScreen
import com.batchfee.student.ui.fees.FeesScreen
import com.batchfee.student.ui.attendance.AttendanceScreen
import com.batchfee.student.ui.exams.ExamsScreen
import com.batchfee.student.ui.exams.ExamDetailScreen
import com.batchfee.student.ui.exams.MeritListScreen
import com.batchfee.student.ui.homework.HomeworkScreen
import com.batchfee.student.ui.homework.HomeworkDetailScreen
import com.batchfee.student.ui.notice.NoticeScreen
import com.batchfee.student.ui.profile.ProfileScreen
import com.batchfee.student.ui.navigation.*
import com.batchfee.student.ui.theme.BatchFeeStudentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Demo mode — always on, no auth needed
        SessionManager.loginDemo()
        StudentFirestoreRepository.forceDemoMode()

        setContent {
            BatchFeeStudentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppContent()
                }
            }
        }
    }
}

@Composable
private fun MainAppContent() {
    val navController = rememberNavController()
    val context = LocalContext.current

    NavHost(navController = navController, startDestination = DashboardRoute) {
        composable<DashboardRoute> {
            DashboardTabsScreen(
                onNavigate = { route ->
                    when (route) {
                        "ProfileRoute" -> navController.navigate(ProfileRoute)
                        "FeesRoute" -> navController.navigate(FeesRoute)
                        "AttendanceRoute" -> navController.navigate(AttendanceRoute)
                        "ExamsRoute" -> navController.navigate(ExamsRoute)
                        "HomeworkRoute" -> navController.navigate(HomeworkRoute)
                        "NoticeRoute" -> navController.navigate(NoticeRoute)
                    }
                },
                onLogout = { (context as? android.app.Activity)?.finish() }
            )
        }

        composable<ProfileRoute> {
            ProfileScreen(onBack = { navController.popBackStack() })
        }

        composable<FeesRoute> {
            FeesScreen(
                onBack = { navController.popBackStack() },
                onFeeDetail = { feeId -> navController.navigate(FeeDetailRoute(feeId)) },
                onReceiptView = { receiptId -> navController.navigate(ReceiptViewRoute(receiptId)) }
            )
        }

        composable<FeeDetailRoute> { backStackEntry ->
            FeeDetailScreen(
                feeId = backStackEntry.toRoute<FeeDetailRoute>().feeId,
                onBack = { navController.popBackStack() }
            )
        }

        composable<ReceiptViewRoute> { backStackEntry ->
            ReceiptViewScreen(
                receiptId = backStackEntry.toRoute<ReceiptViewRoute>().receiptId,
                onBack = { navController.popBackStack() }
            )
        }

        composable<AttendanceRoute> {
            AttendanceScreen(onBack = { navController.popBackStack() })
        }

        composable<ExamsRoute> {
            ExamsScreen(
                onBack = { navController.popBackStack() },
                onExamDetail = { examId -> navController.navigate(ExamDetailRoute(examId)) }
            )
        }

        composable<ExamDetailRoute> { backStackEntry ->
            ExamDetailScreen(
                examId = backStackEntry.toRoute<ExamDetailRoute>().examId,
                onBack = { navController.popBackStack() },
                onMeritList = { examId -> navController.navigate(MeritListRoute(examId)) }
            )
        }

        composable<MeritListRoute> { backStackEntry ->
            MeritListScreen(
                examId = backStackEntry.toRoute<MeritListRoute>().examId,
                onBack = { navController.popBackStack() }
            )
        }

        composable<HomeworkRoute> {
            HomeworkScreen(
                onBack = { navController.popBackStack() },
                onHomeworkDetail = { hwId -> navController.navigate(HomeworkDetailRoute(hwId)) }
            )
        }

        composable<HomeworkDetailRoute> { backStackEntry ->
            HomeworkDetailScreen(
                homeworkId = backStackEntry.toRoute<HomeworkDetailRoute>().homeworkId,
                onBack = { navController.popBackStack() }
            )
        }

        composable<NoticeRoute> {
            NoticeScreen(onBack = { navController.popBackStack() })
        }
    }
}
