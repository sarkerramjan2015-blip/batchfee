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

# 1. State variables
$anchor = '    var includeAnswerKey by rememberSaveable { mutableStateOf(false) }'
$replacement = @'
    var includeAnswerKey by rememberSaveable { mutableStateOf(false) }
    var font by rememberSaveable { mutableStateOf(QuestionPaperFont.HIND_SILIGURI.name) }
    var columns by rememberSaveable { mutableStateOf(1) }
    var showPageBorder by rememberSaveable { mutableStateOf(false) }
'@
Replace-Once $anchor $replacement.TrimEnd("`n") 'composer state'

# 2. currentSetup fields
$anchor = @'
        fontSize = QuestionPaperFontSize.valueOf(fontSize),
        includeAnswerKey = includeAnswerKey,
    )
'@
$replacement = @'
        fontSize = QuestionPaperFontSize.valueOf(fontSize),
        font = QuestionPaperFont.valueOf(font),
        columns = columns,
        showPageBorder = showPageBorder,
        includeAnswerKey = includeAnswerKey,
    )
'@
Replace-Once $anchor $replacement 'currentSetup fields'

# 3. Font + columns + border controls after the text-size chips
$anchor = @'
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Include answer key", color = BankText, fontWeight = FontWeight.SemiBold)
                        Text("Adds a separate teacher-only answer-key page.", color = BankMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = includeAnswerKey,
                        onCheckedChange = { includeAnswerKey = it; generatedFile = null },
                    )
                }
'@
$replacement = @'
                Text("Bangla font", color = BankText, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuestionPaperFont.entries.forEach { option ->
                        FilterChip(
                            selected = font == option.name,
                            onClick = { font = option.name; generatedFile = null },
                            label = { Text(option.label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BankCyan.copy(alpha = .18f), selectedLabelColor = BankCyan, labelColor = BankMuted),
                        )
                    }
                }
                Text("Columns", color = BankText, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "One column", 2 to "Two columns").forEach { (value, label) ->
                        FilterChip(
                            selected = columns == value,
                            onClick = { columns = value; generatedFile = null },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BankCyan.copy(alpha = .18f), selectedLabelColor = BankCyan, labelColor = BankMuted),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Page border", color = BankText, fontWeight = FontWeight.SemiBold)
                        Text("Draws a thin border around every page.", color = BankMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = showPageBorder,
                        onCheckedChange = { showPageBorder = it; generatedFile = null },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Include answer key", color = BankText, fontWeight = FontWeight.SemiBold)
                        Text("Adds a separate teacher-only answer-key page.", color = BankMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = includeAnswerKey,
                        onCheckedChange = { includeAnswerKey = it; generatedFile = null },
                    )
                }
'@
Replace-Once $anchor $replacement 'font/columns/border controls'

# 4. Preview shows all questions
$anchor = @'
                            questions.take(2).forEachIndexed { index, question ->
                                Text("${index + 1}. ${question.questionText}", color = Color(0xFF0F172A), fontSize = 11.sp, maxLines = 3)
                                question.options.take(4).forEachIndexed { optionIndex, option ->
                                    Text("   ${('A'.code + optionIndex).toChar()}. $option", color = Color(0xFF475569), fontSize = 10.sp, maxLines = 1)
                                }
                            }
                            if (questions.size > 2) Text("+ ${questions.size - 2} more question(s)", color = Color(0xFF0369A1), fontSize = 10.sp)
'@
$replacement = @'
                            questions.forEachIndexed { index, question ->
                                Text("${index + 1}. ${question.questionText}", color = Color(0xFF0F172A), fontSize = 11.sp)
                                question.options.take(4).forEachIndexed { optionIndex, option ->
                                    Text("   ${('A'.code + optionIndex).toChar()}. $option", color = Color(0xFF475569), fontSize = 10.sp)
                                }
                            }
'@
Replace-Once $anchor $replacement 'preview all questions'

# 5. Download success toast
$anchor = @'
                                runCatching {
                                    downloadQuestionPaperPdf(context, file, examName.ifBlank { "question_paper" })
                                }.onSuccess { notice = "PDF saved to Downloads/Question Papers." }
                                    .onFailure { error = it.message ?: "Could not download PDF." }
'@
$replacement = @'
                                runCatching {
                                    downloadQuestionPaperPdf(context, file, examName.ifBlank { "question_paper" })
                                }.onSuccess {
                                    notice = "PDF saved to Downloads/Question Papers."
                                    Toast.makeText(context, "PDF saved to Downloads/Question Papers.", Toast.LENGTH_SHORT).show()
                                }
                                    .onFailure { error = it.message ?: "Could not download PDF." }
'@
Replace-Once $anchor $replacement 'download toast'

$encoding = New-Object System.Text.UTF8Encoding($false)
$writer = New-Object System.IO.StreamWriter($resolved, $false, $encoding)
try {
    $writer.NewLine = "`n"
    $writer.Write($content)
} finally {
    $writer.Close()
}
Write-Output 'Composer dialog patched.'
