# Gathers hardware and OS information and returns a single JSON object.
# Version: 2.0
$result = [ordered]@{}
$warnings = @()

# ── CPU ──────────────────────────────────────────────────────────────────────
try {
    $ErrorActionPreference = 'Stop'
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $cpuSection = [ordered]@{
        name          = if ($cpu.Name) { $cpu.Name.Trim() } else { '' }
        manufacturer  = if ($cpu.Manufacturer) { $cpu.Manufacturer } else { '' }
        cores         = if ($cpu.NumberOfCores) { $cpu.NumberOfCores } else { 0 }
        logicalCpus   = if ($cpu.NumberOfLogicalProcessors) { $cpu.NumberOfLogicalProcessors } else { 0 }
        baseClockMhz  = if ($cpu.MaxClockSpeed) { $cpu.MaxClockSpeed } else { 0 }
        currentClockMhz = if ($cpu.CurrentClockSpeed) { $cpu.CurrentClockSpeed } else { 0 }
        l2CacheKb     = if ($cpu.L2CacheSize) { $cpu.L2CacheSize } else { 0 }
        l3CacheKb     = if ($cpu.L3CacheSize) { $cpu.L3CacheSize } else { 0 }
        socket        = if ($cpu.SocketDesignation) { $cpu.SocketDesignation } else { '' }
        architecture  = switch ($cpu.Architecture) {
            0 { 'x86' } 1 { 'MIPS' } 2 { 'Alpha' } 3 { 'PowerPC' }
            5 { 'ARM' } 6 { 'ia64' } 9 { 'x64' } default { 'Unknown' }
        }
        stepping      = if ($cpu.Stepping) { $cpu.Stepping } else { '' }
        revision      = if ($cpu.Revision) { $cpu.Revision } else { '' }
        voltage       = if ($cpu.CurrentVoltage) { [math]::Round($cpu.CurrentVoltage / 10, 1).ToString() + ' V' } else { '' }
    }
    $result['cpu'] = $cpuSection
} catch {
    $warnings += "CPU: $($_.Exception.Message)"
    $result['cpu'] = [ordered]@{
        name=''; manufacturer=''; cores=0; logicalCpus=0; baseClockMhz=0
        currentClockMhz=0; l2CacheKb=0; l3CacheKb=0; socket=''; architecture=''
        stepping=''; revision=''; voltage=''
    }
}

