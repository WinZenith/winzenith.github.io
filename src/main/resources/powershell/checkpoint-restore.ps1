# Create a system restore point and report the result.
param([string]$Description = 'WinZenith backup')
$ErrorActionPreference = 'Stop'
$success = $false
$seq = -1
try {
    Checkpoint-Computer -Description $Description -RestorePointType MODIFY_SETTINGS
    $success = $true
    try {
        $latest = Get-ComputerRestorePoint | Sort-Object SequenceNumber -Descending | Select-Object -First 1
        if ($latest) { $seq = $latest.SequenceNumber }
    } catch {}
} catch {
    $success = $false
}
@{ success = $success; sequenceNumber = $seq } | ConvertTo-Json -Compress
if (-not $success) { exit 1 }
exit 0
