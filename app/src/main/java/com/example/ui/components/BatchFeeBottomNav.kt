package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Premium dark theme colors (matching PricingScreen) ──────────
private val NavBg         = Color(0xFF0B1622)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val TextMuted     = Color(0xFF94A3B8)

// ── Data class for nav items ────────────────────────────────────
private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val navItems = listOf(
    NavItem("DashboardRoute",  "Home",      Icons.Filled.Home),
    NavItem("StudentsRoute",    "Students",  Icons.Default.Person),
    NavItem("BatchesRoute",     "Batches",   Icons.AutoMirrored.Filled.List),
    NavItem("FeeDashboardRoute","Collection Fee", Icons.Default.DateRange),
    NavItem("More",             "More",      Icons.Default.Menu)
)

@Composable
fun BatchFeeBottomNav(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    NavigationBar(
        containerColor = NavBg,
        tonalElevation = 0.dp
    ) {
        navItems.forEach { item ->
            val isSelected = currentRoute == item.route

            // Animate icon color for smooth transition
            val iconColor by animateColorAsState(
                targetValue = if (isSelected) Cyan else TextMuted.copy(alpha = 0.6f),
                animationSpec = tween(300),
                label = "navIconColor"
            )

            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(item.route) },
                icon = {
                    // Selected: small gradient background badge behind icon
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = item.label,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = iconColor
                        )
                    }
                },
                label = {
                    Text(
                        item.label,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) Cyan else TextMuted.copy(alpha = 0.6f)
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent,
                    selectedIconColor = Cyan,
                    unselectedIconColor = TextMuted.copy(alpha = 0.6f),
                    selectedTextColor = Cyan,
                    unselectedTextColor = TextMuted.copy(alpha = 0.6f)
                )
            )
        }
    }
}
