$ErrorActionPreference = 'SilentlyContinue'

$smartctlPath = $null
$searchPaths = @(
    (Get-Command smartctl -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source),
    "$env:ProgramFiles\smartmontools\bin\smartctl.exe",
    "$env:ProgramFiles(x86)\smartmontools\bin\smartctl.exe",
    "$env:LOCALAPPDATA\smartmontools\bin\smartctl.exe",
    "C:\smartmontools\bin\smartctl.exe",
    "$env:SystemDrive\smartmontools\bin\smartctl.exe"
)
foreach ($p in $searchPaths) {
    if ($p -and (Test-Path $p -LiteralPath -ErrorAction SilentlyContinue)) {
        $smartctlPath = $p
        break
    }
}

function Invoke-Smartctl {
    param([string]$DriveLetter, [string[]]$ExtraArgs)
    if (-not $smartctlPath) { return $null }
    $drive = $DriveLetter.Replace(':', '')
    try {
        $allArgs = @($smartctlPath, "-a", "-j", "$($drive):") + $ExtraArgs
        $proc = Start-Process -FilePath $smartctlPath -ArgumentList ("-a", "-j", "$($drive):") -NoNewWindow -Wait -PassThru -RedirectStandardOutput "$env:TEMP\smartctl_out.json" -RedirectStandardError "$env:TEMP\smartctl_err.txt" -WindowStyle Hidden
        if ($proc.ExitCode -eq 0 -or $proc.ExitCode -eq 64 -or $proc.ExitCode -eq 68) {
            $json = Get-Content "$env:TEMP\smartctl_out.json" -Raw -ErrorAction SilentlyContinue
            if ($json -and $json.Trim().StartsWith('{')) {
                return ($json | ConvertFrom-Json -ErrorAction SilentlyContinue)
            }
        }
    } catch {}
    return $null
}

function Get-SmartFromWmi {
    param([string]$ClassName, [int]$DiskNumber, [uint64]$SizeBytes)
    try {
        $instances = Get-CimInstance -Namespace 'root\wmi' -ClassName $ClassName -ErrorAction SilentlyContinue
        if (-not $instances) { return $null }
        foreach ($inst in $instances) {
            $iname = $inst.InstanceName
            if ($iname -and ($iname -match "Disk$DiskNumber" -or $iname -match "_$DiskNumber" -or $iname -match "\.$DiskNumber" -or $iname -match "PhysicalDrive$DiskNumber")) {
                return $inst
            }
        }
        if ($instances.Count -eq 1) { return $instances[0] }
        foreach ($inst in $instances) {
            if ($inst.PSObject.Properties['Size']) {
                $s = [uint64]$inst.Size
                if ($s -gt 0 -and [math]::Abs([double]$s - [double]$SizeBytes) -lt 1048576) { return $inst }
            }
        }
    } catch {}
    return $null
}

function Parse-SmartctlNvme {
    param($Data)
    $attrs = @{}
    if ($Data.nvme_smart_health_information) {
        $nvme = $Data.nvme_smart_health_information
        if ($nvme.temperature) { $attrs.temperature = [int]$nvme.temperature }
        if ($nvme.power_on_hours -ne $null) { $attrs.powerOnHours = [long]$nvme.power_on_hours }
        if ($nvme.available_spare -ne $null) { $attrs.wearLevel = 100 - [int]$nvme.available_spare }
        if ($nvme.percentage_used -ne $null -and -not $attrs.ContainsKey('wearLevel')) {
            $attrs.wearLevel = [int]$nvme.percentage_used
        }
        if ($nvme.data_units_read -ne $null) { $attrs.totalHostReads = [uint64]$nvme.data_units_read * 512000 }
        if ($nvme.data_units_written -ne $null) { $attrs.totalHostWrites = [uint64]$nvme.data_units_written * 512000 }
        if ($nvme.media_errors -ne $null) { $attrs.mediaErrors = [long]$nvme.media_errors }
        if ($nvme.critical_warning -ne $null) { $attrs.criticalWarning = $nvme.critical_warning }
        if ($nvmeunsafe_shutdowns -ne $null) { $attrs.unsafeShutdowns = [long]$nvme.unsafe_shutdowns }
        if ($nvme.error_information_log_entries -ne $null) { $attrs.errorLogEntries = [long]$nvme.error_information_log_entries }
    }
    return $attrs
}

