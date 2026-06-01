package com.example.ui.pricing

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.domain.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import java.net.URLEncoder

// ── BatchFee Plan Data ──────────────────────────────────────────
data class BatchFeePlan(
    val id: String,
    val name: String,
    val studentCount: Int,
    val studentLabel: String,
    val priceMonthly: Double,
    val isPopular: Boolean = false,
    val isPremium: Boolean = false,
    val isEnterprise: Boolean = false
)

val batchFeePlans = listOf(
    BatchFeePlan("basic",      "Basic",      50,  "50 Students",   199.0),
    BatchFeePlan("standard",   "Standard",  100,  "100 Students",  299.0),
    BatchFeePlan("spark",      "Spark",     150,  "150 Students",  399.0),
    BatchFeePlan("grow",       "Grow",      200,  "200 Students",  499.0),
    BatchFeePlan("pro",        "Pro",       250,  "250 Students",  599.0, isPopular = true),
    BatchFeePlan("elite",      "Elite",     300,  "300 Students",  699.0),
    BatchFeePlan("prime",      "Prime",     350,  "350 Students",  799.0),
    BatchFeePlan("max",        "Max",       400,  "400 Students",  899.0),
    BatchFeePlan("ultra",      "Ultra",     450,  "450 Students",  999.0),
    BatchFeePlan("scale",      "Scale",     500,  "500 Students",  1099.0, isPremium = true),
    BatchFeePlan("enterprise", "Enterprise", Int.MAX_VALUE, "500+ Students", 0.0, isEnterprise = true)
)

// ── ViewModel ───────────────────────────────────────────────────
class PricingViewModel : ViewModel() {
    private val _plans = MutableStateFlow(batchFeePlans)
    val plans = _plans.asStateFlow()

    private val _selectedDuration = MutableStateFlow(0) // 0=1M, 1=6M, 2=1Y
    val selectedDuration = _selectedDuration.asStateFlow()

    fun selectDuration(index: Int) { _selectedDuration.value = index }

    fun priceFor(plan: BatchFeePlan): Double = when (_selectedDuration.value) {
        0 -> plan.priceMonthly
        1 -> plan.priceMonthly * 6.0 * 0.90
        2 -> plan.priceMonthly * 12.0 * 0.80
        else -> plan.priceMonthly
    }

    fun durationLabel(): String = when (_selectedDuration.value) {
        0 -> "/month"
        1 -> "/6 months"
        2 -> "/year"
        else -> "/month"
    }

    fun discountLabel(): String? = when (_selectedDuration.value) {
        0 -> null
        1 -> "Save 10%"
        2 -> "Save 20%"
        else -> null
    }

    fun billingLabel(): String = when (_selectedDuration.value) {
        0 -> "1 Month"
        1 -> "6 Months"
        2 -> "1 Year"
        else -> "1 Month"
    }
}

class PricingViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PricingViewModel::class.java)) return PricingViewModel() as T
        throw IllegalArgumentException()
    }
}

// ── Colors ──────────────────────────────────────────────────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val CardBgAlt     = Color(0xFF111827)
private val BorderSub     = Color(0xFF1E293B)
private val SkyBlue       = Color(0xFF38BDF8)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val VioletBlue    = Color(0xFF6366F1)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val WAGreen       = Color(0xFF25D366)
private val Teal          = Color(0xFF14B8A6)

// ── Feature list for all plans ──────────────────────────────────
private val allPlanFeatures = listOf(
    "Student management" to Icons.Filled.Person,
    "Batch management" to Icons.Filled.Groups,
    "Fee & due tracking" to Icons.Filled.Receipt,
    "Attendance tracking" to Icons.Filled.HowToReg,
    "Staff management" to Icons.Filled.Badge,
    "Reports & reminders" to Icons.Filled.Assessment
)

