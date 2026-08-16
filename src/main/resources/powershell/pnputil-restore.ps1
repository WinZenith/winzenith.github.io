# Restore driver from backup folder. Removes the current driver first to allow downgrade.
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

# Phase 1: Find and remove the currently active driver for this device (enables downgrade)
$removedCurrent = $false
if ($DeviceId) {
    $currentInf = ''
    try {
        $prop = Get-PnpDeviceProperty -InstanceId $DeviceId -KeyName 'DEVPKEY_Device_DriverInfPath' -ErrorAction SilentlyContinue
        if ($prop -and $prop.Data) { $currentInf = [string]$prop.Data }
    } catch {}

    # Extract filename if a full path was returned
    if ($currentInf -match '[\\/]([^\\/]+\.inf)$') { $currentInf = $Matches[1] }

    if ($currentInf -and $currentInf -match '^oem\d+\.inf$') {
        & pnputil.exe /delete-driver $currentInf /uninstall /force 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { $removedCurrent = $true }
    }
}

# Phase 2: Install backed-up drivers
$failed = 0
$installed = 0
foreach ($inf in $infs) {
    & pnputil.exe /add-driver $inf.FullName /install
    if ($LASTEXITCODE -ne 0) { $failed++ } else { $installed++ }
}
if ($failed -eq $infs.Count) { exit 1 }

@{ success = ($failed -eq 0); installed = $installed; failed = $failed; removedCurrent = $removedCurrent } | ConvertTo-Json -Compress
if ($failed -gt 0) { exit 1 }
exit 0
