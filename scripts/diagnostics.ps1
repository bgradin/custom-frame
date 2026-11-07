# Read-only collection; does not restart the app or clear Android log buffers.
[CmdletBinding()]
param([string]$Serial, [string]$AdbPath, [string]$OutputDirectory)
. "$PSScriptRoot\provision-common.ps1"
Connect-Frame $Serial $AdbPath
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $ProjectRoot ('.diagnostics/' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))
}
$null = New-Item -ItemType Directory -Force -Path $OutputDirectory
foreach ($file in @('events.log', 'events.1.log', 'events.2.log', 'events.3.log')) {
    try {
        Invoke-Adb @('shell', 'run-as', $FrameApp, 'cat', "files/diagnostics/$file") |
            Set-Content -Encoding UTF8 (Join-Path $OutputDirectory $file)
    } catch { Write-Warning "Could not collect ${file}: $_" }
}
foreach ($item in @(
    @{ Name = 'logcat.txt'; Args = @('logcat', '-b', 'all', '-d', '-v', 'threadtime') },
    @{ Name = 'anr.txt'; Args = @('shell', 'dumpsys', 'dropbox', '--print', 'data_app_anr') },
    @{ Name = 'crashes.txt'; Args = @('shell', 'dumpsys', 'dropbox', '--print', 'data_app_crash') },
    @{ Name = 'memory.txt'; Args = @('shell', 'dumpsys', 'meminfo', $FrameApp) },
    @{ Name = 'activity.txt'; Args = @('shell', 'dumpsys', 'activity', 'activities') }
)) {
    try { Invoke-Adb $item.Args | Set-Content -Encoding UTF8 (Join-Path $OutputDirectory $item.Name) }
    catch { Write-Warning "Could not collect $($item.Name): $_" }
}
Write-Host "Diagnostics saved to $OutputDirectory. Android reports may contain private system or application data; review before sharing."
