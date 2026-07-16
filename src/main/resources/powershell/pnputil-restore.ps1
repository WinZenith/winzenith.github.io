# Restore driver from backup folder. Installs all INF files found in the backup.
param(
    [Parameter(Mandatory = $true)][string]$BackupFolder
)
$ErrorActionPreference = 'Stop'
$infs = Get-ChildItem -Path $BackupFolder -Filter *.inf -Recurse -ErrorAction SilentlyContinue
if (-not $infs) {
    Write-Error "No INF files in $BackupFolder"
    exit 1
}
$failed = 0
$installed = 0
foreach ($inf in $infs) {
    & pnputil.exe /add-driver $inf.FullName /install
    if ($LASTEXITCODE -ne 0) { $failed++ } else { $installed++ }
}
if ($failed -eq $infs.Count) { exit 1 }
@{ success = ($failed -eq 0); installed = $installed; failed = $failed } | ConvertTo-Json -Compress
if ($failed -gt 0) { exit 1 }
exit 0
