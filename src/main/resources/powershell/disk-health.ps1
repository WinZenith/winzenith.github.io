param([switch]$SelfTest, [string]$FixturePath = '')

function Read-AtaSmartAttributeRows {
    param($VendorData)
    $rows = New-Object System.Collections.Generic.List[object]
    if (-not $VendorData) { return @() }
    $count = 0
    try { $count = [int]$VendorData.Count } catch { return @() }
    # ATA SMART: bytes 0-1 are the structure revision. 12-byte attributes start at offset 2.
    # Raw value is 6 little-endian bytes at attribute+5 (not +7).
    $n = 0
    for ($i = 2; $n -lt 30 -and ($i + 12) -le $count; $i += 12) {
        $n++
        $attrId = [int]$VendorData[$i]
        if ($attrId -le 0) { continue }
        $raw = [long]$VendorData[$i + 5] + ([long]$VendorData[$i + 6] * 256) + ([long]$VendorData[$i + 7] * 65536) + ([long]$VendorData[$i + 8] * 16777216) + ([long]$VendorData[$i + 9] * 4294967296) + ([long]$VendorData[$i + 10] * 1099511627776)
        $rows.Add([pscustomobject]@{
            id    = $attrId
            value = [int]$VendorData[$i + 3]
            worst = [int]$VendorData[$i + 4]
            raw   = $raw
        })
    }
    return $rows.ToArray()
}

function Update-NvmeHealthStatus {
    param([string]$HealthStatus, $CriticalWarning, $MediaErrors, [bool]$SmartFailed)
    $status = if ($HealthStatus) { $HealthStatus } else { 'Unknown' }
    if ($status -eq 'Critical' -or $SmartFailed) { return 'Critical' }
    $cw = 0
    if ($null -ne $CriticalWarning -and "$CriticalWarning" -ne '') {
        try { $cw = [int]$CriticalWarning } catch { $cw = 0 }
    }
    if ($cw -ne 0) { return 'Critical' }
    $me = 0
    if ($null -ne $MediaErrors -and "$MediaErrors" -ne '') {
        try { $me = [long]$MediaErrors } catch { $me = 0 }
    }
    if ($me -gt 0) { return 'Caution' }
    return $status
}

function ConvertTo-DiskMediaType {
    param($MediaType, $BusType, $SpindleSpeed)
    $mt = if ($null -ne $MediaType -and "$MediaType" -ne '') { "$MediaType" } else { '' }
    if ($mt -eq 'HDD' -or $mt -eq 'SSD') { return $mt }
    $bus = if ($null -ne $BusType) { "$BusType" } else { '' }
    $busNum = ''
    if ($null -ne $BusType) {
        try { $busNum = [string][int]$BusType } catch { $busNum = '' }
    }
    if ($bus -eq 'NVMe' -or $busNum -eq '17' -or $bus -eq 'SD' -or $busNum -eq '12' -or $bus -eq 'MMC' -or $busNum -eq '13' -or $bus -eq 'UFS' -or $busNum -eq '19' -or $bus -eq 'SCM' -or $busNum -eq '18') {
        return 'SSD'
    }
    if ($null -ne $SpindleSpeed -and "$SpindleSpeed" -ne '') {
        try {
            $rpm = [int]$SpindleSpeed
            if ($rpm -eq 0) { return 'SSD' }
            if ($rpm -ge 1000) { return 'HDD' }
        } catch {}
    }
    return 'Unknown'
}

if ($SelfTest) {
    if (-not $FixturePath -or -not (Test-Path -LiteralPath $FixturePath)) {
        Write-Error "SelfTest requires -FixturePath"
        exit 1
    }
    $bytes = [System.IO.File]::ReadAllBytes($FixturePath)
    $parsedRows = @(Read-AtaSmartAttributeRows $bytes)
    $reallocated = -1
    $pending = -1
    $uncorrectable = -1
    foreach ($row in $parsedRows) {
        switch ([int]$row.id) {
            5   { if ($reallocated -lt 0) { $reallocated = [long]$row.raw } }
            197 { if ($pending -lt 0) { $pending = [long]$row.raw } }
            198 { if ($uncorrectable -lt 0) { $uncorrectable = [long]$row.raw } }
        }
    }
    $worst = 0
    foreach ($v in @($reallocated, $pending, $uncorrectable)) {
        if ($v -ge 0 -and $v -gt $worst) { $worst = $v }
    }
    $health = 'Healthy'
    if ($worst -gt 10) { $health = 'Critical' }
    elseif ($worst -gt 0) { $health = 'Caution' }
    $out = [ordered]@{
        reallocated    = $reallocated
        pending        = $pending
        uncorrectable  = $uncorrectable
        health         = $health
        nvmeMedia      = (Update-NvmeHealthStatus 'Healthy' 0 4 $false)
        nvmeWarn       = (Update-NvmeHealthStatus 'Healthy' 1 0 $false)
        nvmeOk         = (Update-NvmeHealthStatus 'Healthy' 0 0 $false)
        nvmeSmartFail  = (Update-NvmeHealthStatus 'Healthy' 0 0 $true)
        nvmeKeep       = (Update-NvmeHealthStatus 'Critical' 0 4 $false)
        mediaSsd       = (ConvertTo-DiskMediaType 'Unspecified' 'SATA' 0)
        mediaHdd       = (ConvertTo-DiskMediaType 'Unspecified' 'SATA' 7200)
    }
    $out | ConvertTo-Json -Compress
    exit 0
}

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
    if ($p -and (Test-Path -LiteralPath $p -ErrorAction SilentlyContinue)) {
        $smartctlPath = $p
        break
    }
}

