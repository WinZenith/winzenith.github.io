$ErrorActionPreference = 'SilentlyContinue'
$drives = @()
Get-Volume | Where-Object { $_.DriveType -eq 'Fixed' -and $_.DriveLetter } | ForEach-Object {
    $vol = $_
    $letter = $vol.DriveLetter + ':'
    $disk = Get-Partition -DriveLetter $vol.DriveLetter -ErrorAction SilentlyContinue | Get-Disk -ErrorAction SilentlyContinue
    $phys = $null
    $mediaType = 'Unknown'
    if ($disk) {
        $phys = Get-PhysicalDisk -DeviceNumber $disk.Number -ErrorAction SilentlyContinue
        if ($phys) {
            $mediaType = if ($phys.MediaType -eq 'HDD' -or $phys.MediaType -eq 'SSD') { $phys.MediaType } else { 'Unknown' }
        }
    }
    $drives += [ordered]@{
        driveLetter  = $letter
        volumeLabel  = if ($vol.FileSystemLabel) { $vol.FileSystemLabel } else { '' }
        mediaType    = $mediaType
        fileSystem   = if ($vol.FileSystem) { $vol.FileSystem } else { '' }
        sizeBytes    = if ($vol.Size) { [uint64]$vol.Size } else { 0 }
        freeBytes    = if ($vol.SizeRemaining) { [uint64]$vol.SizeRemaining } else { 0 }
    }
}
$drives | ConvertTo-Json -Depth 3 -Compress