# ── GPU ──────────────────────────────────────────────────────────────────────
try {
    $ErrorActionPreference = 'Stop'
    $gpus = @()
    $gpuSearcher = New-Object System.Management.ManagementObjectSearcher('root\cimv2', 'SELECT * FROM Win32_VideoController')
    foreach ($gpuObj in $gpuSearcher.Get()) {
        $vc = $gpuObj

        $vramBytes = [uint64]0
        try {
            $raw = $vc['AdapterRAM']
            if ($null -ne $raw) { $vramBytes = [uint64]([uint32]$raw) }
        } catch {}

        $gpuName = ''
        try { $gpuName = [string]$vc['Name'] } catch {}
        if ($gpuName -match 'NVIDIA') {
            try {
                $nvidiaOut = & nvidia-smi --query-gpu=memory.total --format=csv,noheader,nounits 2>$null
                if ($nvidiaOut) {
                    foreach ($line in $nvidiaOut) {
                        $mbVal = 0
                        try { $mbVal = [int]($line.Trim()) } catch {}
                        if ($mbVal -gt 0) {
                            $vramBytes = [uint64]$mbVal * 1024 * 1024
                            break
                        }
                    }
                }
            } catch {}
        }

        $memoryType = ''
        try {
            $vmtRaw = $vc['VideoMemoryType']
            if ($null -ne $vmtRaw) {
                $vmt = [int]$vmtRaw
                $memoryType = switch ($vmt) {
                    3 { 'VRAM' } 4 { 'RAM' } 5 { 'EDO' }
                    16 { 'DRAM' } 17 { 'SGRAM' } 19 { 'SDRAM' }
                    22 { 'DDR SGRAM' } 23 { 'DDR' } 24 { 'DDR2' }
                    29 { 'GDDR3' } 30 { 'GDDR4' } 31 { 'GDDR5' }
                    32 { 'HBM' } 33 { 'HBM2' } 34 { 'GDDR5X' }
                    35 { 'GDDR6' } 36 { 'GDDR6X' } 37 { 'GDDR7' }
                    default { '' }
                }
            }
        } catch {}

        if ($memoryType -eq '') {
            $nameLower = ''
            try { $nameLower = $gpuName.ToLower() } catch {}
            if ($nameLower -match 'hbm') { $memoryType = 'HBM' }
            elseif ($nameLower -match 'rtx\s*50') { $memoryType = 'GDDR7' }
            elseif ($nameLower -match 'rtx\s*40[89]0') { $memoryType = 'GDDR6X' }
            elseif ($nameLower -match 'rtx\s*40') { $memoryType = 'GDDR6' }
            elseif ($nameLower -match 'rtx\s*30[89]0') { $memoryType = 'GDDR6X' }
            elseif ($nameLower -match 'rtx\s*30') { $memoryType = 'GDDR6' }
            elseif ($nameLower -match 'rtx\s*20') { $memoryType = 'GDDR6' }
            elseif ($nameLower -match 'gtx\s*16') { $memoryType = 'GDDR5' }
            elseif ($nameLower -match 'gtx\s*10[89]0.*ti') { $memoryType = 'GDDR5X' }
            elseif ($nameLower -match 'gtx\s*10') { $memoryType = 'GDDR5' }
            elseif ($nameLower -match 'gtx\s*9') { $memoryType = 'GDDR5' }
            elseif ($nameLower -match 'gtx\s*7') { $memoryType = 'GDDR5' }
            elseif ($nameLower -match 'gddr6x') { $memoryType = 'GDDR6X' }
            elseif ($nameLower -match 'gddr6') { $memoryType = 'GDDR6' }
            elseif ($nameLower -match 'gddr5x') { $memoryType = 'GDDR5X' }
            elseif ($nameLower -match 'gddr5') { $memoryType = 'GDDR5' }
            elseif ($nameLower -match 'gddr3') { $memoryType = 'GDDR3' }
            elseif ($nameLower -match 'intel') { $memoryType = 'Shared' }
        }

        $gpuEntry = [ordered]@{
            name            = ''
            manufacturer    = ''
            videoProcessor  = ''
            vramBytes       = $vramBytes
            memoryType      = $memoryType
            driverVersion   = ''
            driverDate      = ''
            resolution      = ''
            colorDepth      = ''
            status          = ''
        }
        try { if ($vc['Name']) { $gpuEntry['name'] = [string]$vc['Name'] } } catch {}
        try { if ($vc['AdapterCompatibility']) { $gpuEntry['manufacturer'] = [string]$vc['AdapterCompatibility'] } } catch {}
        try { if ($vc['VideoProcessor']) { $gpuEntry['videoProcessor'] = [string]$vc['VideoProcessor'] } } catch {}
        try { if ($vc['DriverVersion']) { $gpuEntry['driverVersion'] = [string]$vc['DriverVersion'] } } catch {}
        try {
            if ($vc['DriverDate']) {
                $gpuEntry['driverDate'] = ([Management.ManagementDateTime]::ToDateTime([string]$vc['DriverDate'])).ToString('yyyy-MM-dd')
            }
        } catch {}
        try {
            $h = $vc['CurrentHorizontalResolution']
            $v = $vc['CurrentVerticalResolution']
            if ($h -and $v) { $gpuEntry['resolution'] = "${h}x${v}" }
        } catch {}
        try { if ($vc['CurrentBitsPerPixel']) { $gpuEntry['colorDepth'] = "$($vc['CurrentBitsPerPixel'])-bit" } } catch {}
        try { if ($vc['Status']) { $gpuEntry['status'] = [string]$vc['Status'] } } catch {}
        $gpus += $gpuEntry
    }
    $gpuSearcher.Dispose()
    $result['gpu'] = $gpus
} catch {
    $warnings += "GPU: $($_.Exception.Message)"
    $result['gpu'] = @()
}

