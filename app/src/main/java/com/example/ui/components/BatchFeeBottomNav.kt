package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
private val NavBg = Color(0xFF081422)
private val NavSurface = NavBg
private val BorderSub = Color(0xFF1E293B)
private val Cyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val TextMuted = Color(0xFF94A3B8)
private val TextWhite = Color(0xFFF8FAFC)

// ── Data class for nav items ────────────────────────────────────
private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val navItems = listOf(
    NavItem("DashboardRoute",  "Home",      Icons.Filled.Home),
    NavItem("StudentsRoute",    "Students",  Icons.Default.Person),
    NavItem("UnifiedCollectRoute", "Fee",    Icons.Filled.Payments),
    NavItem("BatchesRoute",     "Batches",   Icons.AutoMirrored.Filled.List),
    NavItem("More",             "More",      Icons.Default.Menu)
)

@Composable
fun BatchFeeBottomNav(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NavBg)
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Cyan.copy(alpha = 0.34f), Color.Transparent)
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .background(NavSurface)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navItems.forEach { item ->
                val isSelected = currentRoute == item.route
                val isPrimaryAction = item.route == "UnifiedCollectRoute"
                val itemColor by animateColorAsState(
                    targetValue = if (isSelected || isPrimaryAction) Cyan else TextMuted.copy(alpha = 0.68f),
                    animationSpec = tween(220),
                    label = "navItemColor"
                )
                BottomNavItem(
                    item = item,
                    selected = isSelected,
                    primaryAction = isPrimaryAction,
                    color = itemColor,
                    modifier = Modifier.weight(if (isPrimaryAction) 1.16f else 1f),
                    onClick = { onNavigate(item.route) }
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    item: NavItem,
    selected: Boolean,
    primaryAction: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val activeVisual = selected || primaryAction
    val backgroundBrush =
        if (selected) {
            Brush.verticalGradient(
                listOf(ElectricBlue.copy(alpha = 0.34f), Cyan.copy(alpha = 0.18f))
            )
        } else if (primaryAction) {
            Brush.verticalGradient(
                listOf(ElectricBlue.copy(alpha = 0.18f), Cyan.copy(alpha = 0.10f))
            )
        } else {
            Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
        }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundBrush)
            .then(
                if (activeVisual) {
                    Modifier.border(
                        1.dp,
                        Cyan.copy(alpha = if (selected) 0.30f else 0.20f),
                        RoundedCornerShape(16.dp)
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(
                    when {
                        primaryAction -> 36.dp
                        selected -> 30.dp
                        else -> 28.dp
                    }
                )
                .clip(RoundedCornerShape(if (primaryAction) 13.dp else 11.dp))
                .background(
                    if (activeVisual) {
                        Brush.horizontalGradient(
                            listOf(
                                ElectricBlue,
                                Cyan,
                                if (primaryAction) Color(0xFF67E8F9) else Cyan
                            )
                        )
                    } else {
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                item.icon,
                contentDescription = item.label,
                tint = if (activeVisual) TextWhite else color,
                modifier = Modifier.size(
                    when {
                        primaryAction -> 20.dp
                        selected -> 17.dp
                        else -> 21.dp
                    }
                )
            )
        }
        Spacer(Modifier.height(if (primaryAction) 3.dp else 4.dp))
        Text(
            item.label,
            fontSize = if (primaryAction) 10.5.sp else 10.sp,
            lineHeight = 11.sp,
            fontWeight = if (selected || primaryAction) FontWeight.Bold else FontWeight.Medium,
            color = if (activeVisual) Cyan else color,
            maxLines = 1
        )
    }
}