// ── Screen ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricingScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onSubscribe: (planId: String) -> Unit
) {
    val viewModel: PricingViewModel = viewModel(factory = PricingViewModelFactory())
    val plans by viewModel.plans.collectAsState()
    val selectedDuration by viewModel.selectedDuration.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Load institute name for WhatsApp message (read-only, no logic change)
    var instituteName by remember { mutableStateOf("BatchFee Institute") }
    LaunchedEffect(Unit) {
        val instId = SessionManager.currentInstituteId.value
        if (instId != null) {
            val inst = db.instituteDao().getInstituteFlow(instId).firstOrNull()
            inst?.let { instituteName = it.name }
        }
    }

    val durationOptions = listOf("1 Month", "6 Months", "1 Year")
    val saveLabels = listOf(null, "Save 10%", "Save 20%")

    Scaffold(
        containerColor = BgColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("BatchFee Plans", color = TextWhite, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Header ──────────────────────────────────────────
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Choose Your Plan",
                    color = TextWhite,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Scale your institute with the right plan. All plans include core features.",
                    color = TextMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }

            // ── Billing Duration Selector ───────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBg)
                    .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                durationOptions.forEachIndexed { index, label ->
                    val isSelected = index == selectedDuration
                    val saveLabel = saveLabels[index]
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .then(
                                if (isSelected) Modifier.background(
                                    brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                                )
                                else Modifier.background(Color.Transparent)
                            )
                            .clickable { viewModel.selectDuration(index) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                label,
                                color = if (isSelected) Color.White else TextMuted,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (saveLabel != null) {
                                Text(
                                    saveLabel,
                                    color = if (isSelected) Color.White.copy(alpha = 0.8f) else WAGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Plan Cards (Horizontal Scroll) ──────────────────
            Text(
                "Select a Plan",
                color = TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(10.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(plans.filter { !it.isEnterprise }) { plan ->
                    val price = remember(selectedDuration) { viewModel.priceFor(plan) }
                    val durationLabel = remember(selectedDuration) { viewModel.durationLabel() }
                    val billingLabel = remember(selectedDuration) { viewModel.billingLabel() }
                    PlanCard(
                        plan = plan,
                        price = price,
                        durationLabel = durationLabel,
                        onChoose = {
                            // Open WhatsApp with plan purchase inquiry
                            // Format: multi-line message with plan name, student count, price, billing duration
                            val message = "Hello Developer,\n" +
                                    "I would like to purchase the ${plan.name} plan\n" +
                                    "for ${plan.studentCount} students\n" +
                                    "at BDT ${formatPrice(price)}\n" +
                                    "for $billingLabel."
                            val encoded = URLEncoder.encode(message, "UTF-8")
                            val url = "https://wa.me/8801518657869?text=$encoded"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Enterprise Card ─────────────────────────────────
            val enterprisePlan = plans.find { it.isEnterprise }
            if (enterprisePlan != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(CardBg, CardBgAlt)
                            )
                        )
                        .border(
                            width = 1.5.dp,
                            brush = Brush.horizontalGradient(listOf(ElectricBlue, VioletBlue)),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.Business,
                            contentDescription = null,
                            tint = Cyan,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            enterprisePlan.name,
                            color = TextWhite,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "For institutions with ${enterprisePlan.studentLabel}",
                            color = TextMuted,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Cyan, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Custom institute support", color = TextMuted, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    brush = Brush.horizontalGradient(listOf(WAGreen, Teal))
                                )
                                .clickable {
                                    // Open WhatsApp with institute name in message
                                    val message = "Hello Developer, Institute: $instituteName"
                                    val encoded = URLEncoder.encode(message, "UTF-8")
                                    val url = "https://wa.me/8801518657869?text=$encoded"
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Phone, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Contact Developer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            // ── Footer ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardBg)
                    .border(1.dp, BorderSub, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.SupportAgent, contentDescription = null, tint = WAGreen, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Need help choosing?", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Our team is here to assist you.", color = TextMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = {
                            // Open WhatsApp with institute name in message
                            val message = "Hello Developer, Institute: $instituteName"
                            val encoded = URLEncoder.encode(message, "UTF-8")
                            val url = "https://wa.me/8801518657869?text=$encoded"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, WAGreen.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WAGreen)
                    ) {
                        Icon(Icons.Filled.Phone, contentDescription = null, tint = WAGreen, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Contact Developer", color = WAGreen, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Plan Card ───────────────────────────────────────────────────
@Composable
private fun PlanCard(
    plan: BatchFeePlan,
    price: Double,
    durationLabel: String,
    onChoose: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "planGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    val borderColors = if (plan.isPopular) {
        listOf(ElectricBlue, Cyan)
    } else if (plan.isPremium) {
        listOf(VioletBlue, SkyBlue)
    } else {
        listOf(Color.Transparent, Color.Transparent)
    }

    val showGlowBorder = plan.isPopular || plan.isPremium

    Box(
        modifier = Modifier
            .width(250.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .then(
                if (showGlowBorder) Modifier.border(
                    width = 1.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            borderColors[0].copy(alpha = glowAlpha),
                            borderColors[1].copy(alpha = glowAlpha),
                            borderColors[0].copy(alpha = glowAlpha)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                else Modifier.border(1.dp, BorderSub, RoundedCornerShape(16.dp))
            )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Badge
            if (plan.isPopular || plan.isPremium) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                brush = Brush.horizontalGradient(
                                    if (plan.isPremium) listOf(VioletBlue, SkyBlue)
                                    else listOf(ElectricBlue, Cyan)
                                )
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (plan.isPremium) "Premium" else "Popular",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            } else {
                Spacer(Modifier.height(2.dp))
            }

            // Plan Name + Student Limit
            Text(
                plan.name,
                color = TextWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Group, contentDescription = null, tint = SkyBlue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(plan.studentLabel, color = SkyBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))

            HorizontalDivider(color = BorderSub)
            Spacer(Modifier.height(12.dp))

            // Price
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom
            ) {
                Text("BDT", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
                Spacer(Modifier.width(2.dp))
                Text(
                    formatPrice(price),
                    color = TextWhite,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                durationLabel,
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(14.dp))

            // Feature list
            allPlanFeatures.forEach { (label, icon) ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = null, tint = Cyan.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = TextMuted, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // CTA Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        brush = if (plan.isPopular || plan.isPremium)
                            Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                        else
                            Brush.horizontalGradient(listOf(CardBgAlt, CardBg))
                    )
                    .then(
                        if (!plan.isPopular && !plan.isPremium)
                            Modifier.border(1.dp, BorderSub, RoundedCornerShape(12.dp))
                        else Modifier
                    )
                    .clickable { onChoose() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Choose Plan",
                    color = if (plan.isPopular || plan.isPremium) Color.White else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun formatPrice(price: Double): String {
    return if (price == price.toLong().toDouble()) {
        price.toLong().toString()
    } else {
        "%.0f".format(price)
    }
}
