package com.batchfee.edu.ui.pricing

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.data.models.SubscriptionPlanEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.Dispatchers
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.batchfee.edu.data.firestore.InstituteCacheRefreshManager
import com.batchfee.edu.data.repository.SubscriptionRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.roundToLong

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

// The published owner-facing catalogue. Room/Firestore may provide the same
// records (and then can update their price/details), but an old partial cache
// must never hide these plans from the pricing screen.
private val publishedPricingPlans = listOf(
    BatchFeePlan("basic", "Basic", 50, "50 Students", 199.0),
    BatchFeePlan("standard", "Standard", 100, "100 Students", 299.0),
    BatchFeePlan("spark", "Spark", 150, "150 Students", 399.0),
    BatchFeePlan("grow", "Grow", 200, "200 Students", 499.0),
    BatchFeePlan("pro", "Pro", 250, "250 Students", 599.0, isPopular = true),
    BatchFeePlan("elite", "Elite", 300, "300 Students", 699.0),
    BatchFeePlan("prime", "Prime", 350, "350 Students", 799.0),
    BatchFeePlan("max", "Max", 400, "400 Students", 899.0),
    BatchFeePlan("ultra", "Ultra", 450, "450 Students", 999.0),
    BatchFeePlan("scale", "Scale", 500, "500 Students", 1099.0, isPremium = true)
)

// ── ViewModel ───────────────────────────────────────────────────
class PricingViewModel(private val db: AppDatabase) : ViewModel() {
    private val _plans = MutableStateFlow<List<BatchFeePlan>>(emptyList())
    val plans = _plans.asStateFlow()

    private val _selectedDuration = MutableStateFlow(0) // 0=1M, 1=6M, 2=1Y
    val selectedDuration = _selectedDuration.asStateFlow()

