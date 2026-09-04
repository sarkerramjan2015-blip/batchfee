param([string]$Path)
$content = Get-Content -Raw -Path $Path
$matches = [regex]::Matches($content, 'text="([^"]*)"')
$result = @()
foreach ($m in $matches) {
    $val = $m.Groups[1].Value
    if ($val -ne '') { $result += $val }
}
$result | ForEach-Object { Write-Output $_ }
