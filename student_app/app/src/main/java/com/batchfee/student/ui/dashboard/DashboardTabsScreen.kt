package com.batchfee.student.ui.dashboard

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.student.data.firebase.StudentFirestoreRepository
import com.batchfee.student.data.models.*
import com.batchfee.student.domain.SessionManager
import com.batchfee.student.ui.theme.*
import kotlinx.coroutines.delay
import java.util.*

data class MenuItem(
    val title: String,
    val icon: ImageVector,
    val iconContainerBg: Color,
    val iconColor: Color,
    val route: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardTabsScreen(
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit
) {
    val studentId by SessionManager.currentStudentId.collectAsState()
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val studentName by SessionManager.studentName.collectAsState()

    var student by remember { mutableStateOf<Student?>(null) }
    var institute by remember { mutableStateOf<Institute?>(null) }
    var batches by remember { mutableStateOf<List<Batch>>(emptyList()) }
    var fees by remember { mutableStateOf<List<Fee>>(emptyList()) }
    var attendanceSummary by remember { mutableStateOf<AttendanceSummary?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val repo = remember { StudentFirestoreRepository() }

    var showContent by remember { mutableStateOf(false) }

    LaunchedEffect(studentId, instituteId) {
        val sid = studentId ?: return@LaunchedEffect
        val iid = instituteId ?: return@LaunchedEffect
        isLoading = true
        try {
            student = repo.getStudent(iid, sid)
            institute = repo.getInstitute(iid)
            batches = repo.getStudentBatches(iid, sid)
            fees = repo.getFees(iid, sid)
            if (batches.isNotEmpty()) {
                attendanceSummary = repo.getAttendanceSummary(iid, sid, batches.first().id)
            }
        } catch (_: Exception) { }
        isLoading = false
        delay(100); showContent = true
    }

    val totalDue = fees.filter { it.status == "unpaid" || it.status == "overdue" || it.status == "partially_paid" }
        .sumOf { it.dueAmount }
    val totalPaid = fees.filter { it.status == "paid" || it.status == "partially_paid" }
        .sumOf { it.paidAmount }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BatchFee", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        if (studentName != null)
                            Text("Welcome back, ${studentName!!.split(" ").first()}",
                                fontSize = 12.sp, color = TextSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Filled.Logout, "Logout", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard)
            )
        },
        bottomBar = {
            Surface(
                shadowElevation = 0.dp,
                color = SurfaceCard,
                tonalElevation = 0.dp
            ) {
                Column {
                    // Top border line — 1px solid #F1F5F9
                    HorizontalDivider(thickness = 1.dp, color = Color(0xFFF1F5F9))
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
                        listOf(
                            "Dashboard" to Icons.Filled.Home,
                            "Fees" to Icons.Filled.AccountBalanceWallet,
                            "Class" to Icons.Filled.School,
                            "Profile" to Icons.Filled.Person,
                            "More" to Icons.Filled.MoreHoriz
                        ).forEachIndexed { index, (label, icon) ->
                            NavigationBarItem(
                                icon = { Icon(icon, null, modifier = Modifier.size(22.dp)) },
                                label = { Text(label, fontSize = 10.sp) },
                                selected = selectedTab == index,
                                onClick = {
                                    selectedTab = index
                                    when (index) { 1 -> onNavigate("FeesRoute"); 2 -> onNavigate("AttendanceRoute"); 3 -> onNavigate("ProfileRoute") }
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = PrimaryDefault,
                                    selectedTextColor = PrimaryDefault,
                                    indicatorColor = PrimaryContainer,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryDefault, strokeWidth = 3.dp)
            }
        } else if (selectedTab == 0) {
            DashboardContent(
                modifier = Modifier.padding(padding),
                student = student, institute = institute, batches = batches,
                totalDue = totalDue, totalPaid = totalPaid,
                attendancePct = attendanceSummary?.percentage ?: 0f,
                showContent = showContent, onNavigate = onNavigate
            )
        } else if (selectedTab == 4) {
            MoreMenu(modifier = Modifier.padding(padding), onNavigate = onNavigate)
        }
    }
}

@Composable
private fun DashboardContent(
    modifier: Modifier = Modifier,
    student: Student?, institute: Institute?, batches: List<Batch>,
    totalDue: Double, totalPaid: Double, attendancePct: Float,
    showContent: Boolean, onNavigate: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 8.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // ═══════════════════════════════════════
        //  HERO BANNER — Deep Navy (#1E1B4B)
        // ═══════════════════════════════════════
        AnimatedVisibility(
            visible = showContent,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 }
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12 -> "Good Morning"
                                Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 17 -> "Good Afternoon"
                                else -> "Good Evening"
                            },
                            color = TextOnPrimary.copy(alpha = 0.7f), fontSize = 13.sp
                        )
                        Text(
                            text = student?.fullName ?: "Student",
                            color = TextOnPrimary, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (institute != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(institute.name,
                                color = TextOnPrimary.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                    }
                    // Avatar — semi-transparent white bg (rgba(255,255,255,0.15))
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = TextOnPrimary.copy(alpha = 0.15f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = (student?.fullName?.take(2) ?: "ST").uppercase(),
                                color = TextOnPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ═══════════════════════════════════════
        //  METRIC CARDS — Updated saturation
        // ═══════════════════════════════════════
        AnimatedVisibility(
            visible = showContent,
            enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 2 }
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Due — #FEE2E2 bg, #DC2626 text
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Due",
                    value = "\u09F3${String.format("%.0f", totalDue)}",
                    icon = Icons.Filled.TrendingUp,
                    bgColor = DueBg, contentColor = DueText, iconColor = DueIcon
                )
                // Paid — #DCFCE7 bg, #16A34A text
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Paid",
                    value = "\u09F3${String.format("%.0f", totalPaid)}",
                    icon = Icons.Filled.CheckCircle,
                    bgColor = PaidBg, contentColor = PaidText, iconColor = PaidIcon
                )
                // Attendance — #E0F2FE bg, #2563EB text
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Attendance",
                    value = "${attendancePct.toInt()}%",
                    icon = Icons.Filled.CalendarMonth,
                    bgColor = AttBg, contentColor = AttText, iconColor = AttIcon
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ═══════════════════════════════════════
        //  QUICK ACCESS — White cards (#FFFFFF)
        //  with border (1px #E2E8F0) + subtle shadow
        // ═══════════════════════════════════════
        AnimatedVisibility(
            visible = showContent,
            enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 4 }
        ) {
            Column {
                Text("Quick Access",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 12.dp))

                val menuItems = listOf(
                    MenuItem("My Fees", Icons.Filled.AccountBalanceWallet, CardFees, CardIconFees, "FeesRoute"),
                    MenuItem("Attendance", Icons.Filled.CalendarMonth, CardAttendance, CardIconAttendance, "AttendanceRoute"),
                    MenuItem("Exams", Icons.Filled.TaskAlt, CardExams, CardIconExams, "ExamsRoute"),
                    MenuItem("Results", Icons.Filled.Assessment, CardResults, CardIconResults, "ExamsRoute"),
                    MenuItem("Homework", Icons.Filled.MenuBook, CardHomework, CardIconHomework, "HomeworkRoute"),
                    MenuItem("Notices", Icons.Filled.Campaign, CardNotices, CardIconNotices, "NoticeRoute"),
                    MenuItem("Profile", Icons.Filled.Person, CardProfile, CardIconProfile, "ProfileRoute"),
                    MenuItem("Routine", Icons.Filled.Schedule, CardRoutine, CardIconRoutine, "AttendanceRoute"),
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    menuItems.chunked(2).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowItems.forEach { item ->
                                QuickAccessCard(
                                    modifier = Modifier.weight(1f),
                                    item = item,
                                    onClick = { onNavigate(item.route) }
                                )
                            }
                            if (rowItems.size < 2) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ═══════════════════════════════════════
        //  BATCHES
        // ═══════════════════════════════════════
        if (batches.isNotEmpty()) {
            Text("My Batches", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp))
            batches.forEach { batch -> BatchCard(batch = batch) }
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ── Metric Card — with exact colors ──
@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    title: String, value: String, icon: ImageVector,
    bgColor: Color, contentColor: Color, iconColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = contentColor)
            Text(title, fontSize = 11.sp, color = contentColor.copy(alpha = 0.6f))
        }
    }
}

