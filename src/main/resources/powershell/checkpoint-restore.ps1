# Optional system restore point (best-effort).
param([string]$Description = 'SBasic Driver Updater backup')
$ErrorActionPreference = 'SilentlyContinue'
Checkpoint-Computer -Description $Description -RestorePointType MODIFY_SETTINGS
@{ attempted = $true } | ConvertTo-Json -Compress