# ── RAM ──────────────────────────────────────────────────────────────────────
try {
    $ErrorActionPreference = 'Stop'
    $cs = Get-CimInstance Win32_ComputerSystem
    $totalRamBytes = if ($cs.TotalPhysicalMemory) { [uint64]$cs.TotalPhysicalMemory } else { 0 }

    function Get-DdrType {
        param([int]$smbiosType, [string]$memoryTypeCim)
        $smbiosTypeMap = @{
            0x0F = 'DDR'; 0x12 = 'DDR'; 0x13 = 'DDR2'; 0x14 = 'DDR2 FB-DIMM'
            0x18 = 'DDR3'; 0x19 = 'FBD2'; 0x1A = 'DDR4'
            0x1B = 'LPDDR'; 0x1C = 'LPDDR2'; 0x1D = 'LPDDR3'; 0x1E = 'LPDDR4'
            0x20 = 'HBM'; 0x21 = 'HBM2'; 0x22 = 'DDR5'; 0x23 = 'LPDDR5'
        }
        if ($smbiosTypeMap.ContainsKey($smbiosType)) {
            return $smbiosTypeMap[$smbiosType]
        }
        if ($memoryTypeCim -and $memoryTypeCim -ne 'Unknown') {
            return $memoryTypeCim
        }
        return 'Unknown'
    }

    $sticks = @()
    $memSearcher = New-Object System.Management.ManagementObjectSearcher('root\cimv2', 'SELECT * FROM Win32_PhysicalMemory')
    foreach ($memObj in $memSearcher.Get()) {
        $mem = $memObj

        $cap = [uint64]0
        try { $raw = $mem['Capacity']; if ($null -ne $raw) { $cap = [uint64]$raw } } catch {}

        $smbiosMemType = 0
        try { $raw = $mem['SMBIOSMemoryType']; if ($null -ne $raw) { $smbiosMemType = [int]$raw } } catch {}

        $cimMemType = 'Unknown'
        try {
            $raw = $mem['MemoryType']
            if ($null -ne $raw) {
                $cimMemType = switch ([int]$raw) {
                    20 { 'DDR' } 21 { 'DDR2' } 22 { 'DDR2 FB-DIMM' }
                    24 { 'DDR3' } 25 { 'FBD2' } 26 { 'DDR4' }
                    27 { 'DDR5' } default { 'Unknown' }
                }
            }
        } catch {}

        $resolvedType = Get-DdrType -smbiosType $smbiosMemType -memoryTypeCim $cimMemType

        $speed = 0
        try { $raw = $mem['Speed']; if ($null -ne $raw) { $speed = [int]$raw } } catch {}
        $mfr = ''
        try { $raw = $mem['Manufacturer']; if ($null -ne $raw) { $mfr = ([string]$raw).Trim() } } catch {}
        $partNum = ''
        try { $raw = $mem['PartNumber']; if ($null -ne $raw) { $partNum = ([string]$raw).Trim() } } catch {}
        $ff = 'Unknown'
        try {
            $raw = $mem['FormFactor']
            if ($null -ne $raw) {
                $ff = switch ([int]$raw) { 8 { 'DIMM' } 12 { 'SODIMM' } default { 'Unknown' } }
            }
        } catch {}

        $stick = [ordered]@{
            capacityBytes = $cap
            speedMhz     = $speed
            manufacturer = $mfr
            partNumber   = $partNum
            formFactor   = $ff
            memoryType   = $resolvedType
        }
        $sticks += $stick
    }
    $memSearcher.Dispose()
    $channel = if ($sticks.Count -ge 2) { 'Dual' } elseif ($sticks.Count -eq 1) { 'Single' } else { '' }
    $ramSection = [ordered]@{
        totalBytes = $totalRamBytes
        channel    = $channel
        sticks     = $sticks
    }
    $result['ram'] = $ramSection
} catch {
    $warnings += "RAM: $($_.Exception.Message)"
    $result['ram'] = [ordered]@{ totalBytes=0; channel=''; sticks=@() }
}

# ── OS ───────────────────────────────────────────────────────────────────────
try {
    $ErrorActionPreference = 'Stop'
    $os = Get-CimInstance Win32_OperatingSystem | Select-Object -First 1
    $osSection = [ordered]@{
        name            = if ($os.Caption) { $os.Caption } else { '' }
        version         = if ($os.Version) { $os.Version } else { '' }
        buildNumber     = if ($os.BuildNumber) { $os.BuildNumber } else { '' }
        architecture    = if ($os.OSArchitecture) { $os.OSArchitecture } else { '' }
        installDate     = if ($os.InstallDate) {
            try { $os.InstallDate.ToString('yyyy-MM-dd HH:mm') } catch { '' }
        } else { '' }
        lastBoot        = if ($os.LastBootUpTime) {
            try { $os.LastBootUpTime.ToString('yyyy-MM-dd HH:mm') } catch { '' }
        } else { '' }
        computerName    = if ($cs.Name) { $cs.Name } else { '' }
        windowsDir      = if ($os.WindowsDirectory) { $os.WindowsDirectory } else { '' }
        serialNumber    = ''
        productKey      = ''
    }
    $bios = Get-CimInstance Win32_BIOS | Select-Object -First 1
    if ($bios -and $bios.SerialNumber) {
        $osSection['serialNumber'] = $bios.SerialNumber.Trim()
    }
    $result['os'] = $osSection
} catch {
    $warnings += "OS: $($_.Exception.Message)"
    $result['os'] = [ordered]@{
        name=''; version=''; buildNumber=''; architecture=''
        installDate=''; lastBoot=''; computerName=''
        windowsDir=''; serialNumber=''; productKey=''
    }
}

