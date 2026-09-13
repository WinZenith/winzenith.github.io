param(
    [Parameter(Mandatory = $true)][string]$ArchivePath,
    [Parameter(Mandatory = $true)][string]$Destination
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ArchivePath)) {
    Write-Error "Archive not found: $ArchivePath"
    exit 1
}
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$ext = [System.IO.Path]::GetExtension($ArchivePath).ToLowerInvariant()
if ($ext -eq '.zip') {
    Expand-Archive -LiteralPath $ArchivePath -DestinationPath $Destination -Force
    exit 0
}
if ($ext -eq '.cab') {
    $expand = Join-Path $env:SystemRoot 'System32\expand.exe'
    if (-not (Test-Path -LiteralPath $expand)) {
        Write-Error "expand.exe not found"
        exit 1
    }
    & $expand $ArchivePath -F:* $Destination
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    exit 0
}
Write-Error "Unsupported archive type: $ext"
exit 1
