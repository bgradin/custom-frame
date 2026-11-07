# Shared Windows PowerShell 5.1+ helpers for provision.ps1 and provision-rollback.ps1.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$FrameApp = 'com.bgradin.customframe'
$FrameHome = "$FrameApp/.MainActivity"
$NixPackages = @('com.kitesystems.nix.frame', 'com.kitesystems.nix.prod')
$ProjectRoot = Split-Path -Parent $PSScriptRoot
# The legacy manifest is retained only for schema 2 rollback snapshots.
$NixPower = Get-Content -Raw (Join-Path $ProjectRoot 'nixplay-power-components.json') | ConvertFrom-Json

function Read-PowerProfile {
    param([string]$Path, $Profile)
    if ($Path) { $Profile = Get-Content -Raw $Path | ConvertFrom-Json }
    if ($profile.Protocol -ne 'nixplay-broadcast-v1' -or $profile.Package -ne 'com.kitesystems.nix.frame' -or
        "$($profile.VersionCode)" -notmatch '^[1-9][0-9]*$' -or -not $profile.Id) { throw "Invalid power profile: $Path" }
    $required = @('com.kitesystems.nix.frame.powermanagement.SleepReceiver', 'com.kitesystems.nix.frame.powermanagement.WakeupReceiver')
    if (@($profile.PowerReceivers).Count -ne 2 -or @($required | Where-Object { $_ -notin $profile.PowerReceivers }).Count) {
        throw "Unsupported power receivers in $Path"
    }
    $names = @($profile.DisabledComponents) + @($profile.PowerReceivers)
    if (@($profile.DisabledComponents).Count -eq 0 -or @($names | Select-Object -Unique).Count -ne $names.Count -or
        @($names | Where-Object { $_ -notmatch '^[A-Za-z_][\w$]*(\.[A-Za-z_][\w$]*)+$' }).Count) { throw "Invalid component list in $Path" }
    return $profile
}

function Assert-NixPowerSupport {
    $dump = Invoke-Adb @('shell', 'dumpsys', 'package', 'com.kitesystems.nix.frame')
    if ($dump -notmatch '\bversionCode=(\d+)\b') { throw 'Cannot determine installed Nixplay version.' }
    $version = [long]$Matches[1]
    $profiles = @(Get-ChildItem (Join-Path $ProjectRoot 'app/src/main/assets/power-profiles') -Filter '*.json' |
        ForEach-Object { Read-PowerProfile $_.FullName } | Where-Object { $_.VersionCode -eq $version })
    if ($profiles.Count -ne 1) { throw "Expected one power profile for Nixplay version $version; found $($profiles.Count). See docs/power-profiles.md to contribute support." }
    $script:NixPower = $profiles[0]
    Write-Host "Power profile: $($NixPower.Id)"
    $identity = Invoke-Adb @('shell', 'run-as', $NixPower.Package, 'id')
    if ($identity -notmatch 'uid=') { throw 'Nixplay run-as access is required to isolate its UI without root.' }
}

function Get-NixComponentStates {
    $dump = Invoke-Adb @('shell', 'dumpsys', 'package', $NixPower.Package)
    # Scope to user 0; enabled/disabled overrides are stored as component lists.
    $user = [regex]::Match($dump, '(?ms)^    User 0:.*?(?=^    User \d+:|^\S|\z)').Value
    if (-not $user) { throw 'Cannot read Nixplay user 0 component overrides.' }
    $overrides = @{}
    foreach ($section in [regex]::Matches($user, '(?m)^      (enabledComponents|disabledComponents):\r?\n((?:        [^\r\n]+\r?\n?)*)')) {
        $value = if ($section.Groups[1].Value -eq 'enabledComponents') { 1 } else { 2 }
        foreach ($line in ($section.Groups[2].Value -split '\r?\n')) {
            $name = $line.Trim()
            if ($name) { $overrides[$name] = $value }
        }
    }
    foreach ($name in @($NixPower.DisabledComponents) + @($NixPower.PowerReceivers)) {
        $value = if ($overrides.ContainsKey($name)) { $overrides[$name] } else { 0 }
        [pscustomobject]@{ Name = "$($NixPower.Package)/$name"; Enabled = $value }
    }
}

