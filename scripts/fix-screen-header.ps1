$path = Join-Path $PSScriptRoot '..\app\src\main\java\com\example\ui\exams\QuestionBankFoundationScreen.kt'
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

# 1. Prepend package + missing android imports when the package line is absent.
if (-not $content.Contains('package com.batchfee.edu.ui.exams')) {
    $header = @'
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
    $content = $header + $content
    Write-Output 'Prepared package + android imports.'
}

# 2. Icons + TextOverflow imports (idempotent)
if (-not $content.Contains('import androidx.compose.material.icons.filled.ContentCopy')) {
    Replace-Once 'import androidx.compose.material.icons.filled.CheckCircle' "import androidx.compose.material.icons.filled.CheckCircle`nimport androidx.compose.material.icons.filled.ContentCopy" 'ContentCopy import'
}
if (-not $content.Contains('import androidx.compose.material.icons.filled.Phone')) {
    Replace-Once 'import androidx.compose.material.icons.filled.Print' "import androidx.compose.material.icons.filled.Phone`nimport androidx.compose.material.icons.filled.Print" 'Phone import'
}
if (-not $content.Contains('import androidx.compose.ui.text.style.TextOverflow')) {
    Replace-Once 'import androidx.compose.ui.text.input.KeyboardType' "import androidx.compose.ui.text.input.KeyboardType`nimport androidx.compose.ui.text.style.TextOverflow" 'TextOverflow import'
}

# 3. State variables
if (-not $content.Contains('var topupMethod by rememberSaveable')) {
    $anchor = '    var previousError by remember { mutableStateOf<String?>(null) }'
    $replacement = @'
    var previousError by remember { mutableStateOf<String?>(null) }
    var topupMethod by rememberSaveable { mutableStateOf("bkash") }
    var topupSenderNumber by rememberSaveable { mutableStateOf("") }
'@
    Replace-Once $anchor $replacement.TrimEnd("`n") 'topup state vars'
}

$encoding = New-Object System.Text.UTF8Encoding($false)
$writer = New-Object System.IO.StreamWriter($resolved, $false, $encoding)
try {
    $writer.NewLine = "`n"
    $writer.Write($content)
} finally {
    $writer.Close()
}
Write-Output 'Screen header fixed.'
