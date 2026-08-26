param([switch]$IncludeRemovable, [switch]$IncludeNetwork)
# Default behaviour for Disk Tools: include Fixed + Removable (USB sticks / external SSDs)
# so portable/user-expects-visible drives are not hidden. Network drives remain excluded
# unless -IncludeNetwork is explicitly passed.
if (-not $PSBoundParameters.ContainsKey('IncludeRemovable')) { $IncludeRemovable = $true }
$ErrorActionPreference = 'Stop'

$drives = @()
$enumerationErrors = @()

function Add-DriveEntry {
    param([string]$Letter, [string]$Label, [string]$MediaType, [string]$Fs, [uint64]$Size, [uint64]$Free)
    $script:drives += [ordered]@{
        driveLetter = $Letter
        volumeLabel = $Label
        mediaType   = $MediaType
        fileSystem  = $Fs
        sizeBytes   = $Size
        freeBytes   = $Free
    }
}

function Resolve-MediaType {
    param([string]$DriveLetterNoColon)
    try {
        $part = Get-Partition -DriveLetter $DriveLetterNoColon -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $part) { return 'Unknown' }
        $disk = Get-Disk -Number $part.DiskNumber -ErrorAction SilentlyContinue
        if (-not $disk) { return 'Unknown' }
        $phys = Get-PhysicalDisk -DeviceNumber $disk.Number -ErrorAction SilentlyContinue
        if (-not $phys -or -not $phys.MediaType) { return 'Unknown' }
        $mt = $phys.MediaType.ToString()
        if ($mt -eq 'HDD' -or $mt -eq 'SSD') { return $mt }
        # NVMe with MediaType Unspecified -> treat as SSD via BusType check
        if ($phys.BusType -eq 'NVMe' -or $phys.BusType -eq 17) { return 'SSD' }
        return 'Unknown'
    } catch { return 'Unknown' }
}

