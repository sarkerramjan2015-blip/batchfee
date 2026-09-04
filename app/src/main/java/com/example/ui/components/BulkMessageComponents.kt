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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.domain.BulkMessageController

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
                "Sent ${state.sentCount} of ${state.totalCount}",
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
            if (running) {
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
    broadcastMode: Boolean = false
) {
    var delayText by remember {
        mutableStateOf(initialDelaySeconds.toString())
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
                OutlinedTextField(
                    value = messageText,
                    onValueChange = onMessageChange,
                    placeholder = {
                        Text(
                            if (broadcastMode) {
                                "Write the common message to send to everyone."
                            } else {
                                "Optional note added on top of each student's due report."
                            },
                            color = TextMuted.copy(alpha = 0.75f)
                        )
                    },
                    supportingText = {
                        Text(
                            if (broadcastMode) {
                                "Same message for all. Use {name} for each student's name."
                            } else {
                                "Due report (name, amounts, periods) is added automatically for each student."
                            },
                            color = TextMuted.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedBorderColor = Cyan,
                        unfocusedBorderColor = Cyan.copy(alpha = 0.28f),
                        focusedContainerColor = CardBgAlt,
                        unfocusedContainerColor = CardBgAlt,
                        cursorColor = Cyan
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                Row(
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
                OutlinedButton(
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
                Button(
                    onClick = {
                        val seconds = (delayText.toIntOrNull() ?: initialDelaySeconds).coerceIn(0, 999)
                        onStartSms(seconds * 1000L)
                    },
                    enabled = !broadcastMode || messageText.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = BgColor)
                ) {
                    Text("SMS", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    )
}

// ── Selectable check badge shown on list cards in selection mode ──
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
