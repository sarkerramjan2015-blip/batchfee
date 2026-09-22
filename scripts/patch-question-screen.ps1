$path = Join-Path $PSScriptRoot '..\app\src\main\java\com\example\ui\exams\QuestionBankFoundationScreen.kt'
$resolved = (Resolve-Path $path).Path
$content = [System.IO.File]::ReadAllText($resolved)

function Replace-Once([string]$oldText, [string]$newText, [string]$label) {
    if (-not $script:content.Contains($oldText)) {
        Write-Error "Anchor not found: $label"
        exit 1
    }
    $script:content = $script:content.Replace($oldText, $newText)
    Write-Output "Replaced: $label"
}

# 1. Restore package line + android imports if a previous edit removed them.
if (-not $content.Contains('package com.batchfee.edu.ui.exams')) {
    $content = "package com.batchfee.edu.ui.exams`n`n$content"
    Write-Output 'Restored package line.'
}
if (-not $content.Contains('import android.app.Activity')) {
    $anchor = @'
package com.batchfee.edu.ui.exams

import androidx.activity.compose.rememberLauncherForActivityResult
'@
    $replacement = @'
package com.batchfee.edu.ui.exams

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
'@
    if ($content.Contains($anchor)) {
        $content = $content.Replace($anchor, $replacement)
        Write-Output 'Restored android imports.'
    } else {
        $fallbackAnchor = "package com.batchfee.edu.ui.exams`n"
        $fallback = @'
package com.batchfee.edu.ui.exams

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
'@
        $content = $content.Replace($fallbackAnchor, $fallback)
        if (-not $content.Contains('import android.app.Activity')) {
            Write-Error 'Could not restore android imports.'
            exit 1
        }
        Write-Output 'Restored android imports (fallback).'
    }
}

# 2. Icon imports
if (-not $content.Contains('import androidx.compose.material.icons.filled.ContentCopy')) {
    Replace-Once 'import androidx.compose.material.icons.filled.CheckCircle' "import androidx.compose.material.icons.filled.CheckCircle`nimport androidx.compose.material.icons.filled.ContentCopy" 'ContentCopy import'
}
if (-not $content.Contains('import androidx.compose.material.icons.filled.Phone')) {
    Replace-Once 'import androidx.compose.material.icons.filled.Print' "import androidx.compose.material.icons.filled.Phone`nimport androidx.compose.material.icons.filled.Print" 'Phone import'
}

# 3. TextOverflow import
if (-not $content.Contains('import androidx.compose.ui.text.style.TextOverflow')) {
    Replace-Once 'import androidx.compose.ui.text.input.KeyboardType' "import androidx.compose.ui.text.input.KeyboardType`nimport androidx.compose.ui.text.style.TextOverflow" 'TextOverflow import'
}

# 4. State variables
if (-not $content.Contains('var topupMethod by rememberSaveable')) {
    $anchor = '    var previousError by remember { mutableStateOf<String?>(null) }'
    $replacement = @'
    var previousError by remember { mutableStateOf<String?>(null) }
    var topupMethod by rememberSaveable { mutableStateOf("bkash") }
    var topupSenderNumber by rememberSaveable { mutableStateOf("") }
'@
    $replacement = $replacement.TrimEnd("`n")
    Replace-Once $anchor $replacement 'topup state vars'
}

# 5. Reset sender when opening the dialog
$anchor = @'
                onOpenTopup = {
                    topupAmountText = ""
                    showTopupDialog = true
                },
'@
$replacement = @'
                onOpenTopup = {
                    topupAmountText = ""
                    topupSenderNumber = ""
                    showTopupDialog = true
                },
'@
Replace-Once $anchor $replacement 'onOpenTopup reset'

