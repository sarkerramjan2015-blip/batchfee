package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AnimatedGlowBorder(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    borderWidth: Dp = 1.5.dp,
    glowWidth: Dp = 3.dp,
    animationDurationMillis: Int = 4500,
    backgroundColor: Color = Color(0xFF101827),
    borderColors: List<Color> = listOf(
        Color(0xFFFBBF24).copy(alpha = 0f),
        Color(0xFFFBBF24),
        Color(0xFF38BDF8),
        Color(0xFFA855F7),
        Color(0xFFFBBF24).copy(alpha = 0f)
    ),
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "GlowTransition")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDurationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "GlowAngle"
    )

    val innerCornerRadius = if (cornerRadius > borderWidth) cornerRadius - borderWidth else 0.dp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val centerOffset = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                    val brush = Brush.sweepGradient(borderColors, center = centerOffset)
                    onDrawBehind {
                        rotate(angle) {
                            val radius = kotlin.math.hypot(size.width / 2f, size.height / 2f)
                            drawCircle(brush = brush, radius = radius, center = centerOffset)
                        }
                    }
                }
        )

        Box(
            modifier = Modifier
                .padding(borderWidth)
                .background(backgroundColor, RoundedCornerShape(innerCornerRadius))
                // Content will dictate the size, and background matches this inner box
        ) {
            content()
        }
    }
}
