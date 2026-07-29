$ErrorActionPreference = 'Stop'

$sourceRoot = Join-Path $PSScriptRoot '..\app\src\main'
$patterns = @(
    [string]::Concat([char]0x00E2, [char]0x20AC, [char]0x00A2),
    [string]::Concat([char]0x00C2, [char]0x00B7),
    [string]::Concat([char]0x00E2, [char]0x20AC, [char]0x201C),
    [string]::Concat([char]0x00E2, [char]0x20AC, [char]0x201D),
    [string]::Concat([char]0x00E2, [char]0x20AC, [char]0x2122),
    [string][char]0x00C2,
    [string][char]0x00C3
)

$matches = foreach ($file in Get-ChildItem -Path $sourceRoot -Recurse -Filter '*.kt') {
    $text = [System.IO.File]::ReadAllText($file.FullName, [System.Text.UTF8Encoding]::new($false, $true))
    foreach ($pattern in $patterns) {
        if ($text.Contains($pattern)) {
            [PSCustomObject]@{ File = $file.FullName; Pattern = $pattern }
        }
    }
}

if ($matches) {
    $matches | Format-Table -AutoSize | Out-String | Write-Error
    exit 1
}

Write-Host 'No known mojibake patterns found in main-app Kotlin sources.'