function Invoke-Smartctl {
    param([string]$DriveLetter, [string[]]$ExtraArgs)
    if (-not $smartctlPath) { return $null }
    $drive = $DriveLetter.Replace(':', '')
    $tempOut = Join-Path $env:TEMP ("smartctl_out_" + $drive + "_" + [Guid]::NewGuid().ToString("N") + ".json")
    $tempErr = Join-Path $env:TEMP ("smartctl_err_" + $drive + "_" + [Guid]::NewGuid().ToString("N") + ".txt")
    try {
        $argList = @("-a", "-j", "$($drive):") + $ExtraArgs
        $proc = Start-Process -FilePath $smartctlPath -ArgumentList $argList -NoNewWindow -Wait -PassThru -RedirectStandardOutput $tempOut -RedirectStandardError $tempErr -WindowStyle Hidden
        # smartctl uses bitmask exit codes: 0=OK, 4=SMART failure, 8=etc. We must parse JSON even when SMART indicates failure.
        # Only treat 1 (syntax error) and 2 (open error) as hard failures without data. For all other codes, if JSON exists, return it.
        $json = Get-Content $tempOut -Raw -ErrorAction SilentlyContinue
        if ($json -and $json.Trim().StartsWith('{')) {
            $parsed = $json | ConvertFrom-Json -ErrorAction SilentlyContinue
            if ($parsed) { return $parsed }
        }
        # Fallback: if JSON present but ConvertFrom-Json failed due to encoding, try raw return
        if ($json -and $json.Trim().StartsWith('{')) {
            try { return ($json | ConvertFrom-Json -ErrorAction Stop) } catch {}
        }
    } catch {}
    finally {
        try { Remove-Item $tempOut -Force -ErrorAction SilentlyContinue } catch {}
        try { Remove-Item $tempErr -Force -ErrorAction SilentlyContinue } catch {}
    }
    return $null
}

