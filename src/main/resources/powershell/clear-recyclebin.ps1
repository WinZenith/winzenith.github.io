# Clears the Recycle Bin on all drives.
$ErrorActionPreference = 'SilentlyContinue'
Clear-RecycleBin -Force -ErrorAction SilentlyContinue
exit 0
