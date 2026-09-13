# Create a system restore point and report the result.
param([string]$Description = 'WinZenith backup')
$ErrorActionPreference = 'Stop'
$success = $false
$seq = -1
$errMsg = ''
try {
    # Snapshot the newest pre-existing point: attributing "latest" afterwards
    # can otherwise report a concurrent creator's point as ours.
    $beforeSeq = -1
    try {
        $pre = Get-ComputerRestorePoint | Sort-Object SequenceNumber -Descending | Select-Object -First 1
        if ($pre) { $beforeSeq = $pre.SequenceNumber }
    } catch {}
    Checkpoint-Computer -Description $Description -RestorePointType MODIFY_SETTINGS
    $success = $true
    try {
        $cands = Get-ComputerRestorePoint | Where-Object { $_.SequenceNumber -gt $beforeSeq } | Sort-Object SequenceNumber -Descending
        $mine = $cands | Where-Object { $_.Description -eq $Description } | Select-Object -First 1
        if ($mine) { $seq = $mine.SequenceNumber }
    } catch {}
} catch {
    $success = $false
    $errMsg = $_.Exception.Message
    # Detect frequency limit or System Protection disabled for better UX.
    # 0x80042316 (VSS storage/snapshot failure) is NOT a frequency skip: it
    # means no restore point exists, so it must never map to FREQUENCY_LIMIT
    # (callers treat that prefix as "safety net present" and would install
    # with no rollback when the backup also failed).
    if ($errMsg -match '0x80042316') {
        $errMsg = 'VSS_ERROR: Shadow-copy storage failed (VSS 0x80042316, often free space on the shadow volume). ' + $errMsg
    } elseif ($errMsg -match 'already.*24.*hour' -or $errMsg -match 'frequency') {
        $errMsg = 'FREQUENCY_LIMIT: A restore point was already created within the last 24 hours (Windows default). ' + $errMsg
    } elseif ($errMsg -match 'System Protection' -or $errMsg -match 'disabled' -or $errMsg -match '0x80070422') {
        $errMsg = 'PROTECTION_DISABLED: System Protection is disabled. ' + $errMsg
    }
}
@{ success = $success; sequenceNumber = $seq; error = $errMsg } | ConvertTo-Json -Compress
if (-not $success) { exit 1 }
exit 0
