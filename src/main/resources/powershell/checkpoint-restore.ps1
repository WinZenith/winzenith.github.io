# Create a system restore point and report the result.
param([string]$Description = 'WinZenith backup')
$ErrorActionPreference = 'Stop'
$success = $false
$seq = -1
$errMsg = ''
try {
    Checkpoint-Computer -Description $Description -RestorePointType MODIFY_SETTINGS
    $success = $true
    try {
        $latest = Get-ComputerRestorePoint | Sort-Object SequenceNumber -Descending | Select-Object -First 1
        if ($latest) { $seq = $latest.SequenceNumber }
    } catch {}
} catch {
    $success = $false
    $errMsg = $_.Exception.Message
    # Detect frequency limit or System Protection disabled for better UX
    if ($errMsg -match 'already.*24.*hour' -or $errMsg -match 'frequency' -or $errMsg -match '0x80042316') {
        $errMsg = 'FREQUENCY_LIMIT: A restore point was already created within the last 24 hours (Windows default). ' + $errMsg
    } elseif ($errMsg -match 'System Protection' -or $errMsg -match 'disabled' -or $errMsg -match '0x80070422') {
        $errMsg = 'PROTECTION_DISABLED: System Protection is disabled. ' + $errMsg
    }
}
@{ success = $success; sequenceNumber = $seq; error = $errMsg } | ConvertTo-Json -Compress
if (-not $success) { exit 1 }
exit 0
