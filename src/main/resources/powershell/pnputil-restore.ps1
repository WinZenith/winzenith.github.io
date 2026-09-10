# Restore driver from backup folder. Safer order: install first, then remove old only if needed.
# Args: BackupFolder DeviceId
param(
    [Parameter(Mandatory = $true)][string]$BackupFolder,
    [Parameter(Mandatory = $false)][string]$DeviceId
)
$ErrorActionPreference = 'Stop'
$infs = Get-ChildItem -Path $BackupFolder -Filter *.inf -Recurse -ErrorAction SilentlyContinue
if (-not $infs) {
    Write-Error "No INF files in $BackupFolder"
    exit 1
}

# Phase 1: Install backed-up drivers first (never delete current driver automatically - destructive)
$failed = 0
$installed = 0
$installOutputs = @()
foreach ($inf in $infs) {
    $out = & pnputil.exe /add-driver $inf.FullName /install 2>&1 | Out-String
    $installOutputs += "$($inf.Name): exit=$LASTEXITCODE $out"
    if ($LASTEXITCODE -ne 0) { $failed++ } else { $installed++ }
}
# Downgrade path is intentionally NOT automatic. Deleting the current driver with
# /delete-driver /uninstall /force is destructive and can leave the device driverless
# if the backup INF is incompatible. Instead we report failure and let the UI suggest
# manual Device Manager -> Rollback or reboot.
$removedCurrent = $false
$retryFailed = $failed
# NOTE: Previous versions attempted to delete current OEM INF and retry. That is disabled
# for safety. If $installed -eq 0, the caller should surface installOutputs to the user
# and suggest: reboot, Device Manager -> Update driver -> Browse -> Let me pick -> Have Disk.
if ($installed -eq 0) {
    # Capture diagnostic info about current driver without deleting
    $currentInf = ''
    try {
        $prop = Get-PnpDeviceProperty -InstanceId $DeviceId -KeyName 'DEVPKEY_Device_DriverInfPath' -ErrorAction SilentlyContinue
        if ($prop -and $prop.Data) { $currentInf = [string]$prop.Data }
    } catch {}
    if ($currentInf -match '[\\/]([^\\/]+\.inf)$') { $currentInf = $Matches[1] }
    # Do not delete - only log
    if ($currentInf) {
        $installOutputs += "Current driver INF: $currentInf (not removed for safety)"
    }
}
# Trigger device rescan so driver becomes active without reboot if possible
try { & pnputil.exe /scan-devices 2>&1 | Out-Null } catch {}

# Best-effort bind: staging via /add-driver does not always switch the active
# driver on a downgrade path. Attempt a non-destructive device restart so the
# staged (older) driver can bind without a reboot. Never fails the restore —
# if the restart is unavailable the caller falls back to reboot/manual steps.
$restartAttempted = $false
$restartOk = $false
if ($DeviceId -and $DeviceId.Trim()) {
    $restartAttempted = $true
    try {
        $r = & pnputil.exe /restart-device $DeviceId.Trim() 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0) { $restartOk = $true }
        else { $installOutputs += "restart-device exit=$LASTEXITCODE $r" }
    } catch {
        $installOutputs += "restart-device unavailable: $($_.Exception.Message)"
    }
    if (-not $restartOk) {
        try {
            $dev = Get-PnpDevice -InstanceId $DeviceId.Trim() -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($dev) {
                try { Disable-PnpDevice -InstanceId $dev.InstanceId -Confirm:$false -ErrorAction SilentlyContinue } catch {}
                Start-Sleep -Milliseconds 1500
                try { Enable-PnpDevice -InstanceId $dev.InstanceId -Confirm:$false -ErrorAction SilentlyContinue; $restartOk = $true } catch {}
            }
        } catch {}
    }
}

if ($failed -eq $infs.Count) {
    $details = ($installOutputs -join " | ").Trim()
    @{ success = $false; installed = $installed; failed = $failed; removedCurrent = $removedCurrent; restartAttempted = $restartAttempted; restartOk = $restartOk; details = $details } | ConvertTo-Json -Compress
    exit 1
}

$details = ($installOutputs -join " | ").Trim()
@{ success = ($failed -eq 0); installed = $installed; failed = $failed; removedCurrent = $removedCurrent; restartAttempted = $restartAttempted; restartOk = $restartOk; details = $details } | ConvertTo-Json -Compress
if ($failed -gt 0) { exit 1 }
exit 0
