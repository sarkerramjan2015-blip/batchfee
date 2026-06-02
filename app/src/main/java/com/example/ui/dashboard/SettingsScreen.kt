package com.example.ui.dashboard

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
import com.example.data.database.AppDatabase
import com.example.domain.BiometricAuthManager
import com.example.domain.DataExporter
import com.example.domain.ThemePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    var showResetConfirmation by remember { mutableStateOf(false) }
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
                    SettingsRow("Reminder Templates", Icons.Filled.Notifications, onClick = { onNavigate("ReminderTemplatesRoute") })
                    HorizontalDivider(color = BorderSub)
                    SettingsRow("Backup & Restore", Icons.Filled.Backup, onClick = { onNavigate("BackupRestoreRoute") })
                    HorizontalDivider(color = BorderSub)
                    SettingsRow("Export All Data", Icons.Filled.FileDownload, onClick = {
                        scope.launch(Dispatchers.IO) {
                            DataExporter.exportAllToCsv(context, db)
                        }
                    })
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

            // polish: preferences card (non-clickable items)
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
                    SettingsRow("Currency / Locale", Icons.Filled.Language, subtitle = "BDT (Future Update)", onClick = null)
                }
            }

            // polish: styled Reset Demo Data button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, AccentRed.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .background(AccentRed.copy(alpha = 0.1f))
                    .shadow(2.dp, RoundedCornerShape(14.dp), spotColor = AccentRed.copy(alpha = 0.2f))
                    .clickable { showResetConfirmation = true },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = AccentRed, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset Demo Data", color = AccentRed, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.weight(1f))

            // polish: centered version text
            Text(
                "BatchFee v1.0 (Android)",
                color = TextMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            )
        }
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            containerColor = CardBg,
            title = { Text("Reset Demo Data?", color = TextWhite, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will delete all local students, batches, fees, payments, receipts, attendance, reports, and settings data in this app database. This cannot be undone.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        scope.launch(Dispatchers.IO) { db.clearAllTables() }
                    }
                ) {
                    Text("Delete Everything", color = AccentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
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
