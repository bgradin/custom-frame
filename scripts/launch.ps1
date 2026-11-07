# Build, install, and launch Custom Frame using Windows Android tools.
[CmdletBinding()]
param([string]$Serial, [string]$AdbPath)
. "$PSScriptRoot\provision-common.ps1"

Connect-Frame $Serial $AdbPath

Push-Location $ProjectRoot
try {
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }

    $apk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $apk -PathType Leaf)) { throw "APK not found: $apk" }
    Invoke-Adb @('install', '-r', $apk) | Write-Host
    Invoke-Adb @('shell', 'am', 'start', '-n', $FrameHome) | Write-Host
} finally {
    Pop-Location
}
