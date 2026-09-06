param(
    [string]$InFile = "emulator-ui-now.xml",
    [string]$OutFile = "ui-formatted.txt"
)
$raw = Get-Content $InFile -Raw
$lines = ($raw -replace '><', ">`n<") -split "`n"
$out = foreach ($line in $lines) {
    if ($line -match 'text="([^"]+)"') {
        $t = $matches[1]
        if ($t.Trim().Length -gt 0) { $t.Trim() }
    } elseif ($line -match 'content-desc="([^"]+)"') {
        $d = $matches[1]
        if ($d.Trim().Length -gt 0) { "[desc] " + $d.Trim() }
    }
}
$out | Set-Content $OutFile -Encoding UTF8
$out | Select-Object -First 80