# ── STORAGE ──────────────────────────────────────────────────────────────────
try {
    $ErrorActionPreference = 'Stop'
    $disks = @()
    $diskIndexMap = @{}
    $diskIdx = 0
    Get-CimInstance Win32_DiskDrive | ForEach-Object {
        $sizeBytes = 0
        if ($_.Size) { $sizeBytes = [uint64]$_.Size }
        $serial = ''
        try { if ($_.SerialNumber) { $serial = $_.SerialNumber.Trim() } } catch {}
        $disk = [ordered]@{
            model       = if ($_.Model) { $_.Model.Trim() } else { '' }
            manufacturer = if ($_.Manufacturer) { $_.Manufacturer.Trim() } else { '' }
            sizeBytes   = $sizeBytes
            mediType    = if ($_.MediaType) { $_.MediaType } else { '' }
            interfaceType = if ($_.InterfaceType) { $_.InterfaceType } else { '' }
            serialNumber = $serial
            partitions  = if ($_.Partitions) { $_.Partitions } else { 0 }
        }
        if ($serial) { $diskIndexMap[$serial] = $diskIdx }
        $disks += $disk
        $diskIdx++
    }

    # Build mapping: logical disk deviceID -> physical disk index
    $logicalToPhysical = @{}
    Get-CimInstance Win32_DiskDriveToDiskPartition -ErrorAction SilentlyContinue | ForEach-Object {
        $physDisk = $_.Antecedent
        $partition = $_.Dependent
        $physSerial = ''
        try {
            $physObj = Get-CimInstance -Query "SELECT SerialNumber FROM Win32_DiskDrive WHERE DeviceID='$($physDisk.DeviceID)'" -ErrorAction SilentlyContinue
            if ($physObj -and $physObj.SerialNumber) { $physSerial = $physObj.SerialNumber.Trim() }
        } catch {}
        if (-not $physSerial) { return }
        $physIdx = -1
        if ($diskIndexMap.ContainsKey($physSerial)) { $physIdx = $diskIndexMap[$physSerial] }
        if ($physIdx -lt 0) { return }

        # Find logical disks on this partition
        Get-CimInstance Win32_LogicalDiskToPartition -ErrorAction SilentlyContinue | Where-Object {
            $_.Antecedent.DeviceID -eq $partition.DeviceID
        } | ForEach-Object {
            $logicalDiskId = $_.Dependent.DeviceID
            $logicalToPhysical[$logicalDiskId] = $physIdx
        }
    }

    $partitions = @()
    Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3" | ForEach-Object {
        $partSize = 0
        $partFree = 0
        if ($_.Size) { $partSize = [uint64]$_.Size }
        if ($_.FreeSpace) { $partFree = [uint64]$_.FreeSpace }
        $devId = if ($_.DeviceID) { $_.DeviceID } else { '' }
        $diskIdxVal = -1
        if ($logicalToPhysical.ContainsKey($devId)) { $diskIdxVal = $logicalToPhysical[$devId] }
        $part = [ordered]@{
            deviceID   = $devId
            volumeName = if ($_.VolumeName) { $_.VolumeName } else { '' }
            fsType     = if ($_.FileSystem) { $_.FileSystem } else { '' }
            sizeBytes  = $partSize
            freeBytes  = $partFree
            diskIndex  = $diskIdxVal
        }
        $partitions += $part
    }

    $nvmes = @()
    Get-CimInstance Win32_PhysicalMedia -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.MediaType -match 'NVMe' -or $_.BusType -eq 'NVMe') {
            $nvmes += [ordered]@{
                serialNumber = if ($_.SerialNumber) { $_.SerialNumber.Trim() } else { '' }
                mediaType    = if ($_.MediaType) { $_.MediaType } else { '' }
                busType      = if ($_.BusType) { $_.BusType } else { '' }
            }
        }
    }

    $storageSection = [ordered]@{
        disks       = $disks
        partitions  = $partitions
        nvmes       = $nvmes
    }
    $result['storage'] = $storageSection
} catch {
    $warnings += "Storage: $($_.Exception.Message)"
    $result['storage'] = [ordered]@{ disks=@(); partitions=@(); nvmes=@() }
}