# 6. Call site
$oldCall = @'
    if (showTopupDialog && !reviewing) {
        TopupRequestDialog(
            minAmountPoisha = foundation?.topupMinAmountPoisha ?: 5_000,
            feePercent = foundation?.topupProcessingFeePercent ?: 1.8,
            amountText = topupAmountText,
            onAmountTextChange = { topupAmountText = it.filter(Char::isDigit).take(8) },
            submitting = requestingTopup,
            onSubmit = { amountPoisha ->
                val id = instituteId
                if (id == null) {
                    error = "Open this feature from an institute account."
                } else {
                    requestingTopup = true
                    scope.launch {
                        runCatching { repository.requestTopup(id, amountPoisha) }
                            .onSuccess { pending ->
                                foundation = foundation?.copy(pendingTopup = pending)
                                showTopupDialog = false
                                topupAmountText = ""
                                snackbar.showSnackbar("Top-up request sent. Wait for Super Admin approval.")
                            }
                            .onFailure { error = it.message ?: "Could not request top-up. Try again." }
                        requestingTopup = false
                    }
                }
            },
            onDismiss = { if (!requestingTopup) showTopupDialog = false },
        )
    }
'@

$newCall = @'
    if (showTopupDialog && !reviewing) {
        TopupRequestDialog(
            minAmountPoisha = foundation?.topupMinAmountPoisha ?: 5_000,
            feePercent = foundation?.topupProcessingFeePercent ?: 1.8,
            amountText = topupAmountText,
            onAmountTextChange = { topupAmountText = it.filter(Char::isDigit).take(8) },
            paymentMethod = topupMethod,
            onPaymentMethodChange = { topupMethod = it },
            senderNumber = topupSenderNumber,
            onSenderNumberChange = { topupSenderNumber = it },
            submitting = requestingTopup,
            onSubmit = { amountPoisha, method, sender ->
                val id = instituteId
                if (id == null) {
                    error = "Open this feature from an institute account."
                } else {
                    requestingTopup = true
                    scope.launch {
                        runCatching { repository.requestTopup(id, amountPoisha, method, sender) }
                            .onSuccess { pending ->
                                foundation = foundation?.copy(pendingTopup = pending)
                                showTopupDialog = false
                                topupAmountText = ""
                                topupSenderNumber = ""
                                snackbar.showSnackbar("Top-up request sent. Wait for Super Admin approval.")
                            }
                            .onFailure { error = it.message ?: "Could not request top-up. Try again." }
                        requestingTopup = false
                    }
                }
            },
            onDismiss = { if (!requestingTopup) showTopupDialog = false },
        )
    }
'@

if (-not $content.Contains($oldCall)) {
    Write-Error 'TopupRequestDialog call-site anchor not found.'
    exit 1
}
$content = $content.Replace($oldCall, $newCall)
Write-Output 'Replaced: topup call site'

# 7. One-line Previous questions button
$anchor = ') { Text("Previous questions", color = BankText) }'
$replacement = ') { Text("Previous questions", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = BankText) }'
Replace-Once $anchor $replacement 'previous questions button'

# 8. Use-again snackbar
$anchor = @'
                sourceMode = "manual"
                showPreviousQuestions = false
                error = null
'@
$replacement = @'
                sourceMode = "manual"
                showPreviousQuestions = false
                error = null
                scope.launch { snackbar.showSnackbar("Question added to the review queue. Review it before finalizing.") }
'@
Replace-Once $anchor $replacement 'use-again snackbar'

