# Export driver package for backup. Args: InfName BackupFolder
param(
    [Parameter(Mandatory = $true)][string]$InfName,
    [Parameter(Mandatory = $true)][string]$BackupFolder
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $BackupFolder | Out-Null
if ($InfName -match '^oem\d+\.inf$') {
    & pnputil.exe /export-driver $InfName $BackupFolder
} else {
    # Try to find oem inf from enum-drivers matching published name
    $enum = & pnputil.exe /enum-drivers 2>&1 | Out-String
    $oem = $null
    if ($enum -match "Published Name\s*:\s*(oem\d+\.inf)[\s\S]*?Original Name\s*:\s*$([regex]::Escape($InfName))") {
        $oem = $Matches[1]
    }
    if (-not $oem) {
        Write-Error "Could not resolve OEM INF for $InfName"
        exit 1
    }
    & pnputil.exe /export-driver $oem $BackupFolder
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
@{ success = $true; folder = $BackupFolder } | ConvertTo-Json -Compress
