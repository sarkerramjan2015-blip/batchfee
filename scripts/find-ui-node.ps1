param(
    [string]$InFile = "emulator-ui-now.xml",
    [string[]]$Texts = @("Cancel", "Assign", "Remove", "Delete")
)
$raw = Get-Content $InFile -Raw
$nodes = $raw -split '<node'
foreach ($n in $nodes) {
    foreach ($want in $Texts) {
        if ($n -match ('text="' + [regex]::Escape($want) + '"')) {
            $b = $null
            if ($n -match 'bounds="\[([-\d]+),([-\d]+)\]\[([-\d]+),([-\d]+)\]"') {
                $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
                $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
                $cx = [int](($x1 + $x2) / 2); $cy = [int](($y1 + $y2) / 2)
                $b = "bounds[$x1,$y1][$x2,$y2] center=($cx,$cy)"
            }
            Write-Output ("{0} => {1}" -f $want, $b)
        }
    }
}
