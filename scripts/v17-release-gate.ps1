param(
    [string]$JavaHome
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$localJava21 = if ($env:LOCALAPPDATA) {
    Join-Path $env:LOCALAPPDATA 'BatchFeeTools\temurin-21.0.12.1\jdk-21.0.12.1+1'
} else { $null }
$javaCandidates = @($JavaHome, $env:JAVA_HOME, $localJava21) |
    Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) } |
    Select-Object -Unique
$selectedJava = $null

foreach ($candidate in $javaCandidates) {
    $versionText = (& (Join-Path $candidate 'bin\java.exe') -version 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -and $versionText -match 'version "21(?:\.|\")') {
        $selectedJava = $candidate
        break
    }
}

if (-not $selectedJava) {
    throw 'Java 21 was not found. Pass -JavaHome with the JDK 21 directory.'
}

$env:JAVA_HOME = $selectedJava
$env:Path = "$(Join-Path $selectedJava 'bin');$env:Path"

function Invoke-GateStep {
    param(
        [string]$Name,
        [scriptblock]$Command
    )
    Write-Host "`n=== $Name ===" -ForegroundColor Cyan
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
}

Push-Location $repoRoot
try {
    Invoke-GateStep 'UTF-8 source check' {
        powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repoRoot 'scripts\check-mojibake.ps1')
    }

    Push-Location (Join-Path $repoRoot 'functions')
    try {
        Invoke-GateStep 'Backend syntax check' { npm.cmd run check }
        Invoke-GateStep 'Backend unit and load-oriented tests' { npm.cmd test }
    } finally {
        Pop-Location
    }

    Invoke-GateStep 'Firestore security rules tests' { npm.cmd run test:firestore-rules }
    Invoke-GateStep 'Android unit tests' { & (Join-Path $repoRoot 'gradlew.bat') testDebugUnitTest --no-daemon }
    Invoke-GateStep 'Android Kotlin compile' { & (Join-Path $repoRoot 'gradlew.bat') :app:compileDebugKotlin --no-daemon }

    Write-Host "`nBatchFee v1.7 release gate passed. No deployment was performed." -ForegroundColor Green
} finally {
    Pop-Location
}
