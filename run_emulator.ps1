<#
.SYNOPSIS
    Launches the Android emulator for the BatchFee project.

.DESCRIPTION
    Resolves emulator.exe from the standard Android SDK location
    ($env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe) with fallbacks
    to ANDROID_HOME, ANDROID_SDK_ROOT, and PATH, then starts the requested
    AVD in a detached process so the terminal is freed immediately.

.PARAMETER AvdName
    Name of the AVD to launch. Defaults to 'BatchFee_Pixel_API_37'.

.PARAMETER ListAvds
    List all available AVDs instead of launching the emulator.

.EXAMPLE
    .\run_emulator.ps1

.EXAMPLE
    .\run_emulator.ps1 -AvdName SomeOtherAvd

.EXAMPLE
    .\run_emulator.ps1 -ListAvds
#>
[CmdletBinding()]
param(
    [string]$AvdName = 'BatchFee_Pixel_API_37',
    [switch]$ListAvds
)

$ErrorActionPreference = 'Stop'

function Find-Emulator {
    $candidates = @()

    if ($env:ANDROID_HOME) {
        $candidates += Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += Join-Path $env:ANDROID_SDK_ROOT 'emulator\emulator.exe'
    }
    if ($env:LOCALAPPDATA) {
        $candidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk\emulator\emulator.exe'
    }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }

    $fromPath = Get-Command emulator -ErrorAction SilentlyContinue
    if ($fromPath) {
        return $fromPath.Source
    }

    throw 'emulator.exe not found. Install the Android SDK or set ANDROID_HOME / ANDROID_SDK_ROOT.'
}

$emulator = Find-Emulator
Write-Host "Using emulator: $emulator"

if ($ListAvds) {
    & $emulator -list-avds
    exit 0
}

$avdHome = if ($env:ANDROID_AVD_HOME) {
    $env:ANDROID_AVD_HOME
}
else {
    Join-Path $env:USERPROFILE '.android\avd'
}
$avdIni = Join-Path $avdHome "$AvdName.ini"

if (-not (Test-Path -LiteralPath $avdIni -PathType Leaf)) {
    $available = & $emulator -list-avds
    throw "AVD '$AvdName' not found. Available AVDs:`n$available"
}

Write-Host "Launching AVD '$AvdName'..."
Start-Process -FilePath $emulator -ArgumentList @('-avd', $AvdName)
Write-Host 'Emulator launch initiated. The emulator window should appear shortly.'
