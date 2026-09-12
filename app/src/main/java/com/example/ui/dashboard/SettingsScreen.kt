package com.batchfee.edu.ui.dashboard

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.database.AppDatabase
import com.batchfee.edu.data.firestore.SmsMessageReport
import com.batchfee.edu.data.firestore.SmsMessageStatus
import com.batchfee.edu.data.firestore.SmsPackage
import com.batchfee.edu.data.firestore.SmsRechargeRequest
import com.batchfee.edu.data.firestore.SmsWalletSyncHelper
import com.batchfee.edu.data.firestore.SmsWalletState
import com.batchfee.edu.BuildConfig
import com.batchfee.edu.domain.BiometricAuthManager
import com.batchfee.edu.domain.DataExporter
import com.batchfee.edu.domain.ExportFormat
import com.batchfee.edu.domain.ExportSection
import com.batchfee.edu.domain.SessionManager
import com.batchfee.edu.domain.ThemePreferences
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Premium palette (matching other polished screens) ───────────
private val BgColor      = Color(0xFF07111F)
private val CardBg        = Color(0xFF0F172A)
private val BorderSub     = Color(0xFF1E293B)
private val Cyan          = Color(0xFF22D3EE)
private val ElectricBlue  = Color(0xFF3B82F6)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextMuted     = Color(0xFF94A3B8)
private val AccentRed     = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val isDark by ThemePreferences.isDarkMode.collectAsState()
    val currentDark = isDark ?: true
    var biometricEnabled by remember { mutableStateOf(BiometricAuthManager.isEnabled(context)) }
    var showExportDialog by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = BgColor, // polish: navy background
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()) // polish: scroll support
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // polish: navigation section card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .shadow(2.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Column {
                    SettingsRow("Subscription & Billing", Icons.Filled.WorkspacePremium, onClick = { onNavigate("BillingRoute") })
                    HorizontalDivider(color = BorderSub)
                    SettingsRow("Student Registration", Icons.Filled.PersonAdd, onClick = { onNavigate("StudentRegistrationRoute") })
                    HorizontalDivider(color = BorderSub)
                    SettingsRow("Export All Data", Icons.Filled.FileDownload, onClick = {
                        showExportDialog = true
                    })
                }
            }

            // Join Our Community card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .shadow(2.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                var communityMenuOpen by remember { mutableStateOf(false) }
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { communityMenuOpen = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Groups, null, tint = Cyan, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Join Our Community", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Connect with other institutes", color = TextMuted, fontSize = 11.sp)
                        }
                        Icon(Icons.Filled.Add, contentDescription = "Open community options", tint = Cyan, modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(
                        expanded = communityMenuOpen,
                        onDismissRequest = { communityMenuOpen = false },
                        containerColor = CardBg
                    ) {
                        DropdownMenuItem(
                            text = { Text("WhatsApp Support Group", color = TextWhite, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Filled.Chat, null, tint = Cyan, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                communityMenuOpen = false
                                openCommunityLink(
                                    context,
                                    "https://chat.whatsapp.com/HjQjkLDyEy57EGG4WU3HI7?s=sh&p=a&mlu=4&ilr=4"
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Facebook Community Group", color = TextWhite, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Filled.Groups, null, tint = ElectricBlue, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                communityMenuOpen = false
                                openCommunityLink(context, "https://www.facebook.com/share/g/19DDAe2dYv/")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Facebook Page", color = TextWhite, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Filled.Info, null, tint = Cyan, modifier = Modifier.size(20.dp)) },
                            onClick = {
                                communityMenuOpen = false
                                openCommunityLink(context, "https://www.facebook.com/profile.php?id=61590829462013")
                            }
                        )
                    }
                }
            }

            // Theme toggle card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .shadow(2.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Palette, null, tint = Cyan, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Dark Mode", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(if (currentDark) "Currently dark" else "Currently light", color = TextMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = currentDark,
                        onCheckedChange = { ThemePreferences.setDarkMode(context, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Cyan, checkedTrackColor = Cyan.copy(alpha = 0.3f))
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .shadow(2.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderSub)
            ) {
                val availabilityMessage = BiometricAuthManager.availabilityMessage(context)
                SettingsSwitchRow(
                    title = "Fingerprint Login",
                    icon = Icons.Filled.Fingerprint,
                    subtitle = when {
                        biometricEnabled -> "Enabled for this account"
                        availabilityMessage != null -> availabilityMessage
                        else -> "Use fingerprint from the login screen"
                    },
                    checked = biometricEnabled,
                    enabled = availabilityMessage == null || biometricEnabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            val activity = BiometricAuthManager.findFragmentActivity(context)
                            when {
                                availabilityMessage != null -> {
                                    scope.launch { snackbarHostState.showSnackbar(availabilityMessage) }
                                }
                                activity == null -> {
                                    scope.launch { snackbarHostState.showSnackbar("Fingerprint setup needs an active app screen.") }
                                }
                                else -> {
                                    BiometricAuthManager.showPrompt(
                                        activity = activity,
                                        title = "Enable Fingerprint Login",
                                        subtitle = "Confirm your fingerprint for this BatchFee account",
                                        negativeButtonText = "Cancel",
                                        onSuccess = {
                                            val error = BiometricAuthManager.enableForCurrentSession(context)
                                            if (error == null) {
                                                biometricEnabled = true
                                                scope.launch { snackbarHostState.showSnackbar("Fingerprint login enabled.") }
                                            } else {
                                                scope.launch { snackbarHostState.showSnackbar(error) }
                                            }
                                        },
                                        onError = { message ->
                                            scope.launch { snackbarHostState.showSnackbar(message) }
                                        }
                                    )
                                }
                            }
                        } else {
                            BiometricAuthManager.disable(context)
                            biometricEnabled = false
                            scope.launch { snackbarHostState.showSnackbar("Fingerprint login disabled.") }
                        }
                    }
                )
            }

            // Multi-tenant SMS wallet and recharge. Wallet counters are
            // read-only here; the trusted callable is the only writer.
            val instituteId by SessionManager.currentInstituteId.collectAsState()
            var wallet by remember { mutableStateOf<SmsWalletState?>(null) }
            var rechargeRequests by remember { mutableStateOf<List<SmsRechargeRequest>>(emptyList()) }
            var smsPackages by remember { mutableStateOf<List<SmsPackage>>(emptyList()) }
            var showSmsPlans by remember { mutableStateOf(true) }
            var showRechargeDialog by remember { mutableStateOf(false) }
            var showSmsReport by remember { mutableStateOf(false) }
            val isAdmin = SessionManager.isAdmin()
            LaunchedEffect(instituteId) {
                val resolvedInstituteId = instituteId
                if (isAdmin && !resolvedInstituteId.isNullOrBlank()) {
                    wallet = runCatching { SmsWalletSyncHelper.ensureWalletInitialized(resolvedInstituteId) }
                        .getOrNull()
                    rechargeRequests = runCatching { SmsWalletSyncHelper.myRechargeRequests() }
                        .getOrDefault(emptyList())
                    smsPackages = runCatching { SmsWalletSyncHelper.listPackages() }
                        .getOrDefault(emptyList())
                }
            }
            val resolvedInstituteId = instituteId
            if (isAdmin && !resolvedInstituteId.isNullOrBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .shadow(2.dp, RoundedCornerShape(14.dp), spotColor = Cyan.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, BorderSub)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Sms, null, tint = Cyan, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("SMS Wallet & Recharge", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("Balance, usage, packages and top-up", color = TextMuted, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        val state = wallet ?: SmsWalletState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0B1B2E))
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(Modifier.fillMaxWidth()) {
                                WalletStat("Remaining", state.smsBalance.toString(), Modifier.weight(1f))
                                WalletStat("Used today", state.smsUsedToday.toString(), Modifier.weight(1f))
                            }
                            HorizontalDivider(color = BorderSub.copy(alpha = 0.75f))
                            Row(Modifier.fillMaxWidth()) {
                                WalletStat("This month", state.smsUsedThisMonth.toString(), Modifier.weight(1f))
                                WalletStat("Lifetime used", state.totalSmsUsed.toString(), Modifier.weight(1f))
                            }
                        }
                        Text(
                            "Total purchased: ${state.totalSmsPurchased} SMS credits",
                            color = TextMuted,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showSmsPlans = !showSmsPlans }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Storefront, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "SMS packages & price list",
                                color = TextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (smsPackages.isEmpty()) "Loading..." else "${smsPackages.size} packages",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                            Icon(
                                if (showSmsPlans) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (showSmsPlans) {
                            var currentLayer = ""
                            smsPackages.forEach { pkg ->
                                if (pkg.layer != currentLayer) {
                                    currentLayer = pkg.layer
                                    Text(
                                        currentLayer.uppercase(),
                                        color = Cyan,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                    )
                                }
                                val ratePaisa = if (pkg.smsCount > 0) pkg.baseAmount * 100.0 / pkg.smsCount else 0.0
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(pkg.name, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${pkg.smsCount} SMS · ≈ ${"%.1f".format(ratePaisa)} paisa/SMS",
                                            color = TextMuted,
                                            fontSize = 9.sp
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            "BDT ${"%.0f".format(pkg.baseAmount)}",
                                            color = Color(0xFFF59E0B),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text("Pay ${"%.0f".format(pkg.payableAmount)}", color = TextMuted, fontSize = 9.sp)
                                    }
                                }
                            }
                            Text(
                                "Recharge payable includes the ${if (smsPackages.isNotEmpty()) smsPackages.first().chargePercent else "1.8"}% processing charge.",
                                color = TextMuted,
                                fontSize = 9.sp
                            )
                            Text(
                                "Usage counters show BatchFee automatic SMS credits; phone-carrier hand-offs remain in SMS Report.",
                                color = TextMuted,
                                fontSize = 9.sp,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showRechargeDialog = true },
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, Cyan),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Top Up SMS", color = Cyan, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { showSmsReport = true },
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, ElectricBlue),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("SMS Report", color = ElectricBlue, fontWeight = FontWeight.Bold)
                            }
                        }
                        rechargeRequests.take(3).forEach { request ->
                            Spacer(Modifier.height(8.dp))
                            RechargeStatusRow(request)
                        }
                    }
                }
            }

            if (showRechargeDialog && !resolvedInstituteId.isNullOrBlank()) {
                SmsRechargeDialog(
                    onDismiss = { showRechargeDialog = false },
                    onSubmitted = { request ->
                        showRechargeDialog = false
                        rechargeRequests = listOf(request) + rechargeRequests
                        scope.launch {
                            wallet = runCatching { SmsWalletSyncHelper.ensureWalletInitialized(resolvedInstituteId) }
                                .getOrNull()
                            snackbarHostState.showSnackbar("Recharge request submitted. Pending admin approval.")
                        }
                    }
                )
            }

            if (showSmsReport) {
                SmsReportDialog(onDismiss = { showSmsReport = false })
            }

            if (showExportDialog) {
                ExportDataDialog(
                    onDismiss = { showExportDialog = false },
                    onExport = { sections, format ->
                        showExportDialog = false
                        scope.launch {
                            runCatching { DataExporter.exportData(context, db, sections, format) }
                                .onSuccess { fileName ->
                                    snackbarHostState.showSnackbar("Export ready: $fileName")
                                }
                                .onFailure { error ->
                                    snackbarHostState.showSnackbar(error.message ?: "Could not export data. Please try again.")
                                }
                        }
                    }
                )
            }

            // polish: centered version text
            Text(
                "BatchFee v${BuildConfig.VERSION_NAME} (Android)",
                color = TextMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            )
        }
    }

}