# ── Stage 1: Primary — Get-Volume (Storage module) ──
try {
    $volumes = Get-Volume -ErrorAction Stop | Where-Object {
        $_.DriveLetter -and $_.DriveLetter -ne [char]0 -and (
            $_.DriveType -eq 'Fixed' -or
            ($IncludeRemovable -and $_.DriveType -eq 'Removable') -or
            ($IncludeNetwork -and $_.DriveType -eq 'Network')
        )
    }
    foreach ($vol in $volumes) {
        $letter = "$($vol.DriveLetter):"
        $letterNoColon = $vol.DriveLetter.ToString()
        $mediaType = Resolve-MediaType -DriveLetterNoColon $letterNoColon
        Add-DriveEntry -Letter $letter `
                       -Label $(if ($vol.FileSystemLabel) { $vol.FileSystemLabel } else { '' }) `
                       -MediaType $mediaType `
                       -Fs $(if ($vol.FileSystem) { $vol.FileSystem } else { '' }) `
                       -Size $(if ($vol.Size) { [uint64]$vol.Size } else { 0 }) `
                       -Free $(if ($vol.SizeRemaining) { [uint64]$vol.SizeRemaining } else { 0 })
    }
} catch {
    $enumerationErrors += "Get-Volume failed: $($_.Exception.Message)"
}

# ── Stage 2: Fallback — Win32_LogicalDisk (WMI, no Storage module needed) ──
if ($drives.Count -eq 0) {
    try {
        $filter = "DriveType=3"
        if ($IncludeRemovable) { $filter += " or DriveType=2" }
        if ($IncludeNetwork)   { $filter += " or DriveType=4" }
        $logical = Get-CimInstance -ClassName Win32_LogicalDisk -Filter $filter -ErrorAction Stop
        if ($logical) {
            foreach ($ld in $logical) {
                $deviceId = $ld.DeviceID  # e.g. "C:"
                if (-not $deviceId) { continue }
                $letterNoColon = $deviceId.Replace(':', '').Trim()
                if (-not $letterNoColon) { continue }
                $mediaType = Resolve-MediaType -DriveLetterNoColon $letterNoColon
                # Win32_LogicalDisk Size/FreeSpace may be null for empty drives
                $size = if ($ld.Size) { [uint64]$ld.Size } else { 0 }
                $free = if ($ld.FreeSpace) { [uint64]$ld.FreeSpace } else { 0 }
                # If size is 0, try to enrich from Get-Volume info already gathered or from WMI Volume
                Add-DriveEntry -Letter "$deviceId" `
                               -Label $(if ($ld.VolumeName) { $ld.VolumeName } else { '' }) `
                               -MediaType $mediaType `
                               -Fs $(if ($ld.FileSystem) { $ld.FileSystem } else { '' }) `
                               -Size $size -Free $free
            }
        }
    } catch {
        $enumerationErrors += "Win32_LogicalDisk failed: $($_.Exception.Message)"
    }
}

# ── Stage 3: Fallback — Get-PSDrive ──
if ($drives.Count -eq 0) {
    try {
        $psDrives = Get-PSDrive -PSProvider FileSystem -ErrorAction Stop | Where-Object { $_.Root -match '^[A-Z]:\\$' }
        foreach ($pd in $psDrives) {
            $root = $pd.Root  # "C:\"
            $letter = $root.Substring(0,2)  # "C:"
            $letterNoColon = $letter.Replace(':', '')
            # Filter network vs removable via underlying DriveType if possible
            try {
                $ld = Get-CimInstance -ClassName Win32_LogicalDisk -Filter "DeviceID='$letter'" -ErrorAction SilentlyContinue
                if ($ld) {
                    $dt = [int]$ld.DriveType
                    $isFixed = ($dt -eq 3)
                    $isRemovable = ($dt -eq 2)
                    $isNetwork = ($dt -eq 4)
                    if ($isNetwork -and -not $IncludeNetwork) { continue }
                    if ($isRemovable -and -not $IncludeRemovable) { continue }
                    if (-not ($isFixed -or $isRemovable -or $isNetwork)) { continue }
                }
            } catch { }
            $mediaType = Resolve-MediaType -DriveLetterNoColon $letterNoColon
            $size = if ($pd.Used -ne $null -and $pd.Free -ne $null) { [uint64]($pd.Used + $pd.Free) } else { 0 }
            $free = if ($pd.Free -ne $null) { [uint64]$pd.Free } else { 0 }
            # Labels/FileSystem not available via PSDrive — leave blank / try WMI enrichment
            $label = ""
            $fs = ""
            try {
                $volInfo = Get-Volume -DriveLetter $letterNoColon -ErrorAction SilentlyContinue
                if ($volInfo) {
                    if ($volInfo.FileSystemLabel) { $label = $volInfo.FileSystemLabel }
                    if ($volInfo.FileSystem) { $fs = $volInfo.FileSystem }
                    if ($volInfo.Size) { $size = [uint64]$volInfo.Size }
                    if ($volInfo.SizeRemaining) { $free = [uint64]$volInfo.SizeRemaining }
                }
            } catch { }
            Add-DriveEntry -Letter $letter -Label $label -MediaType $mediaType -Fs $fs -Size $size -Free $free
        }
    } catch {
        $enumerationErrors += "Get-PSDrive failed: $($_.Exception.Message)"
    }
}

# ── Stage 4: Final fallback — System.IO.DriveInfo ──
if ($drives.Count -eq 0) {
    try {
        $ioDrives = [System.IO.DriveInfo]::GetDrives() | Where-Object { $_.IsReady -and $_.Name -match '^[A-Z]:\\$' }
        foreach ($iod in $ioDrives) {
            $letter = $iod.Name.Substring(0,2)
            $letterNoColon = $letter.Replace(':', '')
            $driveType = $iod.DriveType.ToString()  # Fixed, Removable, Network, etc.
            if ($driveType -eq 'Network' -and -not $IncludeNetwork) { continue }
            if ($driveType -eq 'Removable' -and -not $IncludeRemovable) { continue }
            if ($driveType -notin @('Fixed','Removable','Network')) { continue }
            $mediaType = Resolve-MediaType -DriveLetterNoColon $letterNoColon
            $size = 0; $free = 0
            try { $size = [uint64]$iod.TotalSize } catch { $size = 0 }
            try { $free = [uint64]$iod.AvailableFreeSpace } catch { $free = 0 }
            Add-DriveEntry -Letter $letter -Label $(if ($iod.VolumeLabel) { $iod.VolumeLabel } else { '' }) -MediaType $mediaType -Fs $(if ($iod.DriveFormat) { $iod.DriveFormat } else { '' }) -Size $size -Free $free
        }
    } catch {
        $enumerationErrors += "DriveInfo failed: $($_.Exception.Message)"
    }
}

# Deduplicate by driveLetter (first wins — from most reliable source)
$unique = @{}
$deduped = @()
foreach ($d in $drives) {
    $key = $d.driveLetter.ToUpper()
    if (-not $unique.ContainsKey($key)) {
        $unique[$key] = $true
        $deduped += $d
    }
}
$drives = $deduped

# ── Emit JSON ──
# Always emit a JSON array via -InputObject to avoid PS single-item collapse.
# On total failure with 0 drives and errors collected, emit an error wrapper so Java
# can distinguish "no drives on system" from "enumeration failed".
if ($drives.Count -eq 0 -and $enumerationErrors.Count -gt 0) {
    $wrapper = [ordered]@{
        drives = @()
        error  = ($enumerationErrors -join '; ')
        enumerationErrors = $enumerationErrors
    }
    $wrapper | ConvertTo-Json -Depth 4 -Compress
} else {
    # Use -InputObject to force array serialization even for 0 or 1 element
    ConvertTo-Json -InputObject @($drives) -Depth 3 -Compress
}
