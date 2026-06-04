package com.example.ui.subscription

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.example.domain.SessionManager

private val BgColor = Color(0xFF0F0F14)
private val CardBg = Color(0xFF1A1A24)
private val TextWhite = Color(0xFFF0F0F0)
private val TextMuted = Color(0xFF8888A0)
private val AccentRed = Color(0xFFF87171)
private val AccentCyan = Color(0xFF22D3EE)
private val AccentViolet = Color(0xFFA855F7)
private val ElectricBlue = Color(0xFF3B82F6)

private const val SUPPORT_WHATSAPP = "+8801518657869"

@Composable
fun SubscriptionExpiredScreen(onLogout: () -> Unit) {
    val context = LocalContext.current

    BackHandler(enabled = true) {}

    val pulseAnim = rememberInfiniteTransition()
    val glowAlpha by pulseAnim.animateFloat(0.4f, 0.75f, infiniteRepeatable(tween(1800), RepeatMode.Reverse))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(BgColor, Color(0xFF1A1028), BgColor)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(AccentRed.copy(alpha = 0.18f), AccentViolet.copy(alpha = 0.12f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = AccentRed.copy(alpha = glowAlpha),
                    modifier = Modifier.size(42.dp)
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = "Subscription Expired",
                color = TextWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(14.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Your subscription has expired. Please contact our developer team to extend your subscription.",
                        color = TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 21.sp
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = {
                    val url = "https://wa.me/$SUPPORT_WHATSAPP?text=Hello%2C+I+need+to+extend+my+BatchFee+subscription."
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.horizontalGradient(listOf(AccentCyan, ElectricBlue))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.WarningAmber, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Contact Support", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            OutlinedButton(
                onClick = {
                    SessionManager.logout()
                    onLogout()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = ButtonDefaults.outlinedButtonBorder,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
            ) {
                Icon(Icons.Filled.Logout, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Logout", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
        }
    }
}
