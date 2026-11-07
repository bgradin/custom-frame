[CmdletBinding()]
param([string]$Serial, [string]$AdbPath, [switch]$CheckOnly)
. "$PSScriptRoot\provision-common.ps1"

Connect-Frame $Serial $AdbPath
$safeSerial = $script:Serial -replace '[^A-Za-z0-9_.-]', '_'
$folder = Join-Path $ProjectRoot ".rollback\$safeSerial"
$statePath = Join-Path $folder 'state.json'
if (-not (Test-Path $statePath)) { throw "No rollback snapshot found at $statePath" }
$state = Get-Content -Raw $statePath | ConvertFrom-Json
if ($state.Schema -notin @(1, 2, 3) -or $state.Serial -ne $script:Serial -or $state.Fingerprint -ne $script:Fingerprint) {
    throw 'Snapshot schema, device identity, or firmware differs. Refusing to restore another configuration.'
}
if ($state.Schema -eq 3) {
    # Restore against the captured profile, even after repository profiles change.
    $NixPower = Read-PowerProfile -Profile $state.PowerProfile
}
if ($state.Home -notmatch '^[\w.$]+/[\w.$]+$') { throw 'Invalid saved HOME component.' }
if (@($state.Packages).Count -ne 2) { throw 'Invalid saved package list.' }
foreach ($name in $NixPackages) {
    $entry = @($state.Packages | Where-Object { $_.Name -eq $name })
    if ($entry.Count -ne 1 -or "$($entry[0].Enabled)" -notmatch '^[0-4]$') { throw "Invalid saved state for $name" }
}
if ($state.Schema -in @(2, 3)) {
    $expected = @($NixPower.DisabledComponents) + @($NixPower.PowerReceivers)
    if (@($state.Components).Count -ne $expected.Count) { throw 'Invalid saved component list.' }
    foreach ($name in $expected) {
        $component = "$($NixPower.Package)/$name"
        $entry = @($state.Components | Where-Object { $_.Name -eq $component })
        if ($entry.Count -ne 1 -or "$($entry[0].Enabled)" -notmatch '^[0-2]$') {
            throw "Invalid saved state for $component"
        }
    }
}
Write-Host "Will restore HOME $($state.Home) and the recorded Nixplay package/component states."
if ($CheckOnly) { return }
Restore-Frame $state
# Archive only after success; retain the original record, and allow a fresh setup later.
$archive = Join-Path $folder (('restored-{0}-{1}.json' -f [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'), [guid]::NewGuid().ToString('N')))
Move-Item $statePath $archive
Write-Host "Rollback complete. Snapshot retained at $archive. No reboot performed."
