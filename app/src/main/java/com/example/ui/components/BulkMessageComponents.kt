package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batchfee.edu.data.firestore.SmsWalletState
import com.batchfee.edu.data.firestore.SmsWalletSyncHelper
import com.batchfee.edu.domain.SessionManager
import com.example.domain.BulkMessageController
import kotlinx.coroutines.launch

// Shared dark palette — mirrors the Students / Fees screens.
private val BgColor      = Color(0xFF07111F)
private val CardBg       = Color(0xFF0F172A)
private val CardBgAlt    = Color(0xFF111827)
private val BorderSub    = Color(0xFF1E293B)
private val Cyan         = Color(0xFF22D3EE)
private val ElectricBlue = Color(0xFF3B82F6)
private val WAGreen      = Color(0xFF25D366)
private val TextWhite    = Color(0xFFF8FAFC)
private val TextMuted    = Color(0xFF94A3B8)
private val ModalBg      = Color(0xFF0B1626)
private val SoftRed      = Color(0xFFF87171)
private val Amber        = Color(0xFFF59E0B)
private val CloseSoftRed = Color(0xFFFFA3A3)

/**
 * Display-only automatic-SMS estimate. The trusted server remains the only
 * authority that deducts credits; its same ASCII/Unicode limits are mirrored
 * here so the owner sees the expected charge before sending.
 */
data class BulkSmsPreviewMessage(
    val recipientName: String,
    val phone: String?,
    val message: String
)

data class BulkSmsPreview(
    val firstRecipientName: String,
    val firstMessage: String,
    val firstMessageCharacters: Int,
    val firstMessageCredits: Int,
    val eligibleRecipientCount: Int,
    val skippedRecipientCount: Int,
    val totalCharacters: Int,
    val totalCredits: Int
)

/** Matches functions/src/smsWallet.js: ASCII 160/153, Unicode 70/67. */
fun estimateSmsCredits(message: String): Int {
    if (message.isEmpty()) return 0
    val ascii = message.all { it.code <= 0x7F }
    val singleLimit = if (ascii) 160 else 70
    val joinedLimit = if (ascii) 153 else 67
    return if (message.length <= singleLimit) 1 else (message.length + joinedLimit - 1) / joinedLimit
}

fun buildBulkSmsPreview(messages: List<BulkSmsPreviewMessage>): BulkSmsPreview? {
    val first = messages.firstOrNull() ?: return null
    val eligible = messages.filter { row ->
        row.phone?.filter(Char::isDigit).orEmpty().isNotBlank() &&
            row.message.isNotBlank() && row.message.length <= 480
    }
    return BulkSmsPreview(
        firstRecipientName = first.recipientName,
        firstMessage = first.message,
        firstMessageCharacters = first.message.length,
        firstMessageCredits = estimateSmsCredits(first.message),
        eligibleRecipientCount = eligible.size,
        skippedRecipientCount = messages.size - eligible.size,
        totalCharacters = eligible.sumOf { it.message.length },
        totalCredits = eligible.sumOf { estimateSmsCredits(it.message) }
    )
}