# ── MOTHERBOARD ──────────────────────────────────────────────────────────────
try {
    $ErrorActionPreference = 'Stop'
    $mb = Get-CimInstance Win32_BaseBoard | Select-Object -First 1
    $mbSection = [ordered]@{
        manufacturer = if ($mb.Manufacturer) { $mb.Manufacturer.Trim() } else { '' }
        model        = if ($mb.Product) { $mb.Product.Trim() } else { '' }
        serialNumber = if ($mb.SerialNumber) { $mb.SerialNumber.Trim() } else { '' }
        version      = if ($mb.Version) { $mb.Version.Trim() } else { '' }
    }

    $chipset = ''
    $southbridge = ''
    Get-CimInstance Win32_PnPEntity | Where-Object { $_.DeviceID -match 'PCI\\CC_0601' } | ForEach-Object {
        if (-not $chipset -and $_.Name) { $chipset = $_.Name.Trim() }
    }
    if (-not $chipset) {
        Get-CimInstance Win32_PnPEntity | Where-Object {
            ($_.Name -match 'chipset|PCH|southbridge|fusion controller|A-series|B-series|X-series|Z-series') -and
            ($_.DeviceClass -eq 'System' -or $_.DeviceID -match 'PCI\\CC_0600')
        } | Select-Object -First 1 | ForEach-Object {
            $chipset = $_.Name.Trim()
        }
    }
    if (-not $chipset) {
        try {
            $sysInfo = Get-CimInstance Win32_ComputerSystemProduct
            if ($sysInfo.Name) { $chipset = $sysInfo.Name }
        } catch {}
    }

    $mbSection['chipset'] = $chipset
    $mbSection['southbridge'] = $southbridge
    $result['motherboard'] = $mbSection
} catch {
    $warnings += "Motherboard: $($_.Exception.Message)"
    $result['motherboard'] = [ordered]@{ manufacturer=''; model=''; serialNumber=''; version=''; chipset=''; southbridge='' }
}

# ── BIOS ─────────────────────────────────────────────────────────────────────
try {
    $ErrorActionPreference = 'Stop'
    $biosSection = [ordered]@{
        manufacturer = if ($bios.Manufacturer) { $bios.Manufacturer.Trim() } else { '' }
        version      = if ($bios.SMBIOSBIOSVersion) { $bios.SMBIOSBIOSVersion.Trim() } else { '' }
        releaseDate  = if ($bios.ReleaseDate) {
            try { $bios.ReleaseDate.ToString('yyyy-MM-dd') } catch { '' }
        } else { '' }
        smbiosMajor  = if ($bios.SMBIOSMajorVersion) { $bios.SMBIOSMajorVersion } else { 0 }
        smbiosMinor  = if ($bios.SMBIOSMinorVersion) { $bios.SMBIOSMinorVersion } else { 0 }
    }
    $result['bios'] = $biosSection
} catch {
    $warnings += "BIOS: $($_.Exception.Message)"
    $result['bios'] = [ordered]@{ manufacturer=''; version=''; releaseDate=''; smbiosMajor=0; smbiosMinor=0 }
}