function Set-NixComponentState {
    param([string]$Component, [int]$State)
    if ($Component -notmatch '^com\.kitesystems\.nix\.frame/[\w.$]+$') { throw 'Invalid Nixplay component.' }
    $commands = @{ 0 = 'default-state'; 1 = 'enable'; 2 = 'disable' }
    if (-not $commands.ContainsKey($State)) { throw 'Invalid component state.' }
    $result = Invoke-Adb @('shell', 'run-as', $NixPower.Package, 'pm', $commands[$State], '--user', '0', "'$Component'")
    if ($result -notmatch 'new state:') { throw "Cannot change component state: $result" }
}

function Set-NixPowerOnly {
    Set-PackageState $NixPower.Package 1
    foreach ($name in $NixPower.DisabledComponents) {
        Set-NixComponentState "$($NixPower.Package)/$name" 2
    }
    foreach ($name in $NixPower.PowerReceivers) {
        Set-NixComponentState "$($NixPower.Package)/$name" 1
    }
    $actual = @(Get-NixComponentStates)
    foreach ($item in $actual) {
        $class = $item.Name.Split('/')[1]
        $wanted = if ($NixPower.PowerReceivers -contains $class) { 1 } else { 2 }
        if ($item.Enabled -ne $wanted) { throw "Isolation did not apply to $($item.Name)" }
    }
    # Clear old timers/services. The next explicit power request can cold-start Nixplay.
    Invoke-Adb @('shell', 'am', 'force-stop', $NixPower.Package) | Out-Null
    Set-PackageState 'com.kitesystems.nix.prod' 3
}

