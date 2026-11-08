# Dependency-free checks: powershell.exe -NoProfile -File tests/FrameScripts.Tests.ps1
$ErrorActionPreference = 'Stop'
$root = Join-Path (Split-Path $PSScriptRoot) 'scripts'
foreach ($file in @('provision-common.ps1', 'provision.ps1', 'provision-rollback.ps1', 'reboot.ps1', 'launch.ps1')) {
    $parseTokens = $null
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $root $file), [ref]$parseTokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw "$file has syntax errors: $parseErrors" }
}
. (Join-Path $root 'provision-common.ps1')
function Assert($Condition, $Message) { if (-not $Condition) { throw $Message } }

# All bundled profiles must validate, with unambiguous version codes and IDs.
$profiles = @(Get-ChildItem (Join-Path $ProjectRoot 'app/src/main/assets/device-profiles') -Filter '*.json' |
    ForEach-Object { Read-DeviceProfile $_.FullName })
Assert ($profiles.Count -gt 0) 'No bundled device profiles.'
Assert (@($profiles.VersionCode | Select-Object -Unique).Count -eq $profiles.Count) 'Ambiguous version codes.'
Assert (@($profiles.Id | Select-Object -Unique).Count -eq $profiles.Count) 'Duplicate profile IDs.'
$alternate = $profiles[0] | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$alternate.VersionCode = 999999
$alternate.Id = 'test-alternate'
$validated = Read-DeviceProfile -Profile $alternate
Assert ($validated.VersionCode -eq 999999) 'Alternate versions should not require script changes.'
$alternate.PowerReceivers = @('unsupported.Receiver')
$rejected = $false
try { Read-DeviceProfile -Profile $alternate | Out-Null } catch { $rejected = $true }
Assert $rejected 'Unsupported receiver protocols must be rejected.'

$script:testVersion = $profiles[0].VersionCode
function Invoke-Adb {
    param([string[]]$Arguments)
    if ($Arguments -contains 'run-as') { return 'uid=1000' }
    return "versionCode=$script:testVersion minSdk=25"
}
Assert-NixDeviceSupport
Assert ($NixDeviceProfile.Id -eq $profiles[0].Id) 'Installed version selected the wrong profile.'
$script:testVersion = 999999
$rejected = $false
try { Assert-NixDeviceSupport } catch { $rejected = $true }
Assert $rejected 'Unknown versions must fail preflight.'

# Parse per-user overrides, including explicit enable and untouched defaults.
function Invoke-Adb {
    param([string[]]$Arguments)
    @'
Packages:
    User 0: installed=true enabled=0
      enabledComponents:
        com.kitesystems.nix.frame.powermanagement.SleepReceiver
      disabledComponents:
        com.kitesystems.nix.frame.LaunchActivity
    User 10: installed=true enabled=0
      disabledComponents:
        com.kitesystems.nix.frame.powermanagement.WakeupReceiver
Dexopt state:
'@
}
$components = @(Get-NixComponentStates)
Assert ($components.Count -eq (@($NixDeviceProfile.DisabledComponents).Count + @($NixDeviceProfile.PowerReceivers).Count)) 'Snapshot must include every changed component.'
Assert (($components | Where-Object Name -match '/com.kitesystems.nix.frame.LaunchActivity$').Enabled -eq 2) 'Disabled override lost.'
Assert (($components | Where-Object Name -match '/com.kitesystems.nix.frame.powermanagement.SleepReceiver$').Enabled -eq 1) 'Enabled override lost.'
Assert (($components | Where-Object Name -match '/com.kitesystems.nix.frame.powermanagement.WakeupReceiver$').Enabled -eq 0) 'Another user leaked into snapshot.'

$script:calls = [System.Collections.Generic.List[string]]::new()
function Set-PackageState { param($Package, $State) $script:calls.Add("package:$Package=$State") }
function Set-NixComponentState { param($Component, $State) $script:calls.Add("component:$Component=$State") }
function Invoke-Adb { param($Arguments) $script:calls.Add(($Arguments -join ' ')); return '' }
$script:testHome = 'com.kitesystems.nix.frame/.LaunchActivity'
function Get-Home { param([switch]$AllowResolver) return $script:testHome }
$snapshot = [pscustomobject]@{
    Schema = 2; Home = $script:testHome; Components = $components
    Packages = @([pscustomobject]@{Name='com.kitesystems.nix.frame';Enabled=0}, [pscustomobject]@{Name='com.kitesystems.nix.prod';Enabled=3})
}
Restore-Frame $snapshot
Assert ($script:calls -contains 'component:com.kitesystems.nix.frame/com.kitesystems.nix.frame.LaunchActivity=2') 'Rollback lost original component override.'
Assert ($script:calls -contains 'package:com.kitesystems.nix.prod=3') 'Rollback lost original package state.'
$script:calls.Clear()
$snapshot.Schema = 1
Restore-Frame $snapshot
Assert (@($script:calls | Where-Object { $_ -like 'component:*' }).Count -eq 0) 'Legacy snapshot must not change components.'
$script:calls.Clear()
$snapshot.Schema = 2
$snapshot.Home = 'android/com.android.internal.app.ResolverActivity'
$script:testHome = $snapshot.Home
Restore-Frame $snapshot
Assert (@($script:calls | Where-Object { $_ -like '*set-home-activity*' }).Count -eq 0) 'Unassigned HOME must stay unassigned on rollback.'
$script:calls.Clear()
$snapshot.Schema = 3
Restore-Frame $snapshot
Assert ($script:calls -contains 'component:com.kitesystems.nix.frame/com.kitesystems.nix.frame.LaunchActivity=2') 'Schema 3 rollback lost component states.'
Write-Host 'Device profiles, frame script parsing, component snapshots, and schema 1/2/3 rollback checks passed.'
