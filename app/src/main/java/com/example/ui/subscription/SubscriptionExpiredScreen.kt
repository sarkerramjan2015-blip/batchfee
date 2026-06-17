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
import com.example.data.database.AppDatabase
import com.example.data.models.InstituteEntity
import com.example.domain.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgColor = Color(0xFF0F0F14)
private val CardBg = Color(0xFF1A1A24)
private val TextWhite = Color(0xFFF0F0F0)
private val TextMuted = Color(0xFF8888A0)
private val AccentRed = Color(0xFFF87171)
private val AccentCyan = Color(0xFF22D3EE)
private val AccentViolet = Color(0xFFA855F7)
private val ElectricBlue = Color(0xFF3B82F6)
private val WAGreen = Color(0xFF25D366)
private val Teal = Color(0xFF14B8A6)

private const val SUPPORT_WHATSAPP = "+8801518657869"

@Composable
fun SubscriptionExpiredScreen(db: AppDatabase, onRenew: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current

    var instituteName by remember { mutableStateOf("") }
    var planName by remember { mutableStateOf("Free Plan") }
    var expiredDate by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val instId = SessionManager.currentInstituteId.value
        if (instId != null) {
            val inst = withContext(Dispatchers.IO) { db.instituteDao().getInstitute(instId) }
            val plan = withContext(Dispatchers.IO) { db.subscriptionPlanDao().getPlanById(inst?.currentPlanId ?: "") }
            instituteName = inst?.name ?: "BatchFee Institute"
            planName = plan?.name ?: "Free Plan"
            val endMs = inst?.currentPeriodEndMs ?: inst?.trialEndDateMs ?: 0L
            expiredDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(endMs))
        }
    }

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
                        text = instituteName,
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = planName,
                        color = AccentCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Expired: $expiredDate",
                        color = AccentRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF2A2A3A))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Your subscription has ended. You can still log in, but access to all features is restricted. Contact us to reactivate your plan.",
                        color = TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 21.sp
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // Contact Support button (WhatsApp)
            Button(
                onClick = {
                    val message = URLEncoder.encode(
                        "Hello, my subscription for $instituteName ($planName) has expired on $expiredDate. I want to extend.", 
                        "UTF-8"
                    )
                    val url = "https://wa.me/$SUPPORT_WHATSAPP?text=$message"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
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
                        .background(Brush.horizontalGradient(listOf(WAGreen, Teal))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Phone, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Contact Developer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
