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
foreach ($inf in $infs) {
    & pnputil.exe /add-driver $inf.FullName /install
    if ($LASTEXITCODE -ne 0) { $failed++ }
}
if ($failed -eq $infs.Count) { exit 1 }
@{ success = $true; installed = ($infs.Count - $failed); failed = $failed } | ConvertTo-Json -Compress
