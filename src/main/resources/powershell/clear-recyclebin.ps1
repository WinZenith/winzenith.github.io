# Clears the Recycle Bin on all drives.
$ErrorActionPreference = 'Stop'
try {
    Clear-RecycleBin -Force -ErrorAction Stop
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
