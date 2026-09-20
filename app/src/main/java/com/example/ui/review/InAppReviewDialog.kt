package com.batchfee.edu.ui.review

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DialogBg = Color(0xFF101B31)
private val DialogBgTop = Color(0xFF182A50)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)
private val TextBody = Color(0xFFCBD5E1)
private val FieldBg = Color(0xFF172641)
private val FieldBorder = Color(0xFF334155)
private val StarAmber = Color(0xFFF59E0B)
private val StarOutline = Color(0xFF475569)
private val AccentCyan = Color(0xFF22C7E8)
private val ElectricBlue = Color(0xFF2563EB)
private val DialogShape = RoundedCornerShape(28.dp)

@Composable
fun InAppReviewDialog(
    onPost: (stars: Int, comment: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var stars by rememberSaveable { mutableIntStateOf(0) }
    var comment by rememberSaveable { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    ShimmerDialogFrame(
        visible = visible,
        onDismissRequest = onDismiss,
        icon = Icons.Filled.Star,
        title = "Enjoying BatchFee?",
        subtitle = "Please rate your experience with BatchFee.",
        onClose = onDismiss,
    ) {
        StarRatingRow(stars = stars, onStarsChange = { stars = it })
        RatingCaption(stars)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = comment,
            onValueChange = { if (it.length <= 300) comment = it },
            label = { Text("Write a comment (optional)", color = TextSecondary) },
            minLines = 2,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = FieldBg,
                unfocusedContainerColor = FieldBg,
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = FieldBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentCyan,
                focusedLabelColor = AccentCyan,
                unfocusedLabelColor = TextSecondary,
            ),
        )
        Spacer(Modifier.height(16.dp))
        GlowGradientButton(
            text = if (stars >= 1) "Post review" else "Select a star to post",
            enabled = stars >= 1,
            onClick = { onPost(stars, comment) },
        )
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Maybe later", color = TextSecondary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ReviewThanksDialog(onDone: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    ShimmerDialogFrame(
        visible = visible,
        onDismissRequest = onDone,
        icon = Icons.Filled.Favorite,
        title = "Thanks for your feedback!",
        subtitle = "Your comment helps us make BatchFee better.",
        onClose = onDone,
    ) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = "We read every comment and use it to improve the app.",
            color = TextBody,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        GlowGradientButton(text = "Done", enabled = true, onClick = onDone)
    }
}

/** Dialog frame carrying a rotating shimmer border, pulsing cyan glow,
 * and a sheen band sweeping across the surface. */
@Composable
private fun ShimmerDialogFrame(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "reviewDialogFrame")
    val borderAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "borderAngle",
    )
    val glowPulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glowPulse",
    )
    val sheenOffset by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "sheenOffset",
    )

    val entranceScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.86f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "frameScale",
    )
    val entranceAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "frameAlpha",
    )

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = Color.Transparent,
        title = {},
        text = {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = entranceScale
                        scaleY = entranceScale
                        alpha = entranceAlpha
                    }
                    .shadow(
                        elevation = 20.dp,
                        shape = DialogShape,
                        spotColor = AccentCyan.copy(alpha = 0.30f * glowPulse),
                        ambientColor = AccentCyan.copy(alpha = 0.12f * glowPulse),
                    )
                    .clip(DialogShape)
                    .background(Brush.verticalGradient(listOf(DialogBgTop, DialogBg)))
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DialogHeader(icon = icon, title = title, subtitle = subtitle, onClose = onClose)
                    Spacer(Modifier.height(14.dp))
                    content()
                }
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
                                        Color.White.copy(alpha = 0.06f),
                                        Color.Transparent,
                                    ),
                                    start = Offset(x - bandWidth, 0f),
                                    end = Offset(x, size.height),
                                )
                            )
                        }
                )
                Canvas(Modifier.matchParentSize()) {
                    rotate(borderAngle, pivot = center) {
                        drawRoundRect(
                            brush = Brush.sweepGradient(
                                0f to Color.Transparent,
                                0.16f to AccentCyan.copy(alpha = 0.95f),
                                0.30f to Color.White.copy(alpha = 0.35f),
                                0.50f to Color.Transparent,
                                0.66f to StarAmber.copy(alpha = 0.95f),
                                0.80f to Color.White.copy(alpha = 0.35f),
                                1f to Color.Transparent,
                            ),
                            size = size,
                            cornerRadius = CornerRadius(28.dp.toPx()),
                            style = Stroke(width = 1.6.dp.toPx()),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

@Composable
private fun DialogHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(ElectricBlue, AccentCyan))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp,
            )
            Text(
                text = subtitle,
                color = TextBody,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = TextSecondary)
        }
    }
}

@Composable
private fun StarRatingRow(stars: Int, onStarsChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 1..5) {
            val selected = index <= stars
            val scale by animateFloatAsState(
                targetValue = if (selected) 1.18f else 1f,
                animationSpec = if (selected) {
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
                } else {
                    tween(160, easing = FastOutSlowInEasing)
                },
                label = "starScale$index",
            )
            Box(
                Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(
                        elevation = if (selected) 10.dp else 0.dp,
                        shape = CircleShape,
                        spotColor = StarAmber.copy(alpha = 0.55f),
                        ambientColor = StarAmber.copy(alpha = 0.25f),
                    )
            ) {
                IconButton(onClick = { onStarsChange(index) }) {
                    Icon(
                        imageVector = if (selected) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "$index star",
                        tint = if (selected) StarAmber else StarOutline,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingCaption(stars: Int) {
    val caption = when (stars) {
        0 -> "Tap a star to rate"
        1 -> "We're sorry to hear that"
        2 -> "We'll work on improving"
        3 -> "Thanks for your feedback"
        4 -> "Thanks a lot!"
        else -> "We love you too!"
    }
    val captionColor by animateColorAsState(
        targetValue = if (stars == 0) TextSecondary else StarAmber,
        animationSpec = tween(200),
        label = "captionColor",
    )
    Text(
        text = caption,
        color = captionColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun GlowGradientButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "glowButtonShine")
    val shineOffset by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "shineOffset",
    )
    val glowPulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glowPulse",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .then(
                if (enabled) {
                    Modifier.shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(14.dp),
                        spotColor = AccentCyan.copy(alpha = 0.40f * glowPulse),
                        ambientColor = AccentCyan.copy(alpha = 0.15f * glowPulse),
                    )
                } else Modifier
            )
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) Brush.linearGradient(listOf(ElectricBlue, AccentCyan))
                else Brush.linearGradient(listOf(FieldBg, FieldBg))
            )
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color(0xFF06131F),
                disabledContainerColor = Color.Transparent,
                disabledContentColor = TextSecondary,
            ),
        ) {
            Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        }
        if (enabled) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val bandWidth = size.width * 0.5f
                        val x = size.width * shineOffset
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.22f),
                                    Color.Transparent,
                                ),
                                start = Offset(x - bandWidth, 0f),
                                end = Offset(x, size.height),
                            )
                        )
                    }
            )
        }
    }
}
