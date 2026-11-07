# Run from Windows PowerShell. Does not reboot or flash the frame.
[CmdletBinding()]
param([string]$Serial, [string]$AdbPath, [switch]$CheckOnly)
. "$PSScriptRoot\provision-common.ps1"

Connect-Frame $Serial $AdbPath
$apiText = Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')
if ($apiText -notmatch '^\d+$' -or [int]$apiText -lt 25) { throw "Android API 25+ required; found $apiText" }
$packageHelp = Invoke-Adb @('shell', 'cmd', 'package')
if ($packageHelp -notmatch 'set-home-activity') { throw 'Firmware does not support set-home-activity.' }
Assert-NixPowerSupport
$components = @(Get-NixComponentStates)
$originalHome = Get-Home -AllowResolver
$packages = @($NixPackages | ForEach-Object {
    [pscustomobject]@{ Name = $_; Enabled = Get-PackageState $_ }
})

# Prefer an explicit JDK; otherwise use the JDK bundled with Android Studio.
if (-not $env:JAVA_HOME) {
    $studioJdk = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path "$studioJdk\bin\java.exe") { $env:JAVA_HOME = $studioJdk }
}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' }
        elseif (Get-Command java.exe -ErrorAction SilentlyContinue) { (Get-Command java.exe).Source }
        else { throw 'JDK 17+ required. Set JAVA_HOME or install Android Studio.' }
$oldPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $javaInfo = & $java -version 2>&1
    $javaExit = $LASTEXITCODE
} finally { $ErrorActionPreference = $oldPreference }
if ($javaExit -ne 0 -or "$javaInfo" -notmatch 'version "(\d+)') { throw 'Cannot determine Java version.' }
if ([int]$Matches[1] -lt 17) { throw 'JDK 17+ required.' }
foreach ($file in @('gradlew.bat', 'gradle\wrapper\gradle-wrapper.jar')) {
    if (-not (Test-Path (Join-Path $ProjectRoot $file))) { throw "Missing $file" }
}
Write-Host "Preflight passed. Current HOME: $originalHome"
if ($CheckOnly) {
    Write-Host 'No changes made. SDK/build dependencies and signing compatibility are checked by the build and install during setup.'
    return
}

$safeSerial = $script:Serial -replace '[^A-Za-z0-9_.-]', '_'
$folder = Join-Path $ProjectRoot ".rollback\$safeSerial"
$statePath = Join-Path $folder 'state.json'
if (Test-Path $statePath) {
    throw "An original snapshot already exists at $statePath. Run scripts\provision-rollback.ps1 before starting another setup. It will not be overwritten."
}

# Build before changing device configuration; Gradle validates the SDK and dependencies.
Push-Location $ProjectRoot
try {
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Build failed; device configuration unchanged.' }
} finally { Pop-Location }
$apk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw 'Build did not produce the expected APK.' }

$state = [pscustomobject]@{
    Schema = 3
    PowerProfile = $NixPower
    Serial = $script:Serial
    Fingerprint = $script:Fingerprint
    CapturedUtc = [DateTime]::UtcNow.ToString('o')
    Home = $originalHome
    Packages = $packages
    Components = $components
}
New-Item -ItemType Directory -Force $folder | Out-Null
$state | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $statePath
Write-Host "Original configuration saved to $statePath"

try {
    $install = Invoke-Adb -Arguments @('install', '-r', $apk) -TimeoutSeconds 180
    if ($install -notmatch '(?m)^Success\s*$') { throw "Install failed: $install" }
    $availableHomes = Invoke-Adb @('shell', 'cmd', 'package', 'query-activities', '--brief', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    if ($availableHomes -notmatch 'com\.bgradin\.customframe/(?:\.MainActivity|com\.bgradin\.customframe\.MainActivity)') {
        throw 'Installed app is not registered as a HOME activity.'
    }
    $launch = Invoke-Adb @('shell', 'am', 'start', '-W', '--user', '0', '-n', $FrameHome)
    Write-Host $launch
    if ($launch -notmatch '(?m)^Status: ok\s*$' -or $launch -match '(?m)^Error:') {
        throw 'Custom Frame failed to launch; leaving Nixplay available.'
    }
    # Preserve the absence of a HOME preference so rollback can restore the chooser.
    if ($originalHome -notmatch '^android/') {
        Invoke-Adb @('shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $FrameHome) | Write-Host
    }
    Set-NixPowerOnly
    if ((Get-Home) -ne $FrameHome) { throw 'Custom Frame did not become HOME.' }
    Invoke-Adb @('shell', 'am', 'start', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME') | Write-Host
    Write-Host 'Custom Frame is HOME. Nixplay UI/startup receivers are disabled; its power receivers remain enabled. The companion package is disabled. No reboot performed.'
    Write-Host "Rollback: .\scripts\provision-rollback.ps1 -Serial $script:Serial"
} catch {
    $setupFailure = $_
    Write-Warning 'Setup failed. Attempting to restore the saved HOME and package states.'
    try { Restore-Frame $state }
    catch { Write-Warning "Automatic rollback failed: $_. Run scripts\provision-rollback.ps1 after restoring ADB connectivity." }
    throw $setupFailure
}
