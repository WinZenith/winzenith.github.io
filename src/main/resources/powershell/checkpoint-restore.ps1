# Optional system restore point (best-effort).
param([string]$Description = 'WinZenith backup')
$ErrorActionPreference = 'SilentlyContinue'
Checkpoint-Computer -Description $Description -RestorePointType MODIFY_SETTINGS
$seq = -1
try {
    $latest = Get-ComputerRestorePoint | Sort-Object SequenceNumber -Descending | Select-Object -First 1
    if ($latest) { $seq = $latest.SequenceNumber }
} catch {}
@{ attempted = $true; sequenceNumber = $seq } | ConvertTo-Json -Compress