// ── Quick Access Card — White with border + subtle shadow ──
@Composable
private fun QuickAccessCard(
    modifier: Modifier = Modifier,
    item: MenuItem,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Pastel icon container with contrasting icon
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = item.iconContainerBg
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(item.icon, null, tint = item.iconColor, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(item.title,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = TextHeading, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Batch Card ──
@Composable
private fun BatchCard(batch: Batch) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp), shape = RoundedCornerShape(12.dp),
                color = PrimaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.School, null, tint = PrimaryDefault, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(batch.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (batch.subject != null)
                    Text(batch.subject, color = TextSecondary, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Schedule, null, modifier = Modifier.size(12.dp), tint = TextSecondary)
                    Spacer(Modifier.width(4.dp))
                    Text("${batch.scheduleDays ?: ""} | ${batch.startTime ?: ""}",
                        color = TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

// ── More Menu ──
@Composable
private fun MoreMenu(modifier: Modifier = Modifier, onNavigate: (String) -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 8.dp)
    ) {
        Text("More Options", fontWeight = FontWeight.Bold, fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 16.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                MenuItem("Homework", Icons.Filled.MenuBook, CardHomework, CardIconHomework, "HomeworkRoute"),
                MenuItem("Notices", Icons.Filled.Campaign, CardNotices, CardIconNotices, "NoticeRoute"),
                MenuItem("Profile", Icons.Filled.Person, CardProfile, CardIconProfile, "ProfileRoute"),
                MenuItem("Routine", Icons.Filled.Schedule, CardRoutine, CardIconRoutine, "AttendanceRoute"),
            ).chunked(2).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { item ->
                        QuickAccessCard(Modifier.weight(1f), item) { onNavigate(item.route) }
                    }
                    if (rowItems.size < 2) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