# ProcessStartInfo.Arguments uses Windows command-line quoting (including paths with spaces).
function ConvertTo-NativeArgument {
    param([string]$Value)
    return '"' + ([regex]::Replace($Value, '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}

function Invoke-AdbProcess {
    param([string[]]$Arguments, [int]$TimeoutSeconds = 60)
    Write-Host ("ADB: {1}" -f $TimeoutSeconds, ($Arguments -join ' '))
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $script:AdbPath
    $start.Arguments = ($Arguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    $started = $false
    try {
        [void]$process.Start()
        $started = $true
        # Drain both pipes concurrently so large dumps cannot fill a pipe and deadlock.
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            throw "ADB timed out after $TimeoutSeconds seconds: $($Arguments -join ' '). The device operation may still be running; check ADB connectivity before retrying."
        }
        $result = ($stdout.GetAwaiter().GetResult() + "`n" + $stderr.GetAwaiter().GetResult()).Trim()
        if ($process.ExitCode -ne 0) { throw "ADB failed: $($Arguments -join ' ')`n$result" }
        return $result
    } finally {
        if ($started -and -not $process.HasExited) { $process.Kill() }
        $process.Dispose()
    }
}

function Invoke-Adb {
    param([string[]]$Arguments, [int]$TimeoutSeconds = 60)
    Invoke-AdbProcess -Arguments (@('-s', $script:Serial) + $Arguments) -TimeoutSeconds $TimeoutSeconds
}

function Connect-Frame {
    param([string]$RequestedSerial, [string]$RequestedAdb)
    if ($RequestedAdb) { $script:AdbPath = $RequestedAdb }
    elseif ($env:ADB_EXE) { $script:AdbPath = $env:ADB_EXE }
    elseif (Get-Command adb.exe -ErrorAction SilentlyContinue) {
        $script:AdbPath = (Get-Command adb.exe).Source
    } else {
        $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
               elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
               else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
        $script:AdbPath = Join-Path $sdk 'platform-tools\adb.exe'
    }
    if (-not (Test-Path $script:AdbPath -PathType Leaf)) { throw 'ADB not found. Supply -AdbPath or set ADB_EXE.' }
    $devices = (Invoke-AdbProcess -Arguments @('devices', '-l')) -split '\r?\n'
    $connected = @($devices | ForEach-Object {
        if ("$_" -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
    })
    if (-not $RequestedSerial) { $RequestedSerial = $env:ANDROID_SERIAL }
    if ($RequestedSerial) {
        if ($connected -notcontains $RequestedSerial) { throw "Device $RequestedSerial is not connected and authorized.`n$devices" }
        $script:Serial = $RequestedSerial
    } elseif ($connected.Count -eq 1) { $script:Serial = $connected[0] }
    else { throw "Connect exactly one authorized device, or supply -Serial.`n$devices" }
    if ((Invoke-Adb @('shell', 'am', 'get-current-user')) -ne '0') { throw 'Only Android user 0 is supported.' }
    $script:Fingerprint = Invoke-Adb @('shell', 'getprop', 'ro.build.fingerprint')
    Write-Host "Connected: $script:Serial ($script:Fingerprint)"
}

function Get-Home {
    param([switch]$AllowResolver)
    $result = Invoke-Adb @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    $component = @($result -split '\r?\n' | Where-Object { $_ -match '^[\w.$]+/[\w.$]+$' })
    if ($component.Count -ne 1 -or (-not $AllowResolver -and $component[0] -match '^android/')) {
        throw "Cannot identify an unambiguous original HOME activity: $result"
    }
    return $component[0]
}

function Get-PackageState {
    param([string]$Package)
    $dump = Invoke-Adb @('shell', 'dumpsys', 'package', $Package)
    if ($dump -notmatch '(?m)^\s*User 0:.*\binstalled=true\b.*\benabled=(\d+)') {
        throw "Cannot read user 0 enabled state for $Package. Is the package installed?"
    }
    return [int]$Matches[1]
}

function Set-PackageState {
    param([string]$Package, [int]$State)
    $commands = @{ 0 = 'default-state'; 1 = 'enable'; 2 = 'disable'; 3 = 'disable-user'; 4 = 'disable-until-used' }
    if (-not $commands.ContainsKey($State)) { throw "Unsupported package state: $State" }
    Invoke-Adb @('shell', 'pm', $commands[$State], '--user', '0', $Package) | Write-Host
    if ((Get-PackageState $Package) -ne $State) { throw "Failed to restore/set state for $Package" }
}

function Restore-Frame {
    param($State)
    # Attempt every restoration even if one package operation fails.
    $failures = @()
    # Schema 2 snapshots also restore individual overrides changed for power-only mode.
    if ($State.Schema -in @(2, 3)) {
        try { Set-PackageState $NixPower.Package 1 } catch { $failures += $_.Exception.Message }
        foreach ($component in $State.Components) {
            try { Set-NixComponentState $component.Name ([int]$component.Enabled) }
            catch { $failures += $_.Exception.Message }
        }
        try { Invoke-Adb @('shell', 'am', 'force-stop', $NixPower.Package) | Out-Null }
        catch { $failures += $_.Exception.Message }
    }
    foreach ($package in $State.Packages) {
        try { Set-PackageState $package.Name ([int]$package.Enabled) }
        catch { $failures += $_.Exception.Message }
    }
    try {
        if ($State.Home -notmatch '^android/') {
            Invoke-Adb @('shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $State.Home) | Write-Host
        }
        if ((Get-Home -AllowResolver) -ne $State.Home) { throw 'Original HOME did not resolve after restoration.' }
        Invoke-Adb @('shell', 'am', 'start', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME') | Write-Host
        if ($State.Home -ne $FrameHome) {
            # Remove our alarms/jobs once another HOME (or the original chooser) owns startup.
            Invoke-Adb @('shell', 'am', 'force-stop', $FrameApp) | Out-Null
        }
        if ($State.Schema -in @(2, 3)) {
            Invoke-Adb @('shell', 'am', 'broadcast', '--include-stopped-packages',
                '-a', 'com.kitesystems.nix.ACTION_WAKEUP', '-n', "$($NixPower.Package)/.powermanagement.WakeupReceiver",
                '--es', 'WakeupReceiver', 'CustomFrameRollback') | Out-Null
        }
    } catch { $failures += $_.Exception.Message }
    if ($failures.Count) { throw ($failures -join "`n") }
}