// ── Bulk send progress panel ─────────────────────────────────────
@Composable
fun BulkSendProgressPanel(
    state: BulkMessageController.BulkQueueState,
    onRetryFailed: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit
) {
    val running = state.phase == BulkMessageController.Phase.RUNNING ||
        state.phase == BulkMessageController.Phase.AWAITING_RESUME
    val completed = state.phase == BulkMessageController.Phase.COMPLETED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(ModalBg)
            .border(1.dp, Cyan.copy(alpha = 0.22f), RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Bulk Sending",
                color = Cyan,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Accepted ${state.sentCount + state.queuedCount} of ${state.totalCount}",
                color = TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = {
                if (state.totalCount == 0) 0f
                else state.processedCount.toFloat() / state.totalCount
            },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = Cyan,
            trackColor = BorderSub
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(state.items, key = { _, item -> item.target.key }) { _, item ->
                BulkQueueItemRow(item)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running && !state.serverManaged) {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, SoftRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SoftRed)
                ) {
                    Text("Stop", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            if (running && state.serverManaged) {
                Text(
                    "Sending securely through BatchFee Server…",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
            }
            if (completed && state.failedCount > 0) {
                OutlinedButton(
                    onClick = onRetryFailed,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, Cyan),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                ) {
                    Text("Retry Failed", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            if (completed) {
                Button(
                    onClick = onClose,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = BgColor)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun BulkQueueItemRow(item: BulkMessageController.BulkQueueItem) {
    val (chipColor, chipBg, label) = when (item.status) {
        BulkMessageController.Status.SENT -> Triple(WAGreen, WAGreen.copy(alpha = 0.15f), "Sent")
        BulkMessageController.Status.QUEUED -> Triple(Cyan, Cyan.copy(alpha = 0.15f), "Pending DLR")
        BulkMessageController.Status.FAILED -> Triple(SoftRed, SoftRed.copy(alpha = 0.15f), "Failed")
        BulkMessageController.Status.DUPLICATE -> Triple(Amber, Amber.copy(alpha = 0.15f), "Duplicate")
        BulkMessageController.Status.NO_PHONE -> Triple(TextMuted, TextMuted.copy(alpha = 0.15f), "No phone")
        BulkMessageController.Status.CANCELLED -> Triple(TextMuted, TextMuted.copy(alpha = 0.15f), "Stopped")
        BulkMessageController.Status.PENDING -> Triple(TextMuted, BorderSub, "Pending")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBgAlt)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.target.name,
                color = TextWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item.lastError ?: (item.target.phone ?: "No phone"),
                color = if (item.lastError != null) SoftRed else TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(chipBg)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(label, color = chipColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Selection mode top bar ───────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkSelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                "$selectedCount selected",
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = "Exit selection", tint = TextWhite)
            }
        },
        actions = {
            TextButton(onClick = onSelectAll) {
                Icon(
                    if (selectedCount == totalCount && totalCount > 0) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = Cyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (selectedCount == totalCount && totalCount > 0) "Unselect All" else "Select All",
                    color = Cyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
    )
}

// ── Bottom bulk action bar ───────────────────────────────────────
@Composable
fun BulkActionBar(
    selectedCount: Int,
    onWhatsApp: () -> Unit,
    onSms: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ModalBg,
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderSub)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$selectedCount selected",
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onWhatsApp,
                enabled = selectedCount > 0,
                modifier = Modifier.height(46.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, WAGreen),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = WAGreen)
            ) {
                Text("WhatsApp", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Button(
                onClick = onSms,
                enabled = selectedCount > 0,
                modifier = Modifier.height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = BgColor)
            ) {
                Text("SMS", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

// ── Bulk message composer dialog ─────────────────────────────────
@Composable
fun BulkMessageDialog(
    title: String,
    recipientCount: Int,
    messageText: String,
    onMessageChange: (String) -> Unit,
    initialDelaySeconds: Int,
    onStartWhatsApp: (delayMs: Long) -> Unit,
    onStartSms: (delayMs: Long) -> Unit,
    onDismiss: () -> Unit,
    broadcastMode: Boolean = false,
    /** When set, prevents a Due Fee SMS flow from offering the WhatsApp path (and vice versa). */
    lockedChannel: String? = null,
    /** Per-recipient, resolved preview supplied by a feature-specific caller. */
    smsPreview: BulkSmsPreview? = null
) {
    val scope = rememberCoroutineScope()
    val instituteId by SessionManager.currentInstituteId.collectAsState()
    val role by SessionManager.currentUserRole.collectAsState()
    val ownerCanChooseMethod = role == "InstituteOwner"
    var delayText by remember {
        mutableStateOf(initialDelaySeconds.toString())
    }
    var smsMethod by remember(instituteId) { mutableStateOf(SmsWalletState.METHOD_CARRIER) }
    var smsBalance by remember(instituteId) { mutableStateOf(0) }
    var methodLoading by remember(instituteId) { mutableStateOf(true) }
    var methodBackendAvailable by remember(instituteId) { mutableStateOf(false) }
    var methodSaving by remember { mutableStateOf(false) }
    var methodError by remember { mutableStateOf<String?>(null) }
    var methodWarning by remember { mutableStateOf<String?>(null) }
    val showsSms = lockedChannel != "whatsapp"
    val showsWhatsApp = lockedChannel != "sms"
    val automaticSms = showsSms && smsMethod == SmsWalletState.METHOD_SERVER && methodBackendAvailable
    val automaticCharge = smsPreview?.totalCredits ?: 0
    val insufficientAutomaticBalance = automaticSms && automaticCharge > smsBalance
    // Automatic SMS is handled by the trusted backend, so a phone-app delay
    // is neither used nor shown. WhatsApp and carrier SMS still need it.
    val showDelayControl = showsWhatsApp || (showsSms && !automaticSms)

    LaunchedEffect(instituteId, role) {
        val resolvedInstituteId = instituteId
        if (resolvedInstituteId.isNullOrBlank()) {
            methodLoading = false
            methodError = "No active institute session. Please log in again."
            return@LaunchedEffect
        }
        methodLoading = true
        methodBackendAvailable = false
        methodError = null
        methodWarning = null
        runCatching { SmsWalletSyncHelper.ensureWalletInitialized(resolvedInstituteId) }
            .onSuccess { wallet ->
                methodBackendAvailable = true
                smsBalance = wallet.smsBalance
                // Owners choose for every send; the safer phone carrier flow
                // is always the default. Staff use the owner's saved method.
                smsMethod = if (ownerCanChooseMethod) {
                    SmsWalletState.METHOD_CARRIER
                } else {
                    wallet.smsSendMethod
                }
            }
            .onFailure {
                methodBackendAvailable = false
                smsMethod = SmsWalletState.METHOD_CARRIER
                methodWarning = "Automatic SMS is unavailable right now. Phone SMS will still work."
            }
        methodLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.92f),
        containerColor = ModalBg,
        shape = RoundedCornerShape(22.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Cyan, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = CloseSoftRed, modifier = Modifier.size(28.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (broadcastMode) {
                        "This same message will be sent to $recipientCount selected student${if (recipientCount != 1) "s" else ""}, one by one."
                    } else {
                        "Each selected student gets their own due report. ${recipientCount} student${if (recipientCount != 1) "s" else ""} selected, sent one by one."
                    },
                    color = TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                if (!broadcastMode && smsPreview != null) {
                    MessagePreviewCard(smsPreview)
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = onMessageChange,
                        label = { Text("Optional note") },
                        placeholder = { Text("Added above every student's due reminder.") },
                        supportingText = {
                            Text(
                                "Use {name}, {amount} or {period} in this note.",
                                color = TextMuted.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        colors = composerTextFieldColors(),
                        shape = RoundedCornerShape(16.dp)
                    )
                } else {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = onMessageChange,
                        placeholder = {
                            Text(
                                if (broadcastMode) "Write the common message to send to everyone."
                                else "Optional note added on top of each student's due report.",
                                color = TextMuted.copy(alpha = 0.75f)
                            )
                        },
                        supportingText = {
                            Text(
                                if (broadcastMode) "Same message for all. Use {name} for each student's name."
                                else "Due report (name, amounts, periods) is added automatically for each student.",
                                color = TextMuted.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        colors = composerTextFieldColors(),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
                if (showsSms) Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardBg)
                        .border(1.dp, BorderSub, RoundedCornerShape(16.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Sms, contentDescription = null, tint = Cyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("SMS delivery", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        if (methodLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Cyan)
                        }
                    }
                    SmsDeliveryOption(
                        title = "Phone SMS · Semi-automatic",
                        subtitle = "Recommended default. Review and send from your phone.",
                        selected = smsMethod == SmsWalletState.METHOD_CARRIER,
                        enabled = ownerCanChooseMethod && !methodLoading && !methodSaving,
                        onClick = { smsMethod = SmsWalletState.METHOD_CARRIER; methodError = null }
                    )
                    SmsDeliveryOption(
                        title = "BatchFee SMS · Automatic",
                        subtitle = "Sends in the background · Balance: $smsBalance credits",
                        selected = smsMethod == SmsWalletState.METHOD_SERVER,
                        enabled = ownerCanChooseMethod && methodBackendAvailable && !methodLoading && !methodSaving,
                        onClick = { smsMethod = SmsWalletState.METHOD_SERVER; methodError = null }
                    )
                    if (!ownerCanChooseMethod && methodBackendAvailable && !methodLoading && methodError == null) {
                        Text("Delivery method is controlled by the institute owner.", color = TextMuted, fontSize = 10.sp)
                    }
                    methodError?.let { error ->
                        Text(error, color = SoftRed, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    methodWarning?.let { warning ->
                        Text(warning, color = Amber, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    if (automaticSms && smsPreview != null) {
                        val skipped = smsPreview.skippedRecipientCount
                        Text(
                            "Automatic charge: ${smsPreview.totalCredits} SMS credits for ${smsPreview.eligibleRecipientCount} recipient${if (smsPreview.eligibleRecipientCount == 1) "" else "s"}.",
                            color = Cyan,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                        if (skipped > 0) {
                            Text(
                                "$skipped recipient${if (skipped == 1) "" else "s"} will be skipped (missing phone or message over 480 characters).",
                                color = Amber,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                        if (insufficientAutomaticBalance) {
                            Text(
                                "Insufficient balance: ${smsPreview.totalCredits} credits are needed, but only $smsBalance remain.",
                                color = SoftRed,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
                if (showDelayControl) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Delay between messages:",
                        color = TextWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = delayText,
                        onValueChange = { new ->
                            if (new.all { it.isDigit() } && new.length <= 3) delayText = new
                        },
                        modifier = Modifier.width(76.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedBorderColor = Cyan,
                            unfocusedBorderColor = BorderSub,
                            focusedContainerColor = CardBg,
                            unfocusedContainerColor = CardBg,
                            cursorColor = Cyan
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Text("sec", color = TextMuted, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showsWhatsApp) OutlinedButton(
                    onClick = {
                        val seconds = (delayText.toIntOrNull() ?: initialDelaySeconds).coerceIn(0, 999)
                        onStartWhatsApp(seconds * 1000L)
                    },
                    enabled = !broadcastMode || messageText.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.5.dp, WAGreen),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WAGreen)
                ) {
                    Text("WhatsApp", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                if (showsSms) Button(
                    onClick = {
                        val seconds = (delayText.toIntOrNull() ?: initialDelaySeconds).coerceIn(0, 999)
                        val resolvedInstituteId = instituteId
                        if (resolvedInstituteId.isNullOrBlank()) {
                            methodError = "No active institute session. Please log in again."
                            return@Button
                        }
                        if (!ownerCanChooseMethod || (!methodBackendAvailable && smsMethod == SmsWalletState.METHOD_CARRIER)) {
                            onStartSms(seconds * 1000L)
                            return@Button
                        }
                        methodSaving = true
                        methodError = null
                        scope.launch {
                            runCatching {
                                SmsWalletSyncHelper.setSmsSendMethod(resolvedInstituteId, smsMethod)
                            }.onSuccess { wallet ->
                                smsBalance = wallet.smsBalance
                                methodSaving = false
                                onStartSms(seconds * 1000L)
                            }.onFailure { error ->
                                methodSaving = false
                                methodError = error.message ?: "Could not confirm the SMS delivery method."
                            }
                        }
                    },
                    enabled = (!broadcastMode || messageText.isNotBlank()) &&
                        !methodLoading && !methodSaving && methodError == null && !insufficientAutomaticBalance,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = BgColor)
                ) {
                    Text(
                        when {
                            methodSaving -> "Preparing..."
                            automaticSms && smsPreview != null -> "Send ${smsPreview.totalCredits} SMS"
                            else -> "SMS"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    )
}

// ── Selectable check badge shown on list cards in selection mode ──
@Composable
private fun MessagePreviewCard(preview: BulkSmsPreview) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBgAlt)
            .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Preview, contentDescription = null, tint = Cyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Message preview · ${preview.firstRecipientName}", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            preview.firstMessage,
            color = TextWhite,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${preview.firstMessageCharacters} characters · ${preview.firstMessageCredits} SMS credit${if (preview.firstMessageCredits == 1) "" else "s"}",
            color = Cyan,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Bulk SMS: ${preview.eligibleRecipientCount} recipient${if (preview.eligibleRecipientCount == 1) "" else "s"} · ${preview.totalCharacters} characters total · ${preview.totalCredits} SMS credit${if (preview.totalCredits == 1) "" else "s"}",
            color = TextWhite,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        if (preview.skippedRecipientCount > 0) {
            Text(
                "${preview.skippedRecipientCount} recipient${if (preview.skippedRecipientCount == 1) "" else "s"} cannot be included (missing phone or message over 480 characters).",
                color = Amber,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
        Text(
            "Each student's name, due amount and fee months update automatically.",
            color = TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun composerTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWhite,
    unfocusedTextColor = TextWhite,
    focusedBorderColor = Cyan,
    unfocusedBorderColor = Cyan.copy(alpha = 0.28f),
    focusedContainerColor = CardBgAlt,
    unfocusedContainerColor = CardBgAlt,
    cursorColor = Cyan
)

@Composable
private fun SmsDeliveryOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (selected) Cyan.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = Cyan,
                unselectedColor = TextMuted,
                disabledSelectedColor = Cyan.copy(alpha = 0.65f),
                disabledUnselectedColor = TextMuted.copy(alpha = 0.45f)
            )
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
fun SelectionBadge(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(
                if (selected) Brush.horizontalGradient(listOf(ElectricBlue, Cyan))
                else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
            )
            .border(
                1.5.dp,
                if (selected) Color.Transparent else BorderSub,
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
