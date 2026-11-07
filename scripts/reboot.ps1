# Restart a connected Nixplay through ADB; the battery remains connected.
[CmdletBinding()]
param([string]$Serial, [string]$AdbPath, [switch]$CheckOnly)
. "$PSScriptRoot\provision-common.ps1"

Connect-Frame $Serial $AdbPath
# Check the vendor package even when setup has disabled its UI or services.
$null = Get-PackageState 'com.kitesystems.nix.frame'

if ($CheckOnly) {
    Write-Host 'Nixplay is connected and authorized. No reboot performed.'
    return
}

Write-Host "Rebooting Nixplay $script:Serial..."
Invoke-Adb @('reboot') | Write-Host
Write-Host 'Reboot command accepted. ADB will disconnect while Android restarts.'
