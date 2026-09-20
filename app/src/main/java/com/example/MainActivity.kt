package com.batchfee.edu

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.batchfee.edu.domain.AccessControl
import com.batchfee.edu.domain.ForceUpdateChecker
import com.batchfee.edu.domain.PasswordHasher
import com.batchfee.edu.domain.ReviewFlowLauncher
import com.batchfee.edu.domain.ReviewPromptPreferences
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.StudentSessionManager
import com.batchfee.edu.domain.ThemePreferences
import com.batchfee.edu.notifications.NoticePushRegistration
import com.batchfee.edu.data.firestore.InstituteRealtimeSyncManager
import com.batchfee.edu.data.firebase.FirebaseFailureReporter
import com.batchfee.edu.data.repository.NoticeCenterRepository
import com.batchfee.edu.ui.auth.AuthScreen
import com.batchfee.edu.ui.billing.BillingScreen
import com.batchfee.edu.ui.dashboard.DashboardScreen
import com.batchfee.edu.ui.dashboard.AdminNoticeCenterScreen
import com.batchfee.edu.ui.dashboard.ProductFeedbackScreen
import com.batchfee.edu.ui.dashboard.TutorialGuideScreen
import com.batchfee.edu.ui.legal.PrivacyPolicyScreen
import com.batchfee.edu.ui.legal.TermsConditionsScreen
import com.batchfee.edu.ui.navigation.*
import com.batchfee.edu.ui.pricing.PricingScreen
import com.batchfee.edu.ui.review.InAppReviewDialog
import com.batchfee.edu.ui.review.ReviewThanksDialog
import com.batchfee.edu.ui.superadmin.SuperAdminScreen
import com.batchfee.edu.ui.superadmin.QuestionCurationScreen
import com.batchfee.edu.ui.superadmin.QuestionBankAdminScreen
import com.batchfee.edu.ui.subscription.SubscriptionExpiredScreen
import com.batchfee.edu.ui.theme.MyApplicationTheme
import com.batchfee.edu.ui.update.ForceUpdateScreen
import com.batchfee.edu.ui.studentapp.StudentMainScaffold
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private lateinit var appUpdateManager: AppUpdateManager
    private var isImmediateUpdateCheckInFlight = false
    private var isImmediateUpdateFlowRunning = false

    private val immediateUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        isImmediateUpdateFlowRunning = false
        if (result.resultCode != Activity.RESULT_OK) {
            // Cancellation and Play availability failures are normal outcomes, especially
            // for sideloaded builds. Keep them out of Crashlytics and try again on a later resume.
            Log.w(TAG, "Immediate update flow ended with result code ${result.resultCode}")
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionManager.markActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appUpdateManager = AppUpdateManagerFactory.create(this)
        checkForImmediateUpdate()
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

    override fun onResume() {
        super.onResume()
        if (::appUpdateManager.isInitialized) checkForImmediateUpdate()
    }

    private fun checkForImmediateUpdate() {
        if (!::appUpdateManager.isInitialized || isImmediateUpdateCheckInFlight) return

        isImmediateUpdateCheckInFlight = true
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                isImmediateUpdateCheckInFlight = false
                if (isImmediateUpdateFlowRunning) return@addOnSuccessListener

                val availability = appUpdateInfo.updateAvailability()
                val canStartNewUpdate =
                    availability == UpdateAvailability.UPDATE_AVAILABLE &&
                        appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                val shouldResumeUpdate =
                    availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS

                if (!canStartNewUpdate && !shouldResumeUpdate) return@addOnSuccessListener

                isImmediateUpdateFlowRunning = true
                val started = runCatching {
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        immediateUpdateLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Could not start immediate update flow", error)
                }.getOrDefault(false)

                if (!started) isImmediateUpdateFlowRunning = false
            }
            .addOnFailureListener { error ->
                isImmediateUpdateCheckInFlight = false
                // The API can fail for a sideloaded APK or a device without Google Play.
                // That must not block startup or inflate production crash reports.
                Log.d(TAG, "Google Play update check unavailable", error)
            }
    }

    private companion object {
        const val TAG = "BatchFeeAppUpdate"
    }
}

