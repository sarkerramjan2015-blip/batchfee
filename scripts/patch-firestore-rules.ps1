$path = Join-Path $PSScriptRoot '..\firestore.rules'
$resolved = (Resolve-Path $path).Path
$lines = [System.IO.File]::ReadAllLines($resolved)

if ($lines.Length -gt 0) {
    $first = $lines[0].TrimStart([char]0xFEFF)
    if ($first.StartsWith('ules_version')) {
        $lines[0] = "rules_version = '2';"
    }
}

for ($i = 1; $i -lt $lines.Length; $i++) {
    if ($lines[$i].Trim() -eq 'match /institutes/{instituteId}/staffs/{staffId} {') {
        if ($lines[$i - 1].Trim() -ne '') {
            $restored = New-Object System.Collections.Generic.List[string]
            for ($j = 0; $j -lt $lines.Length; $j++) {
                if ($j -eq $i) { $restored.Add('') }
                $restored.Add($lines[$j])
            }
            $lines = $restored.ToArray()
        }
        break
    }
}

$encoding = New-Object System.Text.UTF8Encoding($false)
$writer = New-Object System.IO.StreamWriter($resolved, $false, $encoding)
try {
    $writer.NewLine = "`n"
    foreach ($line in $lines) { $writer.WriteLine($line) }
} finally {
    $writer.Close()
}
Write-Output 'firestore.rules final cleanup done.'