function Parse-SmartctlAta {
    param($Data)
    $attrs = @{}
    if ($Data.ata_smart_attributes) {
        foreach ($attr in $Data.ata_smart_attributes.table) {
            $id = $attr.id
            $raw = if ($attr.raw -and $attr.raw.value -ne $null) { [long]$attr.raw.value } else { 0 }
            switch ($id) {
                1   { $attrs.rawReadErrorRate = $raw }
                5   { $attrs.reallocatedSectors = $raw }
                9   { $attrs.powerOnHours = $raw }
                12  { $attrs.powerCycleCount = $raw }
                194 { if ($raw -gt 0 -and $raw -lt 200) { $attrs.temperature = [int]$raw } }
                197 { $attrs.pendingSectors = $raw }
                198 { $attrs.uncorrectableSectors = $raw }
                199 { $attrs.udmaCrcErrorCount = $raw }
            }
        }
    }
    if ($Data.temperature -and $Data.temperature.current) {
        $t = [int]$Data.temperature.current
        if ($t -gt 0 -and $t -lt 200 -and -not $attrs.ContainsKey('temperature')) { $attrs.temperature = $t }
    }
    if ($Data.power_on_time -and $Data.power_on_time.hours) {
        if (-not $attrs.ContainsKey('powerOnHours')) { $attrs.powerOnHours = [long]$Data.power_on_time.hours }
    }
    return $attrs
}

function Parse-SmartctlSas {
    param($Data)
    $attrs = @{}
    if ($Data.scsi_start_stop_cycle_counter) {
        $sas = $Data.scsi_start_stop_cycle_counter
        if ($sas.power_on_seconds -ne $null) {
            $attrs.powerOnHours = [long]$sas.power_on_seconds / 3600
        }
    }
    if ($Data.scsi_error_counter_log) {
        # SAS error counters available
    }
    return $attrs
}

$physicalDisks = Get-PhysicalDisk -ErrorAction SilentlyContinue
if (-not $physicalDisks) {
    @{ drives = @(); smartctlAvailable = ($null -ne $smartctlPath) } | ConvertTo-Json -Depth 3 -Compress
    return
}

$allWin32Disks = Get-CimInstance -ClassName Win32_DiskDrive -ErrorAction SilentlyContinue
$results = @()

