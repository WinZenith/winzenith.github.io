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
    $escapedInf = [regex]::Escape($InfName)
    $entries = $enum -split '\r?\n\r?\n'
    foreach ($entry in $entries) {
        if ($entry -match "Published Name\s*:\s*(oem\d+\.inf)" -and $entry -match "Original Name\s*:\s*$escapedInf") {
            $oem = $Matches[1]
            break
        }
    }
    if (-not $oem) {
        Write-Error "Could not resolve OEM INF for $InfName"
        exit 1
    }
    & pnputil.exe /export-driver $oem $BackupFolder
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
@{ success = $true; folder = $BackupFolder } | ConvertTo-Json -Compress
