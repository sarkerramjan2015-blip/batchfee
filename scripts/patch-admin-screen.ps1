$path = Join-Path $PSScriptRoot '..\app\src\main\java\com\example\ui\superadmin\QuestionBankAdminScreen.kt'
$resolved = (Resolve-Path $path).Path
$content = [System.IO.File]::ReadAllText($resolved)
$content = $content -replace "`r`n", "`n"

function Replace-Once([string]$oldText, [string]$newText, [string]$label) {
    $oldText = $oldText -replace "`r`n", "`n"
    $newText = $newText -replace "`r`n", "`n"
    if (-not $script:content.Contains($oldText)) {
        Write-Error "Anchor not found: $label"
        exit 1
    }
    $script:content = $script:content.Replace($oldText, $newText)
    Write-Output "Replaced: $label"
}

# Import
Replace-Once 'import com.batchfee.edu.data.repository.PendingQuestionTopup' "import com.batchfee.edu.data.repository.PendingQuestionTopup`nimport com.batchfee.edu.data.repository.QuestionRevenueSummary" 'revenue summary import'

# State
Replace-Once '    var decidingId by remember { mutableStateOf<String?>(null) }' "    var decidingId by remember { mutableStateOf<String?>(null) }`n    var revenue by remember { mutableStateOf(QuestionRevenueSummary()) }" 'revenue state'

# Refresh loads revenue
$anchor = @'
        scope.launch {
            runCatching { repository.pendingTopups() }
                .onSuccess { pendingTopups = it }
                .onFailure { }
        }
'@
$replacement = @'
        scope.launch {
            runCatching { repository.pendingTopups() }
                .onSuccess { pendingTopups = it }
                .onFailure { }
        }
        scope.launch {
            runCatching { repository.revenueSummary() }
                .onSuccess { revenue = it }
                .onFailure { }
        }
'@
Replace-Once $anchor $replacement 'refresh revenue'

# Revenue summary line under the pending top-ups description
$anchor = @'
                    Text(
                        "Owners request top-up from Create Questions (minimum BDT 50 + 1.8% processing fee). Verify the payment before approving.",
                        color = AdminMuted,
                        fontSize = 12.sp,
                    )
'@
$replacement = @'
                    Text(
                        "Owners request top-up from Create Questions (minimum BDT 50 + 1.8% processing fee). Verify the payment before approving.",
                        color = AdminMuted,
                        fontSize = 12.sp,
                    )
                    Text(
                        "Question revenue: charges ${formatPoisha(revenue.totalQuestionChargesPoisha)} · top-up fees ${formatPoisha(revenue.totalTopupFeePoisha)} · top-up credit ${formatPoisha(revenue.totalTopupCreditPoisha)} (${revenue.topupCount} top-ups, ${revenue.chargeCount} charges)",
                        color = AdminCyan,
                        fontSize = 11.sp,
                    )
'@
Replace-Once $anchor $replacement 'revenue summary line'

# Sender/method line in each pending card
$anchor = 'Text("Institute: ${topup.instituteId}", color = AdminText, fontSize = 13.sp)'
$replacement = @'
Text("Institute: ${topup.instituteId}", color = AdminText, fontSize = 13.sp)
                                    Text(
                                        "${topup.paymentMethod} · sender ${topup.senderNumber}",
                                        color = AdminMuted,
                                        fontSize = 12.sp,
                                    )
'@
$replacement = $replacement.TrimEnd("`n")
Replace-Once $anchor $replacement 'sender line'

$encoding = New-Object System.Text.UTF8Encoding($false)
$writer = New-Object System.IO.StreamWriter($resolved, $false, $encoding)
try {
    $writer.NewLine = "`n"
    $writer.Write($content)
} finally {
    $writer.Close()
}
Write-Output 'QuestionBankAdminScreen.kt patched successfully.'
