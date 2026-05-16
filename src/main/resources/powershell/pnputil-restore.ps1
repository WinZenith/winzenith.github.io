# Restore driver from backup folder.
param(
    [Parameter(Mandatory = $true)][string]$BackupFolder
)
$ErrorActionPreference = 'Stop'
$infs = Get-ChildItem -Path $BackupFolder -Filter *.inf -Recurse -ErrorAction SilentlyContinue
if (-not $infs) {
    Write-Error "No INF files in $BackupFolder"
    exit 1
}
$infPath = $infs[0].FullName
& pnputil.exe /add-driver $infPath /install
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
@{ success = $true } | ConvertTo-Json -Compress
