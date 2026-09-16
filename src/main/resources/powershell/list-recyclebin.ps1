$ErrorActionPreference = 'SilentlyContinue'

$shell = New-Object -ComObject Shell.Application
$recycleBin = $shell.NameSpace(0x0a)

if (-not $recycleBin) {
    @{ files = @(); totalSizeBytes = 0; fileCount = 0 } | ConvertTo-Json -Depth 3 -Compress
    return
}

$files = @()
$totalSize = [long]0

function Test-RecycleBinStoragePath {
    param([string]$Candidate)
    if (-not $Candidate) { return $false }
    return ($Candidate -match '(?i)^[A-Z]:\\\$Recycle\.Bin\\.+$')
}

$items = $recycleBin.Items()
if ($items) {
    foreach ($item in $items) {
        $name = $item.Name
        $origPath = ''
        try { $origPath = $item.ExtendedProperty('System.Recycle.OriginalPath') } catch {}
        # Prefer a real $Recycle.Bin\$R... storage path. Never fall back to
        # OriginalPath (that would shred a live file if the user recreated it).
        $recyclePath = ''
        $path = $item.Path
        if (Test-RecycleBinStoragePath $path) { $recyclePath = $path }
        if (-not $recyclePath) {
            foreach ($prop in @('System.ParsingPath', 'System.ItemPathDisplay')) {
                try {
                    $alt = $item.ExtendedProperty($prop)
                    if (Test-RecycleBinStoragePath $alt) { $recyclePath = $alt; break }
                } catch {}
            }
        }
        $size = [long]0
        try { $size = [long]$item.Size } catch {}
        $deleteDate = ''
        try { $deleteDate = $item.ExtendedProperty('System.Recycle.DateDeleted') } catch {}
        $files += [ordered]@{
            name = $name
            originalPath = $origPath
            recyclePath = $recyclePath
            sizeBytes = $size
            deleteDate = $deleteDate
        }
        $totalSize += $size
    }
}

ConvertTo-Json -InputObject ([ordered]@{
    files = @($files)
    totalSizeBytes = $totalSize
    fileCount = @($files).Count
}) -Depth 3 -Compress
