$ErrorActionPreference = 'SilentlyContinue'

$shell = New-Object -ComObject Shell.Application
$recycleBin = $shell.NameSpace(0x0a)

if (-not $recycleBin) {
    @{ files = @(); totalSizeBytes = 0; fileCount = 0 } | ConvertTo-Json -Depth 3 -Compress
    return
}

$files = @()
$totalSize = [long]0

$items = $recycleBin.Items()
if ($items) {
    foreach ($item in $items) {
        $name = $item.Name
        $path = $item.Path
        $origPath = ''
        try { $origPath = $item.ExtendedProperty('System.Recycle.OriginalPath') } catch {}
        $size = [long]0
        try { $size = [long]$item.Size } catch {}
        $deleteDate = ''
        try { $deleteDate = $item.ExtendedProperty('System.Recycle.DateDeleted') } catch {}
        $files += [ordered]@{
            name = $name
            originalPath = $origPath
            recyclePath = $path
            sizeBytes = $size
            deleteDate = $deleteDate
        }
        $totalSize += $size
    }
}

@{
    files = $files
    totalSizeBytes = $totalSize
    fileCount = $files.Count
} | ConvertTo-Json -Depth 3 -Compress