function Get-SmartFromWmi {
    param([string]$ClassName, [int]$DiskNumber, [uint64]$SizeBytes)
    try {
        $instances = Get-CimInstance -Namespace 'root\wmi' -ClassName $ClassName -ErrorAction SilentlyContinue
        if (-not $instances) { return $null }
        $n = [regex]::Escape([string]$DiskNumber)
        foreach ($inst in $instances) {
            $iname = $inst.InstanceName
            if (-not $iname) { continue }
            # Digit-bounded: Disk1 must not match Disk10 / PhysicalDrive11.
            if ($iname -match "(?i)(?:^|[^0-9])Disk$n(?:[^0-9]|$)" -or
                $iname -match "(?i)PhysicalDrive$n(?:[^0-9]|$)") {
                return $inst
            }
        }
        # Fail closed: never attach another disk's instance (size-similar or sole leftover).
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
        if ($nvme.unsafe_shutdowns -ne $null) { $attrs.unsafeShutdowns = [long]$nvme.unsafe_shutdowns }
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
    @{ drives = @(); smartctlAvailable = ($null -ne $smartctlPath) } | ConvertTo-Json -Depth 5 -Compress
    return
}

$allWin32Disks = Get-CimInstance -ClassName Win32_DiskDrive -ErrorAction SilentlyContinue
$results = @()

foreach ($phys in $physicalDisks) {
    # Fail closed: a non-numeric DeviceId must not keep the previous disk's number
    # (SilentlyContinue would otherwise reuse DiskN SMART/partitions).
    $diskNum = -1
    if ($null -ne $phys.DeviceId -and ("$($phys.DeviceId)" -match '^\d+$')) {
        $diskNum = [int]$phys.DeviceId
    }
    $healthStatus = if ($phys.HealthStatus) { $phys.HealthStatus.ToString() } else { 'Unknown' }
    $operationalStatus = if ($phys.OperationalStatus) { ($phys.OperationalStatus | Select-Object -First 1).ToString() } else { 'Unknown' }
    $model = if ($phys.FriendlyName) { $phys.FriendlyName } else { '' }
    $serial = if ($phys.SerialNumber) { $phys.SerialNumber.Trim() } else { '' }
    $interfaceType = if ($phys.BusType) { $phys.BusType.ToString() } else { '' }
    $spindle = $null
    if ($phys.PSObject.Properties['SpindleSpeed']) { $spindle = $phys.SpindleSpeed }
    $mediaType = ConvertTo-DiskMediaType -MediaType $phys.MediaType -BusType $phys.BusType -SpindleSpeed $spindle
    $sizeBytes = if ($phys.Size) { [uint64]$phys.Size } else { 0 }

    $partitions = $null
    if ($diskNum -ge 0) {
        $partitions = Get-Partition -DiskNumber $diskNum -ErrorAction SilentlyContinue
    }
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

        $smartFailed = ($smartctlData.smart_status -and $smartctlData.smart_status.passed -eq $false)
        if ($smartctlData.nvme_smart_health_information) {
            $cw = $null
            $me = $null
            if ($parsed.ContainsKey('criticalWarning')) { $cw = $parsed.criticalWarning }
            if ($parsed.ContainsKey('mediaErrors')) { $me = $parsed.mediaErrors }
            $healthStatus = Update-NvmeHealthStatus -HealthStatus $healthStatus -CriticalWarning $cw -MediaErrors $me -SmartFailed $smartFailed
        } elseif ($smartFailed) {
            $healthStatus = 'Critical'
        }
    }

    if ($dataSource -eq 'wmi' -and $diskNum -ge 0) {
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
            # Do not map ReadErrorsTotal/WriteErrorsTotal onto SMART sector columns
            # (those counters are not attributes 5/197/198 and cause false Critical).
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
                $cw = $null
                $me = $null
                if ($nvme.PSObject.Properties['CriticalWarning']) { $cw = $nvme.CriticalWarning }
                if ($nvme.PSObject.Properties['MediaErrors']) { $me = $nvme.MediaErrors }
                $healthStatus = Update-NvmeHealthStatus -HealthStatus $healthStatus -CriticalWarning $cw -MediaErrors $me -SmartFailed $false
            }
        }

        if (-not $isNvme -or $temperature -lt 0) {
            $ataSmart = Get-SmartFromWmi -ClassName 'MSStorageDriver_SmartData' -DiskNumber $diskNum -SizeBytes $sizeBytes
            if (-not $ataSmart) { $ataSmart = Get-SmartFromWmi -ClassName 'MSStorageDriver_FailurePredictData' -DiskNumber $diskNum -SizeBytes $sizeBytes }
            if ($ataSmart -and $ataSmart.PSObject.Properties['VendorSpecific']) {
                foreach ($row in @(Read-AtaSmartAttributeRows $ataSmart.VendorSpecific)) {
                    $attrId = [int]$row.id
                    $rawValue = [long]$row.raw
                    switch ($attrId) {
                        0x05 { if ($rawValue -ge 0 -and $reallocatedSectors -lt 0) { $reallocatedSectors = $rawValue } }
                        0x09 { if ($rawValue -gt 0 -and $powerOnHours -lt 0) { $powerOnHours = $rawValue } }
                        0x0C { if ($rawValue -gt 0 -and $powerCycleCount -lt 0) { $powerCycleCount = $rawValue } }
                        0xC0 { if ($rawValue -gt 0 -and $loadCycleCount -lt 0) { $loadCycleCount = $rawValue } }
                        # 0xC4 is Reallocation Event Count, NOT pending. Pending is only 0xC5.
                        0xC4 { if ($rawValue -ge 0 -and $reallocatedSectors -lt 0) { $reallocatedSectors = $rawValue } }
                        0xC5 { if ($rawValue -ge 0 -and $pendingSectors -lt 0) { $pendingSectors = $rawValue } }
                        0xC6 { if ($rawValue -ge 0 -and $uncorrectableSectors -lt 0) { $uncorrectableSectors = $rawValue } }
                        0xBE { if ($rawValue -gt 0 -and $rawValue -lt 200 -and $temperature -lt 0) { $temperature = $rawValue } }
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

        # Reliability fix (WMI-only): do not blindly trust Healthy when sector counters
        # already show damage. Thresholds: any counter >10 => Critical, >0 => Caution.
        # PredictFailure=Critical above already wins; this only upgrades Healthy/OK.
        if ($healthStatus -match '^(Healthy|OK|Unknown)$') {
            $worst = 0
            foreach ($v in @($reallocatedSectors, $pendingSectors, $uncorrectableSectors)) {
                if ($v -ne $null -and $v -ge 0 -and $v -gt $worst) { $worst = $v }
            }
            if ($worst -gt 10) { $healthStatus = 'Critical' }
            elseif ($worst -gt 0) {
                if ($healthStatus -ne 'Critical') { $healthStatus = 'Caution' }
            }
        }
    }

    $rawAttrs = @()
    if ($smartctlData) {
        if ($smartctlData.ata_smart_attributes -and $smartctlData.ata_smart_attributes.table) {
            foreach ($attr in $smartctlData.ata_smart_attributes.table) {
                $rawVal = if ($attr.raw -and $attr.raw.string) { $attr.raw.string } else { if ($attr.raw -and $attr.raw.value -ne $null) { [string]$attr.raw.value } else { '0' } }
                $rawAttrs += [ordered]@{
                    id        = [int]$attr.id
                    name      = if ($attr.name) { $attr.name } else { "Attribute $($attr.id)" }
                    value     = if ($attr.value -ne $null) { [string]$attr.value } else { '-' }
                    worst     = if ($attr.worst -ne $null) { [string]$attr.worst } else { '-' }
                    threshold = if ($attr.thresh -ne $null) { [string]$attr.thresh } else { '-' }
                    rawValue  = $rawVal
                    flags     = if ($attr.flags) { $attr.flags } else { '' }
                }
            }
        } elseif ($smartctlData.nvme_smart_health_information) {
            $nvme = $smartctlData.nvme_smart_health_information
            $nvmeMap = [ordered]@{
                'Critical Warning'     = if ($nvme.critical_warning -ne $null) { [string]$nvme.critical_warning } else { '0' }
                'Temperature'          = if ($nvme.temperature -ne $null) { [string]$nvme.temperature } else { '-' }
                'Available Spare'      = if ($nvme.available_spare -ne $null) { [string]$nvme.available_spare } else { '-' }
                'Available Spare Threshold' = if ($nvme.available_spare_threshold -ne $null) { [string]$nvme.available_spare_threshold } else { '-' }
                'Percentage Used'      = if ($nvme.percentage_used -ne $null) { [string]$nvme.percentage_used } else { '-' }
                'Data Units Read'      = if ($nvme.data_units_read -ne $null) { [string]$nvme.data_units_read } else { '-' }
                'Data Units Written'   = if ($nvme.data_units_written -ne $null) { [string]$nvme.data_units_written } else { '-' }
                'Host Read Commands'   = if ($nvme.host_reads -ne $null) { [string]$nvme.host_reads } else { '-' }
                'Host Write Commands'  = if ($nvme.host_writes -ne $null) { [string]$nvme.host_writes } else { '-' }
                'Controller Busy Time' = if ($nvme.controller_busy_time -ne $null) { [string]$nvme.controller_busy_time } else { '-' }
                'Power Cycles'         = if ($nvme.power_cycles -ne $null) { [string]$nvme.power_cycles } else { '-' }
                'Power On Hours'       = if ($nvme.power_on_hours -ne $null) { [string]$nvme.power_on_hours } else { '-' }
                'Unsafe Shutdowns'     = if ($nvme.unsafe_shutdowns -ne $null) { [string]$nvme.unsafe_shutdowns } else { '-' }
                'Media Errors'         = if ($nvme.media_errors -ne $null) { [string]$nvme.media_errors } else { '-' }
                'Error Information Log Entries' = if ($nvme.error_information_log_entries -ne $null) { [string]$nvme.error_information_log_entries } else { '-' }
            }
            $nvmeId = 1
            foreach ($kv in $nvmeMap.GetEnumerator()) {
                $rawAttrs += [ordered]@{
                    id        = $nvmeId
                    name      = $kv.Key
                    value     = '-'
                    worst     = '-'
                    threshold = '-'
                    rawValue  = $kv.Value
                    flags     = ''
                }
                $nvmeId++
            }
        }
    } elseif ($diskNum -ge 0) {
        $ataSmart = Get-SmartFromWmi -ClassName 'MSStorageDriver_SmartData' -DiskNumber $diskNum -SizeBytes $sizeBytes
        if (-not $ataSmart) { $ataSmart = Get-SmartFromWmi -ClassName 'MSStorageDriver_FailurePredictData' -DiskNumber $diskNum -SizeBytes $sizeBytes }
        if ($ataSmart -and $ataSmart.PSObject.Properties['VendorSpecific']) {
        foreach ($row in @(Read-AtaSmartAttributeRows $ataSmart.VendorSpecific)) {
            $rawAttrs += [ordered]@{
                id        = [int]$row.id
                name      = "Attribute $($row.id)"
                value     = [string]$row.value
                worst     = [string]$row.worst
                threshold = '-'
                rawValue  = [string]$row.raw
                flags     = ''
            }
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
        rawSmartAttributes        = $rawAttrs
    }
}

# -InputObject + @() so a single disk stays a JSON array (PS collapse would hide it).
ConvertTo-Json -InputObject ([ordered]@{
    drives = @($results)
    smartctlAvailable = ($null -ne $smartctlPath)
}) -Depth 5 -Compress