    private val publicPlanIds = setOf(
        "basic", "standard", "spark", "grow", "pro", "elite", "prime", "max", "ultra", "scale"
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val cachedPlanIds = db.subscriptionPlanDao().getAllPlans().firstOrNull()
                .orEmpty()
                .map { it.id }
                .toSet()
            if (!cachedPlanIds.containsAll(publicPlanIds)) {
                // A fresh or partially synced emulator database may contain only
                // the free-trial record. Restore the complete paid catalog so
                // renewal never opens as a blank/incomplete screen.
                AppDatabase.populateInitialPlans(db.subscriptionPlanDao())
            }
        }
        viewModelScope.launch {
            db.subscriptionPlanDao().getAllPlans().collectLatest { planCatalog ->
                val publicPlansFromCatalog = planCatalog
                    .filter { it.id in publicPlanIds && it.priceBdt > 0.0 }
                    .sortedBy { it.tierLevel }
                    .map { plan ->
                        BatchFeePlan(
                            id = plan.id,
                            name = plan.name,
                            studentCount = plan.maxStudents,
                            studentLabel = "${plan.maxStudents} Students",
                            priceMonthly = plan.priceBdt,
                            isPopular = plan.tag.equals("popular", ignoreCase = true) ||
                                plan.tag.equals("recommended", ignoreCase = true),
                            isPremium = plan.tierLevel >= 3,
                            isEnterprise = plan.maxStudents >= 1_000_000
                        )
                    }
                if (publicPlansFromCatalog.size != publicPlanIds.size) {
                    // CoreDataSyncCoordinator may replace the Room table with
                    // an older remote cache after this screen opens. Reinsert
                    // the published catalog and wait for its next Flow value
                    // instead of briefly rendering an incomplete plan list.
                    withContext(Dispatchers.IO) {
                        AppDatabase.populateInitialPlans(db.subscriptionPlanDao())
                    }
                    _plans.value = publishedPricingPlans
                    return@collectLatest
                }
                _plans.value = publicPlansFromCatalog
            }
        }
    }

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

    fun billingMonths(): Int = when (_selectedDuration.value) {
        1 -> 6
        2 -> 12
        else -> 1
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

class PricingViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PricingViewModel::class.java)) return PricingViewModel(db) as T
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
private val WarningAmber  = Color(0xFFFBBF24)
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
    val viewModel: PricingViewModel = viewModel(factory = PricingViewModelFactory(db))
    val plans by viewModel.plans.collectAsState()
    val selectedDuration by viewModel.selectedDuration.collectAsState()
    val context = LocalContext.current

    // Load institute info for submission
    var instituteName by remember { mutableStateOf("BatchFee Institute") }
    var instituteId by remember { mutableStateOf<String?>(null) }
    var institutePhone by remember { mutableStateOf<String?>(null) }
    var ownerName by remember { mutableStateOf("") }
    var subscriptionStatus by remember { mutableStateOf<String?>(null) }
    var currentPlanId by remember { mutableStateOf<String?>(null) }
    var currentPlanStudentLimit by remember { mutableStateOf<Int?>(null) }
    var currentPeriodEndMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val instId = SessionManager.currentInstituteId.value
        if (instId != null) {
            instituteId = instId
            InstituteCacheRefreshManager.refreshIfStaleInBackground(db, instId)
            val inst = db.instituteDao().getInstituteFlow(instId).firstOrNull()
            inst?.let {
                instituteName = it.name
                institutePhone = it.phone ?: it.whatsappNumber
                ownerName = it.ownerName ?: ""
                subscriptionStatus = it.subscriptionStatus
                currentPlanId = it.currentPlanId
                currentPeriodEndMs = it.currentPeriodEndMs
                currentPlanStudentLimit = db.subscriptionPlanDao()
                    .getPlanById(it.currentPlanId)
                    ?.maxStudents
            }
        }
    }
    val activeStudentCount by produceState(initialValue = 0, key1 = instituteId) {
        val activeInstituteId = instituteId ?: return@produceState
        db.studentDao().countStudents(activeInstituteId).collect { value = it }
    }

    // Payment submission state
    var selectedPaymentMethod by remember { mutableStateOf("bkash") }
    var senderPhone by remember { mutableStateOf("") }
    var senderPhoneError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitSuccess by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var selectedPlanId by remember { mutableStateOf<String?>(null) }
    var showPaymentDialog by remember { mutableStateOf(false) }
    var showPaymentConfirmation by remember { mutableStateOf(false) }
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

            if (instituteId != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Cyan.copy(alpha = 0.10f))
                        .border(1.dp, Cyan.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Group,
                        contentDescription = null,
                        tint = Cyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "$activeStudentCount active students",
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (subscriptionStatus == "trial") {
                                "Your trial has unlimited students. Choose a plan that supports all of them."
                            } else {
                                "Only plans that support your current student count can be selected."
                            },
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
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
                    val lowerPlanBeforeRenewal = subscriptionStatus == "active" &&
                        currentPlanId != "plan_free_trial" &&
                        currentPeriodEndMs > System.currentTimeMillis() &&
                        currentPlanStudentLimit != null &&
                        plan.studentCount < currentPlanStudentLimit!!
                    val isEligible = plan.studentCount >= activeStudentCount && !lowerPlanBeforeRenewal
                    val price = remember(selectedDuration) { viewModel.priceFor(plan) }
                    val durationLabel = remember(selectedDuration) { viewModel.durationLabel() }
                    val durationMonths = remember(selectedDuration) { viewModel.billingMonths() }
                    Box(modifier = Modifier
                        .then(if (selectedPlanId == plan.id) Modifier.border(2.dp, Cyan, RoundedCornerShape(16.dp)) else Modifier)
                    ) {
                        PlanCard(
                            plan = plan,
                            price = price,
                            durationLabel = durationLabel,
                            durationMonths = durationMonths,
                            isSelected = selectedPlanId == plan.id,
                            isEligible = isEligible,
                            unavailableLabel = if (lowerPlanBeforeRenewal) "At renewal" else null,
                            onChoose = {
                                selectedPlanId = plan.id
                                senderPhone = ""
                                senderPhoneError = null
                                submitSuccess = false
                                submitError = null
                                showPaymentConfirmation = false
                                showPaymentDialog = true
                            }
                        )
                    }
                }
            }

            if (plans.isEmpty()) {
                Text(
                    text = "Loading available plans…",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )
            }

            val largestSelfServePlan = plans.filter { !it.isEnterprise }.maxOfOrNull { it.studentCount }
            if (largestSelfServePlan != null && activeStudentCount > largestSelfServePlan) {
                Text(
                    text = "Your institute has more than $largestSelfServePlan active students. Contact support for a plan that fits your institute.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = WarningAmber,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
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

                            OutlinedTextField(
                                value = senderPhone,
                                onValueChange = {
                                    if (it.length <= 20 && it.all { c ->
                                        c.isDigit() || c == '+' || c == '-' || c == ' ' || c == '(' || c == ')'
                                    }) {
                                        senderPhone = it
                                        senderPhoneError = null
                                    }
                                },
                                label = { Text("Sending number", color = TextMuted) },
                                placeholder = { Text("e.g. 01712345678", color = TextMuted.copy(alpha = 0.5f)) },
                                supportingText = {
                                    Text(
                                        senderPhoneError ?: "Use the number you paid from. It helps us verify your payment.",
                                        color = if (senderPhoneError == null) TextMuted else AccentRed,
                                        fontSize = 10.sp
                                    )
                                },
                                isError = senderPhoneError != null,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null, tint = Cyan) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite,
                                    focusedBorderColor = Cyan,
                                    unfocusedBorderColor = BorderSub,
                                    errorBorderColor = AccentRed
                                )
                            )

                            // Error / Success
                            if (submitError != null) {
                                Text(submitError!!, color = AccentRed, fontSize = 12.sp)
                            }
                            Text("You will review the plan, student access and payment details before sending.", color = Cyan.copy(alpha = 0.85f), fontSize = 11.sp)
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (normalizeBangladeshiMobileForSubmission(senderPhone) == null) {
                                    senderPhoneError = "Enter a valid Bangladeshi sending number."
                                } else {
                                    senderPhoneError = null
                                    submitError = null
                                    showPaymentDialog = false
                                    showPaymentConfirmation = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Continue", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPaymentDialog = false }, enabled = !isSubmitting) {
                            Text("Close", color = TextMuted)
                        }
                    }
                )
            }

            if (showPaymentConfirmation && selectedPlanId != null) {
                val selPlan = plans.find { it.id == selectedPlanId } ?: return@Scaffold
                val selPrice = remember(selectedDuration) { viewModel.priceFor(selPlan) }
                val durationMonths = when (selectedDuration) { 0 -> 1; 1 -> 6; 2 -> 12; else -> 1 }
                val normalizedSenderPhone = normalizeBangladeshiMobileForSubmission(senderPhone).orEmpty()
                val paymentRequestOperationId = remember(
                    selectedPlanId,
                    durationMonths,
                    selectedPaymentMethod,
                    normalizedSenderPhone
                ) { UUID.randomUUID().toString() }
                val accessPeriod = when (durationMonths) {
                    12 -> "1 year from approval"
                    6 -> "6 months from approval"
                    else -> "1 month from approval"
                }

                AlertDialog(
                    onDismissRequest = { if (!isSubmitting) showPaymentConfirmation = false },
                    containerColor = CardBg,
                    shape = RoundedCornerShape(18.dp),
                    title = {
                        if (submitSuccess) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Green.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Green, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Text("Request sent", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Text("Confirm subscription request", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        if (submitSuccess) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Your request is now visible to the Super Admin for review.", color = Green, fontSize = 13.sp)
                                Text("${selPlan.name} will activate after the payment is verified.", color = TextMuted, fontSize = 12.sp)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Review everything before sending it for approval.", color = TextMuted, fontSize = 12.sp)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(CardBgAlt)
                                        .border(1.dp, BorderSub, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    PaymentConfirmationRow("Plan", selPlan.name, Cyan)
                                    PaymentConfirmationRow("Student access", "Up to ${selPlan.studentCount} students", SkyBlue)
                                    PaymentConfirmationRow("Access period", accessPeriod, Green)
                                    PaymentConfirmationRow("Amount", "BDT ${"%.0f".format(selPrice)}", TextWhite)
                                    PaymentConfirmationRow("Payment", selectedPaymentMethod.replaceFirstChar { it.uppercase() }, Cyan)
                                    PaymentConfirmationRow("Sent from", normalizedSenderPhone, WarningAmber)
                                }
                                submitError?.let { Text(it, color = AccentRed, fontSize = 12.sp) }
                            }
                        }
                    },
                    confirmButton = {
                        if (submitSuccess) {
                            Button(
                                onClick = {
                                    showPaymentConfirmation = false
                                    showPaymentDialog = false
                                    senderPhone = ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Green),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("Done", color = Color.White, fontWeight = FontWeight.Bold) }
                        } else {
                            Button(
                                onClick = {
                                    if (isSubmitting) return@Button
                                    if (instituteId == null) {
                                        submitError = "Institute not found. Try restarting the app."
                                        return@Button
                                    }
                                    if (normalizedSenderPhone.isBlank()) {
                                        submitError = "Enter a valid Bangladeshi sending number."
                                        showPaymentConfirmation = false
                                        showPaymentDialog = true
                                        return@Button
                                    }
                                    isSubmitting = true
                                    submitError = null
                                    scope.launch {
                                        try {
                                            SubscriptionRepository().submitRequest(
                                                instituteId = instituteId!!,
                                                requestedPlanId = selPlan.id,
                                                durationMonths = durationMonths,
                                                paymentMethod = selectedPaymentMethod,
                                                senderPhone = normalizedSenderPhone,
                                                operationId = paymentRequestOperationId
                                            )
                                            submitSuccess = true
                                        } catch (error: Exception) {
                                            submitError = error.message ?: "Submission failed. Try again."
                                        } finally {
                                            isSubmitting = false
                                        }
                                    }
                                },
                                enabled = !isSubmitting,
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (isSubmitting) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Confirm & Submit", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    },
                    dismissButton = {
                        if (!submitSuccess) {
                            TextButton(
                                onClick = {
                                    showPaymentConfirmation = false
                                    showPaymentDialog = true
                                },
                                enabled = !isSubmitting
                            ) { Text("Back", color = TextMuted) }
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
    durationMonths: Int,
    isSelected: Boolean = false,
    isEligible: Boolean,
    unavailableLabel: String? = null,
    onChoose: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "planGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.26f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    val shineProgress by infiniteTransition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(3400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "planShine"
    )
    val selectedScale by animateFloatAsState(
        targetValue = if (isSelected) 1.015f else 1f,
        animationSpec = tween(220),
        label = "selectedPlanScale"
    )

    val borderColors = if (plan.isPopular) {
        listOf(ElectricBlue, Cyan)
    } else if (plan.isPremium) {
        listOf(VioletBlue, SkyBlue)
    } else {
        listOf(Color.Transparent, Color.Transparent)
    }

    val showGlowBorder = plan.isPopular || plan.isPremium || isSelected
    val accentColor = when {
        plan.isPremium -> VioletBlue
        plan.isPopular || isSelected -> Cyan
        else -> SkyBlue
    }
    val cardShape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .width(232.dp)
            .graphicsLayer {
                scaleX = selectedScale
                scaleY = selectedScale
            }
            .clip(cardShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        if (plan.isPremium) Color(0xFF191B45) else CardBgAlt,
                        CardBg
                    )
                )
            )
            .then(
                if (showGlowBorder) Modifier.border(
                    width = if (isSelected) 2.dp else 1.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            (if (isSelected) Cyan else borderColors[0]).copy(alpha = glowAlpha),
                            (if (isSelected) ElectricBlue else borderColors[1]).copy(alpha = glowAlpha),
                            (if (isSelected) Cyan else borderColors[0]).copy(alpha = glowAlpha)
                        )
                    ),
                    shape = cardShape
                )
                else Modifier.border(1.dp, BorderSub, cardShape)
            )
    ) {
        // A low-contrast sheen makes the catalogue feel premium without obscuring text.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = if (plan.isPopular || plan.isPremium) 0.10f else 0.045f),
                            Color.Transparent
                        ),
                        start = androidx.compose.ui.geometry.Offset(232f * shineProgress - 120f, 0f),
                        end = androidx.compose.ui.geometry.Offset(232f * shineProgress + 80f, 82f)
                    )
                )
        )
        Column(
            modifier = Modifier.padding(14.dp)
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

            // Plan name
            Text(
                plan.name,
                color = TextWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Everything your institute needs",
                color = TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))

            // These three items are the actual subscription capacity contract.
            PlanEntitlementRow(
                icon = Icons.Filled.Group,
                title = "Student seats",
                value = plan.studentLabel,
                accent = SkyBlue
            )
            Spacer(Modifier.height(6.dp))
            PlanEntitlementRow(
                icon = Icons.Filled.Groups,
                title = "Batches",
                value = "Unlimited",
                accent = Cyan
            )
            Spacer(Modifier.height(6.dp))
            PlanEntitlementRow(
                icon = Icons.Filled.Badge,
                title = "Staff",
                value = "Unlimited",
                accent = Green
            )
            Spacer(Modifier.height(12.dp))

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
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = Cyan,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Per student: BDT ${formatMonthlyPerStudentPrice(price, plan.studentCount, durationMonths)} /month",
                    color = Cyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(10.dp))

            Text("All core tools included", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            allPlanFeatures.take(3).forEach { (label, icon) ->
                Row(
                    modifier = Modifier.padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = null, tint = accentColor.copy(alpha = 0.82f), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(label, color = TextMuted, fontSize = 10.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // CTA Button
            val hasProminentCta = plan.isPopular || plan.isPremium ||
                plan.id == "basic" || plan.id == "standard"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        brush = if (hasProminentCta && isEligible)
                            Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                        else
                            Brush.horizontalGradient(listOf(CardBgAlt, CardBg))
                    )
                    .then(
                        if (!hasProminentCta || !isEligible)
                            Modifier.border(1.dp, BorderSub, RoundedCornerShape(10.dp))
                        else Modifier
                    )
                    .then(if (isEligible) Modifier.clickable { onChoose() } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isEligible) "Choose Plan" else (unavailableLabel ?: "Not eligible"),
                    color = if (hasProminentCta && isEligible) Color.White else TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun PlanEntitlementRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    accent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.09f))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(title, color = TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text(value, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PaymentConfirmationRow(label: String, value: String, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextMuted, fontSize = 11.sp)
        Text(value, color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

private fun normalizeBangladeshiMobileForSubmission(value: String): String? {
    var digits = value.filter(Char::isDigit)
    if (digits.startsWith("880")) digits = "0${digits.drop(3)}"
    if (digits.startsWith("1") && digits.length == 10) digits = "0$digits"
    return if (Regex("^01[3-9]\\d{8}$").matches(digits)) "+88$digits" else null
}

private fun formatPrice(price: Double): String {
    return price.roundToLong().toString()
}

private fun formatMonthlyPerStudentPrice(
    price: Double,
    studentCount: Int,
    durationMonths: Int
): String {
    if (studentCount <= 0) return "0"
    val amount = BigDecimal.valueOf(price).stripTrailingZeros()
    val divisor = BigDecimal.valueOf(studentCount.toLong() * durationMonths.coerceAtLeast(1))
    return amount.divide(divisor, 2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}