# ── OTHER DEVICES ────────────────────────────────────────────────────────────
try {
    $ErrorActionPreference = 'Stop'
    $others = @()
    $seenIds = @{}
    Get-CimInstance Win32_PnPEntity | Where-Object { $_.DeviceID -and $_.Name -ne 'Unknown device' } | ForEach-Object {
        $devid = $_.DeviceID
        if ($devid -match 'CPU\\|Processor\\') { return }
        if ($devid -match 'VID_') { return }
        if ($devid -match 'PCI\\CC_03') { return }
        if ($devid -match 'SCSI\\Disk|SCSI\\CDRom|IDE\\Disk|IDE\\CDRom|USBSTOR\\') { return }
        if ($devid -match 'PCI\\CC_010[0-9a-fA-F]') { return }
        if ($devid -match 'ACPI\\MSACPI') { return }
        if ($devid -match 'SW\\{') { return }
        $pnpClassRaw = ''
        try { $p = $_.PNPClass; if ($null -ne $p) { $pnpClassRaw = [string]$p } } catch {}
        if ($pnpClassRaw -eq 'Display' -or $pnpClassRaw -eq 'Processor' -or $pnpClassRaw -eq 'DiskDrive') { return }
        if ($seenIds.ContainsKey($devid)) { return }
        $seenIds[$devid] = $true

        $devClass = ''
        try {
            $raw = $_.PNPClass
            if ($null -ne $raw -and [string]$raw -ne '') { $devClass = [string]$raw }
        } catch {}
        if (-not $devClass) {
            $raw2 = $_.ClassGuid
            if ($null -ne $raw2) {
                $guid = [string]$raw2
                $devClass = switch -Regex ($guid) {
                    '{4d36e968' { 'Display' }
                    '{4d36e96b' { 'SCSIAdapter' }
                    '{4d36e96c' { 'USB' }
                    '{4d36e96d' { 'Media' }
                    '{4d36e96e' { 'Image' }
                    '{4d36e96f' { 'Keyboard' }
                    '{4d36e970' { 'HIDClass' }
                    '{4d36e971' { 'Net' }
                    '{4d36e972' { 'NetService' }
                    '{4d36e973' { 'NetTrans' }
                    '{4d36e974' { 'System' }
                    '{4d36e977' { 'Battery' }
                    '{4d36e978' { 'HDC' }
                    '{4d36e979' { 'Infrared' }
                    '{4d36e97a' { 'Modem' }
                    '{4d36e97b' { 'Monitor' }
                    '{4d36e97c' { 'Mouse' }
                    '{4d36e97d' { 'MTD' }
                    '{4d36e97e' { 'MultiFunction' }
                    '{4d36e97f' { 'Adapter' }
                    '{4d36e980' { 'Printer' }
                    '{4d36e981' { 'Ports' }
                    '{4d36e982' { 'SBEmul' }
                    '{4d36e983' { 'Sound' }
                    '{4d36e984' { 'Storage' }
                    '{4d36e985' { 'TapeDrive' }
                    '{4d36e986' { 'Volume' }
                    '{4d36e987' { 'Processor' }
                    '{4d36e988' { 'DiskDrive' }
                    '{4d36e989' { 'FloppyDisk' }
                    '{4d36e98a' { 'Keyboard' }
                    '{4d36e98b' { 'Mouse' }
                    '{4d36e98c' { 'SoftwareDevice' }
                    '{4d36e98d' { 'Sound' }
                    '{4d36e98e' { 'USB' }
                    '{4d36e98f' { 'Display' }
                    '{4d36e990' { 'Battery' }
                    '{4d36e991' { 'HIDClass' }
                    '{4d36e992' { 'IEEE1284.4' }
                    '{4d36e993' { 'IEEE1394' }
                    '{4d36e994' { 'Image' }
                    '{4d36e995' { 'Infrared' }
                    '{4d36e996' { 'Modem' }
                    '{4d36e997' { 'Monitor' }
                    '{4d36e998' { 'Mouse' }
                    '{4d36e999' { 'Multifunction' }
                    '{4d36e99a' { 'Adapter' }
                    '{4d36e99b' { 'Ports' }
                    '{4d36e99c' { 'Printer' }
                    '{4d36e99d' { 'SBEmul' }
                    '{4d36e99e' { 'SCSIAdapter' }
                    '{4d36e99f' { 'Storage' }
                    '{4d36e9a0' { 'TapeDrive' }
                    '{4d36e9a1' { 'Volume' }
                    '{4d36e9a2' { 'System' }
                    '{50dd5230' { 'BAElement' }
                    '{71a27cdd' { 'Volume' }
                    '{d48179be' { 'Adapter' }
                    default { 'Other' }
                }
            }
        }
        if (-not $devClass) { $devClass = 'Other' }

        $mfr = ''
        try { $raw = $_.Manufacturer; if ($null -ne $raw) { $mfr = ([string]$raw).Trim() } } catch {}
        $status = ''
        try { $raw = $_.Status; if ($null -ne $raw) { $status = [string]$raw } } catch {}

        $others += [ordered]@{
            name           = [string]$_.Name
            deviceClass    = $devClass
            manufacturer   = $mfr
            deviceId       = $devid
            status         = $status
        }
    }
    $result['others'] = $others
} catch {
    $warnings += "Others: $($_.Exception.Message)"
    $result['others'] = @()
}

# ── OUTPUT ───────────────────────────────────────────────────────────────────
$result['warnings'] = $warnings
$result['version'] = '2.0'
$result | ConvertTo-Json -Depth 6 -Compress