# 9. Replace the TopupRequestDialog composable with the full payment flow.
$oldDialog = @'
@Composable
private fun TopupRequestDialog(
    minAmountPoisha: Int,
    feePercent: Double,
    amountText: String,
    onAmountTextChange: (String) -> Unit,
    submitting: Boolean,
    onSubmit: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val minTaka = minAmountPoisha / 100.0
    val amountTaka = (amountText.toDoubleOrNull() ?: 0.0)
    val amountPoisha = (amountTaka * 100).toInt()
    val feeTaka = amountTaka * feePercent / 100.0
    val payableTaka = amountTaka + feeTaka
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        containerColor = BankCard,
        title = { Text("Top up question wallet", color = BankText, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Minimum BDT 50.00, and you can add more. BatchFee Super Admin approves your payment, then the amount is credited to this wallet.",
                    color = BankMuted,
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = onAmountTextChange,
                    label = { Text("Amount (BDT)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (amountTaka > 0) {
                    Text(
                        "Amount: BDT ${"%.2f".format(amountTaka)}",
                        color = BankText,
                        fontSize = 13.sp,
                    )
                    Text(
                        "Processing fee (${feePercent}%): BDT ${"%.2f".format(feeTaka)}",
                        color = BankMuted,
                        fontSize = 13.sp,
                    )
                    Text(
                        "You pay: BDT ${"%.2f".format(payableTaka)}",
                        color = BankCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
        confirmButton = {
            Button(
                onClick = { onSubmit(amountPoisha) },
                enabled = !submitting && amountPoisha >= minAmountPoisha,
                colors = ButtonDefaults.buttonColors(containerColor = BankCyan, contentColor = BankBg),
            ) { Text(if (submitting) "Sending..." else "Request top-up") }
        },
    )
}
'@

$newDialog = @'
@Composable
private fun TopupRequestDialog(
    minAmountPoisha: Int,
    feePercent: Double,
    amountText: String,
    onAmountTextChange: (String) -> Unit,
    paymentMethod: String,
    onPaymentMethodChange: (String) -> Unit,
    senderNumber: String,
    onSenderNumberChange: (String) -> Unit,
    submitting: Boolean,
    onSubmit: (Int, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val amountTaka = amountText.toDoubleOrNull() ?: 0.0
    val amountPoisha = (amountTaka * 100).toInt()
    val feeTaka = amountTaka * feePercent / 100.0
    val payableTaka = amountTaka + feeTaka
    val payNumber = if (paymentMethod == "bkash") "01777408383" else "01518657869"
    val payLabel = if (paymentMethod == "bkash") "bKash (Send Money)" else "Nagad"
    val senderValid = senderNumber.replace(Regex("\\D"), "").length >= 11
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        containerColor = BankCard,
        title = { Text("Top up question wallet", color = BankText, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Send Money to the number below, then submit your request. Super Admin verifies your payment and credits this wallet.",
                    color = BankMuted,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("bkash" to "bKash", "nagad" to "Nagad").forEach { (value, label) ->
                        FilterChip(
                            selected = paymentMethod == value,
                            onClick = { onPaymentMethodChange(value) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BankCyan.copy(alpha = 0.18f),
                                selectedLabelColor = BankCyan,
                                labelColor = BankMuted,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = paymentMethod == value,
                                borderColor = BankBorder,
                                selectedBorderColor = BankCyan,
                            ),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BankBg)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(payLabel, color = BankMuted, fontSize = 11.sp)
                        Text(payNumber, color = BankText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("number", payNumber))
                            Toast.makeText(context, "Number copied!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy",
                            tint = BankCyan,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = onAmountTextChange,
                    label = { Text("Amount (BDT, minimum 50)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(color = BankText, fontSize = 14.sp),
                )
                Text(
                    "Amount: BDT ${"%.2f".format(amountTaka)} · Processing fee (${feePercent}%): BDT ${"%.2f".format(feeTaka)}",
                    color = BankMuted,
                    fontSize = 12.sp,
                )
                Text(
                    "You pay: BDT ${"%.2f".format(payableTaka)}",
                    color = BankCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                OutlinedTextField(
                    value = senderNumber,
                    onValueChange = { input ->
                        if (input.length <= 20 && input.all { it.isDigit() || it == '+' }) onSenderNumberChange(input)
                    },
                    label = { Text("Your ${if (paymentMethod == "bkash") "bKash" else "Nagad"} number") },
                    placeholder = { Text("e.g. 01712345678") },
                    supportingText = {
                        Text(
                            "The number the money was sent from. Super Admin verifies this before approving.",
                            color = BankMuted,
                            fontSize = 10.sp,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null, tint = BankCyan) },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
        confirmButton = {
            Button(
                onClick = { onSubmit(amountPoisha, paymentMethod, senderNumber.trim()) },
                enabled = !submitting && amountPoisha >= minAmountPoisha && senderValid,
                colors = ButtonDefaults.buttonColors(containerColor = BankCyan, contentColor = BankBg),
            ) { Text(if (submitting) "Sending..." else "Request top-up") }
        },
    )
}
'@

if (-not $content.Contains($oldDialog)) {
    Write-Error 'TopupRequestDialog body anchor not found.'
    exit 1
}
$content = $content.Replace($oldDialog, $newDialog)
Write-Output 'Replaced: topup dialog body'

$encoding = New-Object System.Text.UTF8Encoding($false)
$writer = New-Object System.IO.StreamWriter($resolved, $false, $encoding)
try {
    $writer.NewLine = "`n"
    $writer.Write($content)
} finally {
    $writer.Close()
}
Write-Output 'QuestionBankFoundationScreen.kt patched successfully.'
