package com.batchfee.edu.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BgColor = Color(0xFF0F0F14)
private val CardBg = Color(0xFF1A1A24)
private val TextWhite = Color(0xFFF0F0F0)
private val TextMuted = Color(0xFF8888A0)
private val AccentCyan = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)

@Composable
fun ForceUpdateScreen(requiredVersion: Int) {
    val context = LocalContext.current
    val packageName = context.packageName

    val infiniteTransition = rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Animated icon
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(listOf(ElectricBlue, AccentCyan))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(52.dp)
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "Update Required",
                color = TextWhite,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "A new version of BatchFee is available. Please update to continue using the app.",
                color = TextMuted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(32.dp))

            // Required version badge
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Required Version", color = TextMuted, fontSize = 11.sp)
                        Text(
                            "v$requiredVersion",
                            color = AccentCyan,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(36.dp))

            // Update button
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                        setPackage("com.android.vending")
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        // Fallback to browser
                        val fallback = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                        }
                        context.startActivity(fallback)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentCyan.copy(alpha = pulse)
                )
            ) {
                Text(
                    "Update Now",
                    color = Color.Black,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Online-only operation · You must update to proceed",
                color = TextMuted.copy(alpha = 0.5f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

