package com.batchfee.edu.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.batchfee.edu.domain.AccessControl
import com.batchfee.edu.domain.SessionManager

private val NavBg = Color(0xFF081422)
private val BorderSub = Color(0xFF1E293B)
private val Cyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val TextMuted = Color(0xFF94A3B8)
private val TextWhite = Color(0xFFF8FAFC)

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val isFeeAction: Boolean = false
)

private val navItems = listOf(
    NavItem("DashboardRoute", "Home", Icons.Filled.Home),
    NavItem("StudentsRoute", "Students", Icons.Default.Person),
    NavItem("UnifiedCollectRoute", "Fee", Icons.Filled.Payments, isFeeAction = true),
    NavItem("BatchesRoute", "Batches", Icons.AutoMirrored.Filled.List),
    NavItem("More", "More", Icons.Default.Menu)
)

@Composable
fun BatchFeeBottomNav(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(NavBg)
            .navigationBarsPadding()
    ) {
        val compactLayout = maxWidth < 360.dp
        val navHeight = if (compactLayout) 64.dp else 72.dp
        val itemHorizontalPadding = if (compactLayout) 6.dp else 8.dp
        val itemVerticalPadding = if (compactLayout) 4.dp else 5.dp
        val iconBoxSize = if (compactLayout) 28.dp else 30.dp
        val iconSize = if (compactLayout) 17.dp else 18.dp
        val labelSize = if (compactLayout) 9.5.sp else 10.5.sp

        val currentRole by SessionManager.currentUserRole.collectAsState()
        val currentStaffPermissions by SessionManager.currentStaffPermissions.collectAsState()
        val visibleNavItems = remember(currentRole, currentStaffPermissions) {
            navItems.filter { AccessControl.canAccessRoute(it.route) }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
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
                    .height(navHeight)
                    .background(NavBg)
                    .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                visibleNavItems.forEach { item ->
                    val isFee = item.isFeeAction
                    val isSelected = currentRoute == item.route

                    val textColor by animateColorAsState(
                        targetValue = when {
                            isFee -> Cyan
                            isSelected -> Cyan
                            else -> TextMuted.copy(alpha = 0.68f)
                        },
                        animationSpec = tween(220),
                        label = "textColor"
                    )
                    val iconTint by animateColorAsState(
                        targetValue = when {
                            isFee -> TextWhite
                            isSelected -> Cyan.copy(alpha = 0.85f)
                            else -> TextMuted.copy(alpha = 0.68f)
                        },
                        animationSpec = tween(220),
                        label = "iconTint"
                    )

                    BottomNavItem(
                        item = item,
                        isSelected = isSelected,
                        isFeeAction = isFee,
                        textColor = textColor,
                        iconTint = iconTint,
                        iconBoxSize = iconBoxSize,
                        iconSize = iconSize,
                        labelSize = labelSize,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(item.route) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    item: NavItem,
    isSelected: Boolean,
    isFeeAction: Boolean,
    textColor: Color,
    iconTint: Color,
    iconBoxSize: Dp,
    iconSize: Dp,
    labelSize: TextUnit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Pulsing glow alpha for the Fee button border
    val infiniteTransition = rememberInfiniteTransition(label = "feeGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val bgBrush = if (isFeeAction) {
        Brush.verticalGradient(
            listOf(ElectricBlue.copy(alpha = 0.10f), Cyan.copy(alpha = 0.05f))
        )
    } else {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }

    val borderMod = when {
        isFeeAction -> Modifier.border(
            width = 1.5.dp,
            brush = Brush.horizontalGradient(
                listOf(
                    ElectricBlue.copy(alpha = glowAlpha),
                    Cyan.copy(alpha = glowAlpha * 1.1f),
                    Color(0xFF67E8F9).copy(alpha = glowAlpha * 0.6f),
                    Cyan.copy(alpha = glowAlpha * 1.1f),
                    ElectricBlue.copy(alpha = glowAlpha)
                )
            ),
            shape = RoundedCornerShape(17.dp)
        )
        else -> Modifier
    }

    val itemShadow = if (isFeeAction) {
        Modifier.shadow(
            elevation = 6.dp,
            shape = RoundedCornerShape(17.dp),
            spotColor = Cyan.copy(alpha = 0.16f),
            ambientColor = ElectricBlue.copy(alpha = 0.06f)
        )
    } else Modifier

    Column(
        modifier = modifier
            .fillMaxHeight()
            .then(itemShadow)
            .clip(RoundedCornerShape(17.dp))
            .background(bgBrush)
            .then(borderMod)
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(iconBoxSize)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isFeeAction) {
                        Brush.horizontalGradient(
                            listOf(ElectricBlue.copy(alpha = 0.22f), Cyan.copy(alpha = 0.16f))
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
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.label,
            fontSize = labelSize,
            lineHeight = 12.sp,
            fontWeight = if (isSelected || isFeeAction) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

