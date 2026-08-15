package com.batchfee.edu.ui.studentapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

// Student app dark palette
private val StuBg    = Color(0xFF07111F)
private val StuCard  = Color(0xFF0F172A)
private val StuCyan  = Color(0xFF22D3EE)
private val StuBlue  = Color(0xFF3B82F6)
private val StuGreen = Color(0xFF22C55E)
private val StuWhite = Color(0xFFF8FAFC)
private val StuMuted = Color(0xFF94A3B8)

data class StudentBottomNav(val route: String, val label: String, val icon: ImageVector)

private val bottomItems = listOf(
    StudentBottomNav("student_dash", "Home", Icons.Filled.Home),
    StudentBottomNav("student_works", "Work", Icons.Filled.Book),
    StudentBottomNav("student_fees", "Fees", Icons.Filled.MonetizationOn),
    StudentBottomNav("student_attendance", "Attend", Icons.Filled.CheckCircle),
    StudentBottomNav("student_results", "Results", Icons.Filled.Grade),
    StudentBottomNav("student_profile", "Profile", Icons.Filled.Person)
)

@Composable
fun StudentMainScaffold(onLogout: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomItems.map { it.route }

    Scaffold(
        containerColor = StuBg,
        bottomBar = {
            if (showBottomBar) {
                // The app draws edge-to-edge, so reserve the device gesture area.
                // This keeps every tab fully tappable above the system navigation bar.
                Surface(
                    modifier = Modifier.navigationBarsPadding(),
                    color = StuCard,
                    shadowElevation = 10.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 4.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    bottomItems.forEach { item ->
                        val selected = currentRoute == item.route
                        val tint by animateColorAsState(
                            targetValue = if (selected) StuCyan else StuMuted,
                            animationSpec = tween(180),
                            label = "studentNavTint"
                        )
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true; restoreState = true
                                }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier.size(width = 42.dp, height = 30.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(if (selected) StuCyan.copy(alpha = 0.16f) else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) { Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(21.dp)) }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                item.label,
                                color = tint,
                                fontSize = 9.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip
                            )
                        }
                    }
                }
                }
            }
        }
    ) { padding ->
        NavHost(navController, startDestination = "student_dash", Modifier.padding(padding)) {
            composable("student_dash") { StudentDashboardScreen() }
            composable("student_works") { StudentWorksScreen(onBack = { navController.popBackStack() }) }
            composable("student_fees") {
                StudentFeeScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDocuments = { navController.navigate("student_documents") }
                )
            }
            composable("student_attendance") { StudentAttendanceScreen(onBack = { navController.popBackStack() }) }
            composable("student_results") {
                StudentResultScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDocuments = { navController.navigate("student_documents") }
                )
            }
            composable("student_profile") {
                StudentProfileScreen(
                    onLogout = onLogout
                )
            }
            composable("student_documents") { StudentDocumentsScreen(onBack = { navController.popBackStack() }) }
        }
    }
}