foreach ($phys in $physicalDisks) {
    $diskNum = [int]$phys.DeviceId
    $mediaType = if ($phys.MediaType) { $phys.MediaType.ToString() } else { 'Unknown' }
    $healthStatus = if ($phys.HealthStatus) { $phys.HealthStatus.ToString() } else { 'Unknown' }
    $operationalStatus = if ($phys.OperationalStatus) { ($phys.OperationalStatus | Select-Object -First 1).ToString() } else { 'Unknown' }
    $model = if ($phys.FriendlyName) { $phys.FriendlyName } else { '' }
    $serial = if ($phys.SerialNumber) { $phys.SerialNumber.Trim() } else { '' }
    $interfaceType = if ($phys.BusType) { $phys.BusType.ToString() } else { '' }
    $sizeBytes = if ($phys.Size) { [uint64]$phys.Size } else { 0 }

    $partitions = Get-Partition -DiskNumber $diskNum -ErrorAction SilentlyContinue
    $driveLetters = @()
    if ($partitions) {
        foreach ($p in $partitions) {
            if ($p.DriveLetter -and $p.DriveLetter -ne [char]0) {
                $driveLetters += "$($p.DriveLetter):"
            }
        }
    }

    $temperature = -1
    $powerOnHours = -1
    $wearLevel = -1
    $reallocatedSectors = -1
    $pendingSectors = -1
    $uncorrectableSectors = -1
    $loadCycleCount = -1
    $powerCycleCount = -1
    $totalHostReads = -1
    $totalHostWrites = -1
    $dataSource = 'wmi'

    $smartctlData = $null
    if ($driveLetters.Count -gt 0) {
        $primaryLetter = $driveLetters[0]
        $smartctlData = Invoke-Smartctl -DriveLetter $primaryLetter
    }

    if ($smartctlData) {
        $dataSource = 'smartctl'
        $parsed = @{}
        if ($smartctlData.nvme_smart_health_information) {
            $parsed = Parse-SmartctlNvme -Data $smartctlData
        } elseif ($smartctlData.ata_smart_attributes) {
            $parsed = Parse-SmartctlAta -Data $smartctlData
        } elseif ($smartctlData.scsi_start_stop_cycle_counter) {
            $parsed = Parse-SmartctlSas -Data $smartctlData
        }
        if ($parsed.ContainsKey('temperature')) { $temperature = $parsed.temperature }
        if ($parsed.ContainsKey('powerOnHours')) { $powerOnHours = $parsed.powerOnHours }
        if ($parsed.ContainsKey('wearLevel')) { $wearLevel = $parsed.wearLevel }
        if ($parsed.ContainsKey('reallocatedSectors')) { $reallocatedSectors = $parsed.reallocatedSectors }
        if ($parsed.ContainsKey('pendingSectors')) { $pendingSectors = $parsed.pendingSectors }
        if ($parsed.ContainsKey('uncorrectableSectors')) { $uncorrectableSectors = $parsed.uncorrectableSectors }
        if ($parsed.ContainsKey('totalHostReads')) { $totalHostReads = $parsed.totalHostReads }
        if ($parsed.ContainsKey('totalHostWrites')) { $totalHostWrites = $parsed.totalHostWrites }
        if ($parsed.ContainsKey('powerCycleCount')) { $powerCycleCount = $parsed.powerCycleCount }

        if ($smartctlData.smart_status -and $smartctlData.smart_status.passed -eq $false) {
            $healthStatus = 'Critical'
        }
    }

    if ($dataSource -eq 'wmi') {
        $reliability = Get-SmartFromWmi -ClassName 'StorageReliabilityCounter' -DiskNumber $diskNum -SizeBytes $sizeBytes
        if ($reliability) {
            if ($reliability.PSObject.Properties['Temperature']) {
                $t = [int]$reliability.Temperature
                if ($t -gt 0 -and $t -lt 200) { $temperature = $t }
            }
            if ($reliability.PSObject.Properties['PowerOnHours']) {
                $v = [long]$reliability.PowerOnHours
                if ($v -gt 0) { $powerOnHours = $v }
            }
            if ($reliability.PSObject.Properties['Wear']) {
                $v = [int]$reliability.Wear
                if ($v -ge 0 -and $v -le 100) { $wearLevel = $v }
            }
            if ($reliability.PSObject.Properties['ReadErrorsTotal']) {
                $v = [long]$reliability.ReadErrorsTotal
                if ($v -gt 0) { $reallocatedSectors = $v }
            }
            if ($reliability.PSObject.Properties['WriteErrorsTotal']) {
                $v = [long]$reliability.WriteErrorsTotal
                if ($v -gt 0) { $uncorrectableSectors = $v }
            }
        }

        $isNvme = $interfaceType -eq 'NVMe'
        if ($isNvme) {
            $nvme = Get-SmartFromWmi -ClassName 'MSStorageDriver_NVMeHealthInformation' -DiskNumber $diskNum -SizeBytes $sizeBytes
            if ($nvme) {
                if ($nvme.PSObject.Properties['Temperature'] -and $temperature -lt 0) {
                    $t = [int]$nvme.Temperature
                    if ($t -gt 0 -and $t -lt 200) { $temperature = $t }
                }
                if ($nvme.PSObject.Properties['AvailableSpare']) {
                    $spare = [int]$nvme.AvailableSpare
                    if ($spare -ge 0 -and $spare -le 100) { $wearLevel = 100 - $spare }
                }
                if ($nvme.PSObject.Properties['PercentageUsed'] -and $wearLevel -lt 0) {
                    $pct = [int]$nvme.PercentageUsed
                    if ($pct -ge 0 -and $pct -le 100) { $wearLevel = $pct }
                }
                if ($nvme.PSObject.Properties['PowerOnHours'] -and $powerOnHours -lt 0) {
                    $powerOnHours = [long]$nvme.PowerOnHours
                }
                if ($nvme.PSObject.Properties['DataUnitsRead']) {
                    $v = [uint64]$nvme.DataUnitsRead * 512000
                    if ($v -gt 0) { $totalHostReads = $v }
                }
                if ($nvme.PSObject.Properties['DataUnitsWritten']) {
                    $v = [uint64]$nvme.DataUnitsWritten * 512000
                    if ($v -gt 0) { $totalHostWrites = $v }
                }
                if ($nvme.PSObject.Properties['CriticalWarning']) {
                    if ([int]$nvme.CriticalWarning -ne 0) { $healthStatus = 'Critical' }
                }
                if ($nvme.PSObject.Properties['MediaErrors']) {
                    if ([long]$nvme.MediaErrors -gt 0) { $healthStatus = 'Caution' }
                }
            }
        }

        if (-not $isNvme -or $temperature -lt 0) {
            $ataSmart = Get-SmartFromWmi -ClassName 'MSStorageDriver_SmartData' -DiskNumber $diskNum -SizeBytes $sizeBytes
            if (-not $ataSmart) { $ataSmart = Get-SmartFromWmi -ClassName 'MSStorageDriver_FailurePredictData' -DiskNumber $diskNum -SizeBytes $sizeBytes }
            if ($ataSmart -and $ataSmart.PSObject.Properties['VendorSpecific']) {
                $vendorData = $ataSmart.VendorSpecific
                if ($vendorData -and $vendorData.Count -gt 12) {
                    for ($i = 0; $i -lt $vendorData.Count - 11; $i += 12) {
                        $attrId = [int]$vendorData[$i]
                        $rawValue = [long]([int]$vendorData[$i+7]) -bor ([long]([int]$vendorData[$i+8]) -shl 8) -bor ([long]([int]$vendorData[$i+9]) -shl 16) -bor ([long]([int]$vendorData[$i+10]) -shl 24)
                        switch ($attrId) {
                            0x05 { if ($rawValue -ge 0 -and $reallocatedSectors -lt 0) { $reallocatedSectors = $rawValue } }
                            0x09 { if ($rawValue -gt 0 -and $powerOnHours -lt 0) { $powerOnHours = $rawValue } }
                            0x0C { if ($rawValue -gt 0 -and $powerCycleCount -lt 0) { $powerCycleCount = $rawValue } }
                            0xC0 { if ($rawValue -gt 0 -and $loadCycleCount -lt 0) { $loadCycleCount = $rawValue } }
                            0xC4 { if ($rawValue -ge 0 -and $pendingSectors -lt 0) { $pendingSectors = $rawValue } }
                            0xC5 { if ($rawValue -ge 0) { $reallocatedSectors = $rawValue } }
                            0xC6 { if ($rawValue -ge 0 -and $uncorrectableSectors -lt 0) { $uncorrectableSectors = $rawValue } }
                            0xBE { if ($rawValue -gt 0 -and $rawValue -lt 200 -and $temperature -lt 0) { $temperature = $rawValue } }
                        }
                    }
                }
            }
        }

        $predict = Get-SmartFromWmi -ClassName 'MSStorageDriver_FailurePredictStatus' -DiskNumber $diskNum -SizeBytes $sizeBytes
        if ($predict -and $predict.PredictFailure) { $healthStatus = 'Critical' }

        $perf = Get-SmartFromWmi -ClassName 'MSStorageDriver_PerfData' -DiskNumber $diskNum -SizeBytes $sizeBytes
        if ($perf) {
            if ($perf.PSObject.Properties['DataRead'] -and $totalHostReads -lt 0) {
                $v = [uint64]$perf.DataRead; if ($v -gt 0) { $totalHostReads = $v }
            }
            if ($perf.PSObject.Properties['DataWritten'] -and $totalHostWrites -lt 0) {
                $v = [uint64]$perf.DataWritten; if ($v -gt 0) { $totalHostWrites = $v }
            }
        }
    }

    $results += [ordered]@{
        driveLetter               = if ($driveLetters.Count -gt 0) { $driveLetters[0] } else { '' }
        driveLetters              = $driveLetters
        model                     = $model
        serialNumber              = $serial
        interfaceType             = $interfaceType
        mediaType                 = $mediaType
        sizeBytes                 = $sizeBytes
        healthStatus              = $healthStatus
        operationalStatus         = $operationalStatus
        temperature               = $temperature
        powerOnHours              = $powerOnHours
        wearLevel                 = $wearLevel
        reallocatedSectors        = $reallocatedSectors
        currentPendingSectorCount = $pendingSectors
        uncorrectableSectorCount  = $uncorrectableSectors
        loadCycleCount            = $loadCycleCount
        powerCycleCount           = $powerCycleCount
        totalHostReads            = $totalHostReads
        totalHostWrites           = $totalHostWrites
        dataSource                = $dataSource
    }
}

@{
    drives = $results
    smartctlAvailable = ($null -ne $smartctlPath)
} | ConvertTo-Json -Depth 3 -Compress
