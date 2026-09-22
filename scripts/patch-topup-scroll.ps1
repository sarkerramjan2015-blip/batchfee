$path = Join-Path $PSScriptRoot '..\app\src\main\java\com\example\ui\exams\QuestionBankFoundationScreen.kt'
$resolved = (Resolve-Path $path).Path
$content = [System.IO.File]::ReadAllText($resolved)
$content = $content -replace "`r`n", "`n"

$anchor = 'modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 440.dp),'
$replacement = 'modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 440.dp).imePadding(),'
if (-not $content.Contains($anchor)) {
    Write-Error 'Scroll modifier anchor not found.'
    exit 1
}
$content = $content.Replace($anchor, $replacement)
Write-Output 'Added imePadding.'

$encoding = New-Object System.Text.UTF8Encoding($false)
$writer = New-Object System.IO.StreamWriter($resolved, $false, $encoding)
try {
    $writer.NewLine = "`n"
    $writer.Write($content)
} finally {
    $writer.Close()
}
Write-Output 'Done.'
