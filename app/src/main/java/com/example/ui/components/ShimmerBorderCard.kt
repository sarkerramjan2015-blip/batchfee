package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Premium card for high-priority feature entries. The border carries two
 * rotating light heads (a sweep gradient spun around the edge), a soft
 * pulsing glow, and a sheen band that sweeps across the surface.
 */
@Composable
fun ShimmerBorderCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    containerColor: Color,
    borderStops: List<Pair<Float, Color>>,
    glowColor: Color,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val transition = rememberInfiniteTransition(label = "shimmerBorderCard")
    val borderAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "borderAngle"
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glowAlpha"
    )
    val sheenOffset by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "sheenOffset"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = tween(durationMillis = if (pressed) 80 else 160, easing = FastOutSlowInEasing),
        label = "shimmerCardPress"
    )

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    } else Modifier

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .shadow(
                elevation = 12.dp,
                shape = shape,
                spotColor = glowColor.copy(alpha = glowAlpha),
                ambientColor = glowColor.copy(alpha = glowAlpha * 0.45f)
            )
            .clip(shape)
            .then(clickableModifier)
    ) {
        Box(Modifier.fillMaxSize().background(containerColor)) {
            Row(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val bandWidth = size.width * 0.55f
                        val x = size.width * sheenOffset
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.07f),
                                    Color.Transparent
                                ),
                                start = Offset(x - bandWidth, 0f),
                                end = Offset(x, size.height)
                            )
                        )
                    }
            )
        }
        Canvas(Modifier.matchParentSize()) {
            rotate(borderAngle, pivot = center) {
                drawRoundRect(
                    brush = Brush.sweepGradient(*borderStops.toTypedArray()),
                    size = size,
                    cornerRadius = CornerRadius(
                        shape.topStart.toPx(shapeSize = size, density = this),
                        shape.topStart.toPx(shapeSize = size, density = this)
                    ),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
    }
}