@Composable
private fun NotificationPermissionEducationDialog(
    onEnable: () -> Unit,
    onNotNow: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onNotNow,
        containerColor = Color(0xFF101B31),
        shape = RoundedCornerShape(28.dp),
        icon = {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF2563EB), Color(0xFF22D3EE)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
        title = {
            Text(
                text = "Stay updated with BatchFee",
                color = Color(0xFFF8FAFC),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 21.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enable alerts so important institute notices are never missed.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF172641))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("You will receive", color = Color(0xFF67E8F9), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("• Super Admin notices\n• Account and subscription updates\n• Important service alerts", color = Color(0xFFCBD5E1), fontSize = 12.sp, lineHeight = 18.sp)
                }
                Text(
                    text = "You can change this anytime from phone settings.",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onEnable,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF22C7E8),
                    contentColor = Color(0xFF06131F),
                ),
            ) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Enable notifications", fontWeight = FontWeight.ExtraBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
                Text("Not now", color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun MainAppContent(appDb: com.batchfee.edu.data.database.AppDatabase) {
    LaunchedEffect(appDb) { com.batchfee.edu.data.firestore.BackgroundSyncQueue.start(appDb) }
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val isLoggedIn by SessionManager.currentUserId.collectAsState()
    val sessionRole by SessionManager.currentUserRole.collectAsState()
    val sessionInstituteId by SessionManager.currentInstituteId.collectAsState()
    val sessionStaffPermissions by SessionManager.currentStaffPermissions.collectAsState()
    val sessionNotice by SessionManager.sessionNotice.collectAsState()
    val lastActivityAtMs by SessionManager.lastActivityAtMs.collectAsState()
    val studentSessionId by StudentSessionManager.studentId.collectAsState()
    val studentSessionExpiry by StudentSessionManager.sessionExpiresAtMs.collectAsState()
    val restoredStudentSession by StudentSessionManager.restoredSession.collectAsState()
    val sessionScope = rememberCoroutineScope()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Push registration still runs; Android simply suppresses alerts if declined. */ }
    val notificationPromptPreferences = remember(context) {
        context.getSharedPreferences("batchfee_notification_prompt", Context.MODE_PRIVATE)
    }
    var showNotificationEducation by rememberSaveable { mutableStateOf(false) }
    var hadStudentSession by rememberSaveable { mutableStateOf(StudentSessionManager.isLoggedIn()) }

    // Each authenticated owner/staff device registers through the trusted
    // callable. On Android 13+ we explain the benefit in BatchFee's own UI
    // before opening the system-controlled permission sheet.
    LaunchedEffect(isLoggedIn, sessionRole, sessionInstituteId) {
        val tenantRole = sessionRole in setOf("InstituteOwner", "InstituteAdmin", "Staff")
        if (isLoggedIn != null && tenantRole && !sessionInstituteId.isNullOrBlank()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !notificationPromptPreferences.getBoolean("has_explained", false)
            ) {
                showNotificationEducation = true
            }
            NoticePushRegistration.registerCurrentTenantDevice()
        }
    }

    if (showNotificationEducation) {
        NotificationPermissionEducationDialog(
            onEnable = {
                notificationPromptPreferences.edit().putBoolean("has_explained", true).apply()
                showNotificationEducation = false
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onNotNow = {
                notificationPromptPreferences.edit().putBoolean("has_explained", true).apply()
                showNotificationEducation = false
            }
        )
    }

    // Soft review prompt: appears ~1 month after install, re-asks every 3 days
    // if skipped, and stops permanently once a rating is posted.
    var showReviewDialog by rememberSaveable { mutableStateOf(false) }
    var showReviewThanks by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isLoggedIn, studentSessionId, restoredStudentSession) {
        val anyLoggedIn = isLoggedIn != null || (restoredStudentSession && studentSessionId != null)
        if (anyLoggedIn) {
            delay(2500)
            // The server flag survives reinstall, so a user who already rated
            // never sees the prompt again even on a fresh install.
            if (ReviewPromptPreferences.shouldShow(context, System.currentTimeMillis()) &&
                !ReviewPromptPreferences.isRatedOnServer(context)
            ) {
                showReviewDialog = true
            }
        }
    }

    if (showReviewDialog) {
        InAppReviewDialog(
            onPost = { stars, comment ->
                ReviewPromptPreferences.markRated(context)
                ReviewPromptPreferences.markShown(context, System.currentTimeMillis())
                showReviewDialog = false
                sessionScope.launch { ReviewPromptPreferences.recordServerRating(stars) }
                if (stars >= 4) {
                    sessionScope.launch {
                        (context as? Activity)?.let { ReviewFlowLauncher.openPlayReview(it) }
                    }
                } else {
                    sessionScope.launch {
                        runCatching { NoticeCenterRepository().submitReviewFeedback(stars, comment) }
                    }
                    showReviewThanks = true
                }
            },
            onDismiss = {
                ReviewPromptPreferences.markShown(context, System.currentTimeMillis())
                showReviewDialog = false
            },
        )
    }

    if (showReviewThanks) {
        ReviewThanksDialog(onDone = { showReviewThanks = false })
    }

    // Navigation is derived directly from session state. The previous implementation used a
    // temporary "was logged in" flag, which could miss a fast expiry event during bootstrap.
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn == null) {
            navController.navigate(AuthRoute) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    LaunchedEffect(restoredStudentSession, studentSessionId, isLoggedIn) {
        if (restoredStudentSession && studentSessionId != null && isLoggedIn == null) {
            navController.navigate(StudentDashboardRoute) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    // A single, session-scoped listener group keeps the high-use owner data current.
    // It only updates Room in the background and is always removed on logout/switch.
    DisposableEffect(isLoggedIn, sessionRole, sessionInstituteId, sessionStaffPermissions) {
        val instituteId = sessionInstituteId
        if (isLoggedIn != null && sessionRole != "SuperAdmin" && !instituteId.isNullOrBlank()) {
            InstituteRealtimeSyncManager.start(
                db = appDb,
                instituteId = instituteId,
                role = sessionRole,
                permissions = sessionStaffPermissions
            )
        }
        onDispose {
            if (!instituteId.isNullOrBlank()) InstituteRealtimeSyncManager.stop(instituteId)
        }
    }

    LaunchedEffect(studentSessionId) {
        if (studentSessionId != null) {
            hadStudentSession = true
        } else if (hadStudentSession) {
            hadStudentSession = false
            navController.navigate(AuthRoute) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    LaunchedEffect(studentSessionId, studentSessionExpiry) {
        val activeStudentId = studentSessionId ?: return@LaunchedEffect
        val remainingMs = (studentSessionExpiry - System.currentTimeMillis()).coerceAtLeast(0L)
        delay(remainingMs)
        if (StudentSessionManager.studentId.value == activeStudentId &&
            !StudentSessionManager.isLoggedIn()) {
            StudentSessionManager.logout()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && SessionManager.isLoggedIn()) {
                if (SessionManager.isSessionInactive()) {
                    SessionManager.expireSession()
                }
            }
            if (event == Lifecycle.Event.ON_RESUME &&
                (StudentSessionManager.isLoggedIn() || StudentSessionManager.hasPendingVerification())) {
                sessionScope.launch { StudentSessionManager.validateActiveSession() }
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
            if (StudentSessionManager.isLoggedIn() && firebaseAuth.currentUser == null) {
                StudentSessionManager.onFirebaseSignedOut()
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

        suspend fun updateLastActive() = withContext(Dispatchers.IO) {
            try {
                FirebaseFirestore.getInstance()
                    .collection("institutes").document(instId)
                    .update("lastActiveAt", System.currentTimeMillis())
                    .await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FirebaseFailureReporter.report(
                    e,
                    operation = "update institute last activity",
                    permissionDeniedIsExpected = true
                )
            }
        }

        // Check entitlement before attempting a protected write. Expired sessions
        // therefore produce no repeated permission-denied events.
        if (checkSubscriptionExpired(instId, appDb)) {
            navController.navigate(SubscriptionExpiredRoute) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
            return@LaunchedEffect
        }
        updateLastActive()
        while (true) {
            delay(60 * 1000L)
            if (SessionManager.currentUserId.value != uid) break
            // Every tenant role is subscription-gated; billing remains available on expiry.
            if (SessionManager.currentUserId.value == uid) {
                val expired = checkSubscriptionExpired(instId, appDb)
                if (expired) {
                    navController.navigate(SubscriptionExpiredRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                    break
                }
                updateLastActive()
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
                    val instituteId = SessionManager.currentInstituteId.value
                    val role = SessionManager.currentUserRole.value
                    navController.navigate(DashboardRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }

                    // Subscription enforcement remains remote-authoritative, but it must not
                    // keep a successfully authenticated user on the login spinner.
                    if (role != "SuperAdmin" && role != "Staff" && instituteId != null) {
                        scope.launch {
                            val isExpired = checkSubscriptionExpired(instituteId, appDb)
                            if (
                                isExpired &&
                                SessionManager.currentInstituteId.value == instituteId &&
                                SessionManager.isLoggedIn()
                            ) {
                                navController.navigate(SubscriptionExpiredRoute) {
                                    popUpTo(navController.graph.id) { inclusive = true }
                                }
                            }
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
                },
                onNavigateStudentDashboard = {
                    navController.navigate(StudentDashboardRoute) {
                        popUpTo(AuthRoute) { inclusive = true }
                    }
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
            var currentTab by rememberSaveable { mutableStateOf("DashboardRoute") }
            // More is a dashboard tab rather than a separate navigation entry. Without
            // this handler Android sees the dashboard as the root and closes the app.
            BackHandler(enabled = currentTab == "More") {
                currentTab = "DashboardRoute"
            }
            com.batchfee.edu.ui.dashboard.DashboardTabsScreen(
                db = appDb,
                currentRoute = currentTab,
                onNavigate = navigate@ { route ->
                    if (route == "DashboardRoute" || route == "More") {
                        currentTab = route
                    } else {
                        val accessRoute = route.substringBefore('|')
                        if (!AccessControl.canAccessRoute(accessRoute)) return@navigate
                        if (route.startsWith("QuestionBankFoundationRoute|")) {
                            val selection = route.split('|', limit = 3)
                            navController.navigate(
                                com.batchfee.edu.ui.navigation.QuestionBankFoundationRoute(
                                    className = selection.getOrNull(1),
                                    subject = selection.getOrNull(2),
                                )
                            )
                            return@navigate
                        }
                        when (route) {
                            "StudentsRoute" -> navController.navigate(StudentsRoute)
                            "ArchivedStudentsRoute" -> navController.navigate(ArchivedStudentsRoute)
                            "AllArchivesRoute" -> navController.navigate(AllArchivesRoute)
                            "AddStudentRoute" -> navController.navigate(AddStudentRoute)
                            "BatchesRoute" -> navController.navigate(BatchesRoute)
                            "AddBatchRoute" -> navController.navigate(AddBatchRoute)
                            "FeeDashboardRoute" -> navController.navigate(FeeDashboardRoute)
                            "DueFeesRoute" -> navController.navigate(DueFeesRoute)
                            "CreateFeeRoute" -> navController.navigate(CreateFeeRoute)
                            "UnifiedCollectRoute" -> navController.navigate(UnifiedCollectRoute)
                            "PaymentRequestReviewRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.PaymentRequestReviewRoute)
                            "PaymentSettingsRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.PaymentSettingsRoute)
                            "AttendanceRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.AttendanceRoute)
                            "AttendanceReportRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.AttendanceReportRoute)
                            "ReportsRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.ReportsRoute)
                            "ReportsRoute?period=today" -> navController.navigate(com.batchfee.edu.ui.navigation.ReportsRoute(period = "today"))
                            "ReportsRoute?period=month" -> navController.navigate(com.batchfee.edu.ui.navigation.ReportsRoute(period = "month"))
                            "ReportsRoute?period=lifetime" -> navController.navigate(com.batchfee.edu.ui.navigation.ReportsRoute(period = "lifetime"))
                            "ReminderTemplatesRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.ReminderTemplatesRoute)
                            "SmartDueAutomationRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.SmartDueAutomationRoute)
                            "StaffRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.StaffRoute)
                            "AddStaffRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.AddStaffRoute)
                            "StaffActivityRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.StaffActivityRoute)
                            "StudentActivityRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.StudentActivityRoute)
                            "RoutineRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.RoutineRoute)
                            "StaffAttendanceRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.StaffAttendanceRoute)
                            "SalaryRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.SalaryRoute)
                            "ExpensesRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.ExpensesRoute)
                            "AddExpenseRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.AddExpenseRoute())
                            "ProfitLossRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.ProfitLossRoute)
                            "ExamsRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.ExamsRoute)
                            "CreateExamRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.CreateExamRoute)
                            "QuestionBankFoundationRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.QuestionBankFoundationRoute())
                            "IdCardGeneratorRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.IdCardGeneratorRoute)
                            "BirthdayReminderRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.BirthdayReminderRoute)
                            "SettingsRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.SettingsRoute)
                            "NoticeCenterRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.NoticeCenterRoute)
                            "ProductFeedbackRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.ProductFeedbackRoute)
                            "TutorialGuideRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.TutorialGuideRoute)
                            "EnquiryListRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.EnquiryListRoute)
                            "WorksListRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.WorksListRoute)
                            "HomeworkListRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.HomeworkListRoute)
                            "AssignmentListRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.AssignmentListRoute)
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

        composable<ArchivedStudentsRoute> {
            com.batchfee.edu.ui.students.ArchivedStudentsScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }

        composable<AllArchivesRoute> {
            com.batchfee.edu.ui.archive.AllArchivesScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
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
                onCollectPayment = { feeId -> navController.navigate(CollectPaymentRoute(feeId)) },
                onNavigatePaymentRequests = { navController.navigate(PaymentRequestReviewRoute) }
            )
        }

        composable<PaymentRequestReviewRoute> {
            com.batchfee.edu.ui.fees.PaymentRequestReviewScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onNavigateReceipt = { paymentId ->
                    navController.popBackStack()
                    navController.navigate(ReceiptDetailRoute(paymentId))
                }
            )
        }

        composable<PaymentSettingsRoute> {
            com.batchfee.edu.ui.fees.PaymentSettingsScreen(db = appDb, onBack = { navController.popBackStack() })
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

        composable<com.batchfee.edu.ui.navigation.SmartDueAutomationRoute> {
            com.batchfee.edu.ui.automation.SmartDueAutomationScreen(db = appDb, onBack = { navController.popBackStack() })
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
                onEdit = { navController.navigate(EditStaffRoute(route.staffId)) },
                onTeachingSessions = { navController.navigate(TeacherClassSessionsRoute(route.staffId)) }
            )
        }

        composable<TeacherClassSessionsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TeacherClassSessionsRoute>()
            com.batchfee.edu.ui.staff.TeacherClassSessionsScreen(
                db = appDb,
                staffId = route.staffId,
                onBack = { navController.popBackStack() },
            )
        }

        composable<StaffActivityRoute> {
            com.batchfee.edu.ui.staff.StaffActivityScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }

        composable<StudentActivityRoute> {
            com.batchfee.edu.ui.students.StudentActivityScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }

        composable<RoutineRoute> {
            com.batchfee.edu.ui.batches.RoutineScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onOpenCustomRoutines = { navController.navigate(CustomRoutineListRoute) }
            )
        }

        composable<CustomRoutineListRoute> {
            com.batchfee.edu.ui.batches.CustomRoutineListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onCreate = { navController.navigate(CreateCustomRoutineRoute) },
                onEdit = { routineId -> navController.navigate(EditCustomRoutineRoute(routineId)) },
                onView = { routineId -> navController.navigate(CustomRoutineDetailRoute(routineId)) }
            )
        }

        composable<CreateCustomRoutineRoute> {
            com.batchfee.edu.ui.batches.CustomRoutineEditorScreen(
                db = appDb,
                routineId = null,
                onBack = { navController.popBackStack() },
                onSaved = { routineId -> navController.navigate(CustomRoutineDetailRoute(routineId)) }
            )
        }

        composable<EditCustomRoutineRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<EditCustomRoutineRoute>()
            com.batchfee.edu.ui.batches.CustomRoutineEditorScreen(
                db = appDb,
                routineId = route.routineId,
                onBack = { navController.popBackStack() },
                onSaved = { routineId -> navController.navigate(CustomRoutineDetailRoute(routineId)) }
            )
        }

        composable<CustomRoutineDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<CustomRoutineDetailRoute>()
            com.batchfee.edu.ui.batches.CustomRoutineDetailScreen(
                db = appDb,
                routineId = route.routineId,
                onBack = { navController.popBackStack() },
                onEdit = { routineId -> navController.navigate(EditCustomRoutineRoute(routineId)) }
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
                onAddExpense = { navController.navigate(AddExpenseRoute()) },
                onEditExpense = { expenseId -> navController.navigate(AddExpenseRoute(expenseId = expenseId)) },
                onNavigateToPricing = { navController.navigate(PricingRoute) }
            )
        }
        
        composable<AddExpenseRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<AddExpenseRoute>()
            com.batchfee.edu.ui.expenses.AddEditExpenseScreen(
                db = appDb,
                expenseId = route.expenseId,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable<ProfitLossRoute> {
            com.batchfee.edu.ui.reports.ProfitLossScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onNavigateToPricing = { navController.navigate(PricingRoute) },
                onCollectFee = { navController.navigate(UnifiedCollectRoute) },
                onAddExpense = { navController.navigate(AddExpenseRoute()) },
            )
        }
        
        composable<ExamsRoute> {
            com.batchfee.edu.ui.exams.ExamListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddExam = { navController.navigate(CreateExamRoute) },
                onNavigateToDetail = { examId -> navController.navigate(ExamDetailRoute(examId)) },
                onNavigateToPricing = { navController.navigate(PricingRoute) },
                onOpenFinalExams = { navController.navigate(FinalExamsRoute) },
                onOpenQuestionBank = { navController.navigate(QuestionBankFoundationRoute()) },
                onOpenCuratedQuestionBank = { navController.navigate(CuratedQuestionBankRoute) },
                onCreateFinalExam = { navController.navigate(CreateFinalExamRoute) }
            )
        }

        composable<QuestionBankFoundationRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<QuestionBankFoundationRoute>()
            com.batchfee.edu.ui.exams.QuestionBankFoundationScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                initialClassName = route.className,
                initialSubject = route.subject,
            )
        }

        composable<CuratedQuestionBankRoute> {
            com.batchfee.edu.ui.exams.CuratedQuestionBankScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
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

        // ── Final Exam module ───────────────────────────────
        composable<FinalExamsRoute> {
            com.batchfee.edu.ui.exams.FinalExamListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onCreateExam = { navController.navigate(CreateFinalExamRoute) },
                onOpenExam = { examId -> navController.navigate(FinalExamDetailRoute(examId)) }
            )
        }

        composable<CreateFinalExamRoute> {
            com.batchfee.edu.ui.exams.CreateFinalExamScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onCreated = { examId ->
                    navController.popBackStack()
                    navController.navigate(FinalExamDetailRoute(examId))
                }
            )
        }

        composable<FinalExamDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<FinalExamDetailRoute>()
            com.batchfee.edu.ui.exams.FinalExamDetailScreen(
                db = appDb,
                examId = route.examId,
                onBack = { navController.popBackStack() },
                onOpenMarks = { examId, subjectId -> navController.navigate(FinalExamMarksRoute(examId, subjectId)) },
                onOpenResults = { examId -> navController.navigate(FinalExamResultsRoute(examId)) }
            )
        }

        composable<FinalExamMarksRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<FinalExamMarksRoute>()
            com.batchfee.edu.ui.exams.FinalExamMarksEntryScreen(
                db = appDb,
                examId = route.examId,
                subjectId = route.subjectId,
                onBack = { navController.popBackStack() }
            )
        }

        composable<FinalExamResultsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<FinalExamResultsRoute>()
            com.batchfee.edu.ui.exams.FinalExamResultsScreen(
                db = appDb,
                examId = route.examId,
                onBack = { navController.popBackStack() }
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
                        "SmartDueAutomationRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.SmartDueAutomationRoute)
                        "BackupRestoreRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.BackupRestoreRoute)
                        "StudentRegistrationRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.StudentRegistrationRoute)
                        "PaymentSettingsRoute" -> navController.navigate(com.batchfee.edu.ui.navigation.PaymentSettingsRoute)
                    }
                }
            )
        }

        composable<NoticeCenterRoute> {
            AdminNoticeCenterScreen(onBack = { navController.popBackStack() })
        }

        composable<ProductFeedbackRoute> {
            ProductFeedbackScreen(onBack = { navController.popBackStack() })
        }
        composable<TutorialGuideRoute> {
            TutorialGuideScreen(onBack = { navController.popBackStack() })
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
                onOpenQuestionCuration = { navController.navigate(QuestionCurationRoute) },
                onOpenQuestionBankAdmin = { navController.navigate(QuestionBankAdminRoute) },
                onLogout = {
                    SessionManager.logout()
                    navController.navigate(AuthRoute) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }
            )
        }

        composable<QuestionCurationRoute> {
            QuestionCurationScreen(onBack = { navController.popBackStack() })
        }

        composable<QuestionBankAdminRoute> {
            QuestionBankAdminScreen(
                onBack = { navController.popBackStack() },
                onOpenModeration = { navController.navigate(QuestionCurationRoute) },
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

        composable<StudentDashboardRoute> {
            StudentMainScaffold(
                onLogout = {
                    StudentSessionManager.logout()
                }
            )
        }

        composable<WorksListRoute> {
            com.batchfee.edu.ui.works.WorksListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddWork = { navController.navigate(AddWorkRoute) }
            )
        }

        composable<AddWorkRoute> {
            com.batchfee.edu.ui.works.AddWorkScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }

        composable<HomeworkListRoute> {
            com.batchfee.edu.ui.homework.HomeworkListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddHomework = { navController.navigate(AddHomeworkRoute) }
            )
        }

        composable<AddHomeworkRoute> {
            com.batchfee.edu.ui.homework.AddHomeworkScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }

        composable<AssignmentListRoute> {
            com.batchfee.edu.ui.assignments.AssignmentListScreen(
                db = appDb,
                onBack = { navController.popBackStack() },
                onAddAssignment = { navController.navigate(AddAssignmentRoute) }
            )
        }

        composable<AddAssignmentRoute> {
            com.batchfee.edu.ui.assignments.AddAssignmentScreen(
                db = appDb,
                onBack = { navController.popBackStack() }
            )
        }
    }

    // Android can restore a detail destination as the only entry after the process
    // is recreated. In that case the normal NavController back action would close
    // the app although the user is still inside a signed-in owner flow. Return them
    // to the dashboard instead. True root screens keep Android's normal exit behavior.
    val routeWithoutHistory = currentBackStackEntry?.destination?.route
    val isRecoverableOwnerDetail = isLoggedIn != null &&
        navController.previousBackStackEntry == null &&
        routeWithoutHistory !in setOf(
            DashboardRoute::class.qualifiedName,
            SuperAdminRoute::class.qualifiedName,
            SubscriptionExpiredRoute::class.qualifiedName,
        )
    BackHandler(enabled = isRecoverableOwnerDetail) {
        navController.navigate(DashboardRoute) {
            popUpTo(navController.graph.id) { inclusive = true }
        }
    }
}

