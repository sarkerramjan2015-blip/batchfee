package com.example.ui.pricing

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.database.AppDatabase
import com.example.domain.SessionManager
import com.example.data.models.SubscriptionPlanEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.example.data.firestore.InstituteCacheRefreshManager
import com.example.data.models.SubscriptionRequest
import com.example.data.repository.SubscriptionRepository
import kotlinx.coroutines.launch
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
private val AccentRed     = Color(0xFFF87171)
private val Green         = Color(0xFF22C55E)

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
    val context = LocalContext.current

    // Load institute info for submission
    var instituteName by remember { mutableStateOf("BatchFee Institute") }
    var instituteId by remember { mutableStateOf<String?>(null) }
    var institutePhone by remember { mutableStateOf<String?>(null) }
    var ownerName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val instId = SessionManager.currentInstituteId.value
        if (instId != null) {
            instituteId = instId
            InstituteCacheRefreshManager.refreshIfStale(db, instId)
            val inst = db.instituteDao().getInstituteFlow(instId).firstOrNull()
            inst?.let {
                instituteName = it.name
                institutePhone = it.phone ?: it.whatsappNumber
                ownerName = it.ownerName ?: ""
            }
        }
    }

    // Payment submission state
    var selectedPaymentMethod by remember { mutableStateOf("bkash") }
    var lastTrxDigits by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitSuccess by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var selectedPlanId by remember { mutableStateOf<String?>(null) }
    var showPaymentDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val durationOptions = listOf("1 Month", "6 Months", "1 Year")
    val saveLabels = listOf(null, "Save 10%", "Save 20%")

    Scaffold(
        containerColor = BgColor,
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
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Choose Your Plan",
                    color = TextWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Scale your institute with the right plan. All plans include core features.",
                    color = TextMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            // ── Billing Duration Selector ───────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardBg)
                    .border(1.dp, BorderSub, RoundedCornerShape(10.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                durationOptions.forEachIndexed { index, label ->
                    val isSelected = index == selectedDuration
                    val saveLabel = saveLabels[index]
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (isSelected) Modifier.background(
                                    brush = Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                                )
                                else Modifier.background(Color.Transparent)
                            )
                            .clickable { viewModel.selectDuration(index) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                label,
                                color = if (isSelected) Color.White else TextMuted,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (saveLabel != null) {
                                Text(
                                    saveLabel,
                                    color = if (isSelected) Color.White.copy(alpha = 0.8f) else WAGreen,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Plan Cards (Horizontal Scroll) ──────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Select a Plan", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Swipe to compare", color = TextMuted.copy(alpha = 0.75f), fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(plans.filter { !it.isEnterprise }) { plan ->
                    val price = remember(selectedDuration) { viewModel.priceFor(plan) }
                    val durationLabel = remember(selectedDuration) { viewModel.durationLabel() }
                    val billingLabel = remember(selectedDuration) { viewModel.billingLabel() }
                    Box(modifier = Modifier
                        .then(if (selectedPlanId == plan.id) Modifier.border(2.dp, Cyan, RoundedCornerShape(16.dp)) else Modifier)
                    ) {
                        PlanCard(
                            plan = plan,
                            price = price,
                            durationLabel = durationLabel,
                            isSelected = selectedPlanId == plan.id,
                            onChoose = {
                                selectedPlanId = plan.id
                                submitSuccess = false
                                submitError = null
                                showPaymentDialog = true
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── Enterprise Card ─────────────────────────────────
            val enterprisePlan = plans.find { it.isEnterprise }
            if (enterprisePlan != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(CardBg, CardBgAlt)
                            )
                        )
                        .border(
                            width = 1.5.dp,
                            brush = Brush.horizontalGradient(listOf(ElectricBlue, VioletBlue)),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.Business,
                            contentDescription = null,
                            tint = Cyan,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            enterprisePlan.name,
                            color = TextWhite,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "For institutions with ${enterprisePlan.studentLabel}",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Cyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Custom institute support", color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
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
                                Icon(Icons.Filled.Phone, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Contact Developer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            // ── Payment Request Dialog ──────────────────────────
            if (showPaymentDialog && selectedPlanId != null) {
                val selPlan = plans.find { it.id == selectedPlanId } ?: return@Scaffold
                val selPrice = remember(selectedDuration) { viewModel.priceFor(selPlan) }
                val selBilling = remember(selectedDuration) { viewModel.billingLabel() }
                val durationMonths = when (selectedDuration) { 0 -> 1; 1 -> 6; 2 -> 12; else -> 1 }

                AlertDialog(
                    onDismissRequest = { if (!isSubmitting) showPaymentDialog = false },
                    containerColor = CardBg,
                    shape = RoundedCornerShape(16.dp),
                    title = {
                        Text("Submit Payment Request", color = TextWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${selPlan.name} · ${selBilling} · BDT ${"%.0f".format(selPrice)}", color = Cyan, fontSize = 13.sp)
                            HorizontalDivider(color = BorderSub)

                            // Payment method chips
                            Text("Payment Method", color = TextMuted, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("bkash" to "bKash", "nagad" to "Nagad").forEach { (id, label) ->
                                    val isSel = selectedPaymentMethod == id
                                    FilterChip(
                                        selected = isSel,
                                        onClick = { selectedPaymentMethod = id },
                                        label = { Text(label, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Cyan.copy(alpha = 0.2f),
                                            selectedLabelColor = Cyan
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            borderColor = if (isSel) Cyan else BorderSub,
                                            selectedBorderColor = Cyan,
                                            enabled = true,
                                            selected = isSel
                                        )
                                    )
                                }
                            }

                            // Payment number with copy
                            val payNumber = if (selectedPaymentMethod == "bkash") "01777408383" else "01518657869"
                            val payLabel = if (selectedPaymentMethod == "bkash") "bKash (Send Money)" else "Nagad"
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CardBgAlt)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(payLabel, color = TextMuted, fontSize = 11.sp)
                                    Text(payNumber, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("number", payNumber))
                                        Toast.makeText(context, "Number copied!", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = Cyan, modifier = Modifier.size(20.dp))
                                }
                            }

                            // Last 4 digits input
                            OutlinedTextField(
                                value = lastTrxDigits,
                                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) lastTrxDigits = it },
                                label = { Text("Last 4 digits of TrxID", color = TextMuted) },
                                placeholder = { Text("e.g. 8X7K", color = TextMuted.copy(alpha = 0.5f)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite,
                                    focusedBorderColor = Cyan,
                                    unfocusedBorderColor = BorderSub
                                )
                            )

                            // Error / Success
                            if (submitError != null) {
                                Text(submitError!!, color = AccentRed, fontSize = 12.sp)
                            }
                            if (submitSuccess) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Green, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Payment submitted. Admin will review and approve your request.", color = Green, fontSize = 12.sp)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                scope.launch {
                                    if (instituteId == null) {
                                        submitError = "Institute not found. Try restarting the app."
                                        return@launch
                                    }
                                    isSubmitting = true
                                    submitError = null
                                    try {
                                        val request = SubscriptionRequest(
                                            requestId = "SR-${System.currentTimeMillis()}",
                                            instituteId = instituteId!!,
                                            instituteName = instituteName,
                                            ownerName = ownerName,
                                            institutePhone = institutePhone,
                                            requestedPlanId = selPlan.id,
                                            durationMonths = durationMonths,
                                            amountPaid = selPrice,
                                            transactionLast4 = lastTrxDigits,
                                            paymentMethod = selectedPaymentMethod,
                                            requestSentAt = System.currentTimeMillis()
                                        )
                                        SubscriptionRepository().submitRequest(request)
                                        submitSuccess = true
                                        lastTrxDigits = ""
                                    } catch (e: Exception) {
                                        submitError = e.message ?: "Submission failed. Try again."
                                    } finally {
                                        isSubmitting = false
                                    }
                                }
                            },
                            enabled = !isSubmitting && lastTrxDigits.length == 4 && !submitSuccess,
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Submit Request", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPaymentDialog = false }, enabled = !isSubmitting) {
                            Text("Close", color = TextMuted)
                        }
                    }
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

// ── Plan Card ───────────────────────────────────────────────────
@Composable
private fun PlanCard(
    plan: BatchFeePlan,
    price: Double,
    durationLabel: String,
    isSelected: Boolean = false,
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
            .width(220.dp)
            .clip(RoundedCornerShape(14.dp))
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
                    shape = RoundedCornerShape(14.dp)
                )
                else Modifier.border(1.dp, BorderSub, RoundedCornerShape(14.dp))
            )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
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
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            if (plan.isPremium) "Premium" else "Popular",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(1.dp))
            }

            // Plan Name + Student Limit
            Text(
                plan.name,
                color = TextWhite,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Group, contentDescription = null, tint = SkyBlue, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(plan.studentLabel, color = SkyBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))

            HorizontalDivider(color = BorderSub)
            Spacer(Modifier.height(9.dp))

            // Price
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom
            ) {
                Text("BDT", color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
                Spacer(Modifier.width(2.dp))
                Text(
                    formatPrice(price),
                    color = TextWhite,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                durationLabel,
                color = TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            // Feature list
            allPlanFeatures.forEach { (label, icon) ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = null, tint = Cyan.copy(alpha = 0.75f), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(label, color = TextMuted, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // CTA Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        brush = if (plan.isPopular || plan.isPremium)
                            Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                        else
                            Brush.horizontalGradient(listOf(CardBgAlt, CardBg))
                    )
                    .then(
                        if (!plan.isPopular && !plan.isPremium)
                            Modifier.border(1.dp, BorderSub, RoundedCornerShape(10.dp))
                        else Modifier
                    )
                    .clickable { onChoose() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Choose Plan",
                    color = if (plan.isPopular || plan.isPremium) Color.White else TextMuted,
                    fontSize = 12.sp,
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