private fun openCommunityLink(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(context, "No app found to open this link.", Toast.LENGTH_SHORT).show()
    }
}

// polish: reusable settings row — clickable rows get a chevron and ripple
@Composable
private fun SettingsRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    val mod = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 14.dp, vertical = 12.dp)

    Row(modifier = mod, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Cyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = TextMuted, fontSize = 11.sp)
            }
        }
        if (onClick != null) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled || checked) Cyan else TextMuted.copy(alpha = 0.55f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled || checked,
            colors = SwitchDefaults.colors(checkedThumbColor = Cyan, checkedTrackColor = Cyan.copy(alpha = 0.3f))
        )
    }
}

@Composable
private fun WalletStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun SmsMethodOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = Cyan,
                unselectedColor = TextMuted.copy(alpha = 0.6f),
                disabledSelectedColor = Cyan.copy(alpha = 0.5f),
                disabledUnselectedColor = TextMuted.copy(alpha = 0.35f)
            )
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun RechargeStatusRow(request: SmsRechargeRequest) {
    val (statusLabel, statusColor) = when (request.status) {
        "approved" -> "Approved" to Color(0xFF22C55E)
        "rejected" -> "Rejected" to AccentRed
        else -> "Pending approval" to Color(0xFFF59E0B)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0B1B2E))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${request.packageName} · ${request.smsCount} SMS · BDT ${"%.0f".format(request.payableAmount)}",
                color = TextWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                request.paymentMethod.uppercase().replaceFirstChar { it.uppercase() } +
                    if (request.senderPhone.isNotBlank()) " · ${request.senderPhone}" else "",
                color = TextMuted,
                fontSize = 10.sp
            )
        }
        Text(statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SmsRechargeDialog(
    onDismiss: () -> Unit,
    onSubmitted: (SmsRechargeRequest) -> Unit
) {
    val scope = rememberCoroutineScope()
    var packages by remember { mutableStateOf<List<SmsPackage>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<SmsPackage?>(null) }
    var paymentMethod by remember { mutableStateOf("bkash") }
    var senderPhone by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { SmsWalletSyncHelper.listPackages() }
            .onSuccess { packages = it }
            .onFailure { loadError = it.message ?: "Could not load packages." }
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        containerColor = CardBg,
        title = {
            Text(
                if (selected == null) "Recharge SMS" else "Confirm recharge",
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val pkg = selected
                if (pkg == null) {
                    loadError?.let { error ->
                        Text(error, color = AccentRed, fontSize = 12.sp)
                    }
                    val loaded = packages
                    if (loaded == null) {
                        Text("Loading packages...", color = TextMuted, fontSize = 12.sp)
                    } else {
                        var lastLayer: String? = null
                        loaded.forEach { item ->
                            if (item.layer != lastLayer) {
                                lastLayer = item.layer
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    item.layer.uppercase(),
                                    color = Cyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            SmsPackageRow(item, onClick = { selected = item; submitError = null })
                        }
                    }
                } else {
                    Text(
                        "${pkg.name} · ${pkg.smsCount} SMS",
                        color = TextWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Base amount: BDT ${"%.0f".format(pkg.baseAmount)}", color = TextMuted, fontSize = 12.sp)
                    Text(
                        "Charge (${pkg.chargePercent}%): BDT ${"%.0f".format(pkg.chargeAmount)}",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        "Total payable: BDT ${"%.0f".format(pkg.payableAmount)}",
                        color = Color(0xFFF59E0B),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Payment method", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    SmsMethodOption(
                        title = "bKash (Send Money)",
                        subtitle = "Pay to the BatchFee bKash number shown after approval.",
                        selected = paymentMethod == "bkash",
                        enabled = !submitting,
                        onSelect = { paymentMethod = "bkash" }
                    )
                    SmsMethodOption(
                        title = "Nagad",
                        subtitle = "Pay to the BatchFee Nagad number shown after approval.",
                        selected = paymentMethod == "nagad",
                        enabled = !submitting,
                        onSelect = { paymentMethod = "nagad" }
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = senderPhone,
                        onValueChange = { senderPhone = it; submitError = null },
                        label = { Text("Your ${if (paymentMethod == "bkash") "bKash" else "Nagad"} number", color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !submitting,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Cyan,
                            unfocusedBorderColor = BorderSub,
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite
                        )
                    )
                    Text(
                        "Pay exactly BDT ${"%.0f".format(pkg.payableAmount)} including the ${pkg.chargePercent}% charge, then submit for admin approval.",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    submitError?.let { error ->
                        Spacer(Modifier.height(6.dp))
                        Text(error, color = AccentRed, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            val pkg = selected
            when {
                pkg == null -> TextButton(onClick = onDismiss, enabled = !submitting) {
                    Text("Cancel", color = TextMuted)
                }
                else -> {
                    TextButton(onClick = onDismiss, enabled = !submitting) { Text("Back", color = TextMuted) }
                    Button(
                        onClick = {
                            if (submitting) return@Button
                            if (senderPhone.isBlank()) {
                                submitError = "Enter your payment phone number."
                                return@Button
                            }
                            submitting = true
                            scope.launch {
                                runCatching {
                                    SmsWalletSyncHelper.submitRechargeRequest(pkg.packageId, paymentMethod, senderPhone.trim())
                                }
                                    .onSuccess { request ->
                                        submitting = false
                                        onSubmitted(request)
                                    }
                                    .onFailure { error ->
                                        submitting = false
                                        submitError = error.message ?: "Could not submit the recharge request."
                                    }
                            }
                        },
                        enabled = !submitting,
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan)
                    ) {
                        Text(if (submitting) "Submitting..." else "Submit Request", color = BgColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = null
    )
}

@Composable
private fun SmsPackageRow(pkg: SmsPackage, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0B1B2E))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${pkg.name} — BDT ${"%.0f".format(pkg.baseAmount)}",
                color = TextWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text("${pkg.smsCount} SMS", color = TextMuted, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "BDT ${"%.0f".format(pkg.payableAmount)}",
                color = Color(0xFFF59E0B),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text("incl. ${pkg.chargePercent}% charge", color = TextMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun SmsReportDialog(onDismiss: () -> Unit) {
    var report by remember { mutableStateOf<SmsMessageReport?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(reloadKey) {
        runCatching { SmsWalletSyncHelper.smsReport() }
            .onSuccess { report = it; loadError = null }
            .onFailure { loadError = it.message ?: "Could not load the SMS report." }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SMS Report", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        "Sent vs delivered for the latest messages",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
                TextButton(onClick = { reloadKey++ }) { Text("Refresh", color = Cyan, fontSize = 12.sp) }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                loadError?.let { error ->
                    Text(error, color = AccentRed, fontSize = 12.sp)
                }
                val data = report
                if (data == null) {
                    Text("Loading SMS report...", color = TextMuted, fontSize = 12.sp)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmsReportStat("Sent", data.sent, Color(0xFF3B82F6), Modifier.weight(1f))
                        SmsReportStat("Delivered", data.delivered, Color(0xFF22C55E), Modifier.weight(1f))
                        SmsReportStat("Pending", data.pending, Color(0xFFF59E0B), Modifier.weight(1f))
                        SmsReportStat("Failed", data.failed, AccentRed, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Carrier sends are recorded as Sent once handed to your phone's SMS app. Delivered/Pending/Failed statuses apply to server sends.",
                        color = TextMuted,
                        fontSize = 9.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    if (data.messages.isEmpty()) {
                        Text("No SMS has been recorded yet.", color = TextMuted, fontSize = 12.sp)
                    } else {
                        data.messages.forEach { message ->
                            Spacer(Modifier.height(6.dp))
                            SmsReportRow(message)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Cyan) }
        }
    )
}

@Composable
private fun SmsReportStat(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0B1B2E))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(count.toString(), color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TextMuted, fontSize = 9.sp)
    }
}

@Composable
private fun SmsReportRow(message: SmsMessageStatus) {
    val (statusLabel, statusColor) = when (message.status) {
        "delivered" -> "Delivered" to Color(0xFF22C55E)
        "failed" -> "Failed" to AccentRed
        "pending" -> "Pending" to Color(0xFFF59E0B)
        else -> "Sent" to Color(0xFF3B82F6)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0B1B2E))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(message.purpose.ifBlank { message.recipient }, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                "${message.recipient} · ${message.channel.uppercase().replaceFirstChar { it.uppercase() }} · " +
                    SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(message.createdAtMs)),
                color = TextMuted,
                fontSize = 9.sp
            )
        }
        Text(statusLabel, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ExportDataDialog(
    onDismiss: () -> Unit,
    onExport: (Set<ExportSection>, ExportFormat) -> Unit
) {
    var selectedSections by remember { mutableStateOf(ExportSection.entries.toSet()) }
    var format by remember { mutableStateOf(ExportFormat.EXCEL) }
    var exporting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        containerColor = CardBg,
        title = { Text("Export Data", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Choose what to export", color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (selectedSections.size == ExportSection.entries.size) "All selected" else "${selectedSections.size} selected",
                        color = TextWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = {
                        selectedSections = if (selectedSections.size == ExportSection.entries.size) emptySet() else ExportSection.entries.toSet()
                    }) {
                        Text(
                            if (selectedSections.size == ExportSection.entries.size) "Clear all" else "Select all",
                            color = Cyan,
                            fontSize = 12.sp
                        )
                    }
                }
                ExportSection.entries.forEach { section ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selectedSections = if (section in selectedSections) selectedSections - section else selectedSections + section
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = section in selectedSections,
                            onCheckedChange = { checked ->
                                selectedSections = if (checked) selectedSections + section else selectedSections - section
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Cyan, uncheckedColor = TextMuted)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(section.label, color = TextWhite, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Choose format", color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                ExportFormat.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { format = option }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = format == option,
                            onClick = { format = option },
                            colors = RadioButtonDefaults.colors(selectedColor = Cyan, unselectedColor = TextMuted.copy(alpha = 0.6f))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(option.label, color = TextWhite, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedSections.isEmpty() || exporting) return@Button
                    exporting = true
                    onExport(selectedSections, format)
                },
                enabled = selectedSections.isNotEmpty() && !exporting,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan)
            ) {
                Text(if (exporting) "Exporting..." else "Export", color = BgColor, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !exporting) { Text("Cancel", color = TextMuted) }
        }
    )
}