private suspend fun checkSubscriptionExpired(instituteId: String, db: com.batchfee.edu.data.database.AppDatabase): Boolean {
    return try {
        val doc = FirebaseFirestore.getInstance()
            .collection("institutes").document(instituteId)
            .get().await()
        val isActive = doc.getBoolean("isActive") == true
        val status = doc.getString("subscriptionStatus")
        if (!isActive || status !in setOf("trial", "active")) return true
        val now = System.currentTimeMillis()
        // Match firestore.rules hasActiveSubscription exactly.
        // A paid subscriber may have a stale trialEndDate in the past — ignore it
        // currentPeriodEndMs remains the backend-authoritative expiry field.
        val periodEnd = doc.getLong("currentPeriodEndMs") ?: return true
        now >= periodEnd
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FirebaseFailureReporter.report(
            e,
            operation = "check subscription entitlement",
            permissionDeniedIsExpected = true
        )
        // Offline fallback: check Room DB instead of allowing access
        val local = withContext(Dispatchers.IO) { db.instituteDao().getInstitute(instituteId) }
        if (local != null) {
            val now = System.currentTimeMillis()
            if (local.subscriptionStatus !in setOf("trial", "active")) return true
            val endMs = local.currentPeriodEndMs.takeIf { it > 0L } ?: local.trialEndDateMs
            return now >= endMs
        }
        // No data available at all: safest is to deny access
        true
    }
}

