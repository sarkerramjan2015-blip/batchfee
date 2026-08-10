package com.batchfee.edu.ui.studentapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    StudentBottomNav("student_dash", "Dashboard", Icons.Filled.Home),
    StudentBottomNav("student_works", "Works", Icons.Filled.Book),
    StudentBottomNav("student_fees", "Fees", Icons.Filled.MonetizationOn),
    StudentBottomNav("student_attendance", "Attendance", Icons.Filled.CheckCircle),
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
                NavigationBar(
                    containerColor = StuCard,
                    contentColor = StuWhite
                ) {
                    bottomItems.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            icon = { Icon(item.icon, item.label) },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true; restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = StuCyan,
                                selectedTextColor = StuCyan,
                                unselectedIconColor = StuMuted,
                                unselectedTextColor = StuMuted,
                                indicatorColor = StuCyan.copy(alpha = 0.15f)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(navController, startDestination = "student_dash", Modifier.padding(padding)) {
            composable("student_dash") { StudentDashboardScreen() }
            composable("student_works") { StudentWorksScreen(onBack = { navController.popBackStack() }) }
            composable("student_fees") { StudentFeeScreen(onBack = { navController.popBackStack() }) }
            composable("student_attendance") { StudentAttendanceScreen(onBack = { navController.popBackStack() }) }
            composable("student_results") { StudentResultScreen(onBack = { navController.popBackStack() }) }
            composable("student_profile") { StudentProfileScreen(onLogout = onLogout) }
        }
    }
}
