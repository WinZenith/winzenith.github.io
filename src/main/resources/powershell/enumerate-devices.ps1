# Enumerate installed drivers as JSON array.
# Uses three sources for maximum coverage:
#   1. Win32_PnPSignedDriver (traditional PnP signed drivers)
#   2. Win32_VideoController (GPU adapters that may not appear in PnPSignedDriver)
#   3. Get-PnpDevice -Class Display (direct PnP tree query, most reliable for DCH GPU drivers)
$ErrorActionPreference = 'Continue'
$seen = @{}
$drivers = @()

Get-CimInstance Win32_PnPSignedDriver -ErrorAction SilentlyContinue |
    Where-Object { $_.DeviceID -and $_.DriverVersion } |
    ForEach-Object {
        $hwIds = $_.DeviceID
        $driverDate = ''
        if ($_.DriverDate) {
            try {
                $driverDate = [Management.ManagementDateTimeConverter]::ToDateTime($_.DriverDate).ToString('yyyy-MM-dd')
            } catch {
                $driverDate = ''
            }
        }
        $entry = [ordered]@{
            deviceId       = $_.DeviceID
            friendlyName   = if ($_.DeviceName) { $_.DeviceName } else { $_.DeviceID }
            hardwareIds    = $hwIds
            provider       = $_.DriverProviderName
            driverVersion  = $_.DriverVersion
            infName        = $_.InfName
            driverKey      = if ($_.Driver) { $_.Driver } else { '' }
            status         = 'OK'
            releaseDate    = $driverDate
        }
        $seen[$_.DeviceID] = $true
        $drivers += $entry
    }

$videoControllers = @(Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue |
    Where-Object { $_.PNPDeviceID -and -not $seen.ContainsKey($_.PNPDeviceID) })

if ($videoControllers.Count -gt 0) {
    $vcInfMap = @{}
    $videoControllers | ForEach-Object { $_.PNPDeviceID } |
        Get-PnpDeviceProperty -KeyName 'DEVPKEY_Device_DriverInfPath' -ErrorAction SilentlyContinue |
        ForEach-Object {
            if ($_.Data) { $vcInfMap[$_.InstanceId] = [string]$_.Data }
        }

    foreach ($vc in $videoControllers) {
        $id = $vc.PNPDeviceID
        $infPath = if ($vcInfMap.ContainsKey($id)) { $vcInfMap[$id] } else { '' }
        if ($infPath -match '[\\/]([^\\/]+\.inf)$') { $infPath = $Matches[1] }
        $ver = if ($vc.DriverVersion) { $vc.DriverVersion } else { '' }
        $driverDate = ''
        if ($vc.DriverDate) {
            try {
                $driverDate = [Management.ManagementDateTimeConverter]::ToDateTime($vc.DriverDate).ToString('yyyy-MM-dd')
            } catch {
                $driverDate = ''
            }
        }
        $entry = [ordered]@{
            deviceId       = $id
            friendlyName   = if ($vc.Name) { $vc.Name } else { $id }
            hardwareIds    = $id
            provider       = $vc.AdapterCompatibility
            driverVersion  = $ver
            infName        = $infPath
            driverKey      = ''
            status         = 'OK'
            releaseDate    = $driverDate
        }
        $seen[$id] = $true
        $drivers += $entry
    }
}

# Batch-fetch device properties for Display class devices (single pipeline call instead of N calls)
$displayDevices = Get-PnpDevice -Class Display -PresentOnly -ErrorAction SilentlyContinue |
    Where-Object { $_.InstanceId -and -not $seen.ContainsKey($_.InstanceId) }

if ($displayDevices) {
    $propMap = @{}
    $displayDevices | Get-PnpDeviceProperty -KeyName 'DEVPKEY_Device_DriverVersion','DEVPKEY_Device_DriverDate','DEVPKEY_Device_DriverInfPath' -ErrorAction SilentlyContinue |
        ForEach-Object {
            $id = $_.InstanceId
            if (-not $propMap.ContainsKey($id)) { $propMap[$id] = @{} }
            $key = $_.KeyName
            if ($key -eq 'DEVPKEY_Device_DriverVersion') { $propMap[$id]['version'] = $_.Data }
            if ($key -eq 'DEVPKEY_Device_DriverDate') { $propMap[$id]['date'] = $_.Data }
            if ($key -eq 'DEVPKEY_Device_DriverInfPath') { $propMap[$id]['infPath'] = $_.Data }
        }

    foreach ($dev in $displayDevices) {
        $id = $dev.InstanceId
        $ver = ''
        $driverDate = ''
        $infPath = ''
        if ($propMap.ContainsKey($id)) {
            $v = $propMap[$id]['version']
            if ($null -ne $v) { $ver = [string]$v }
            $d = $propMap[$id]['date']
            if ($null -ne $d) {
                try { $driverDate = $d.ToString('yyyy-MM-dd') } catch { $driverDate = '' }
            }
            $ip = $propMap[$id]['infPath']
            if ($null -ne $ip) {
                $infPath = [string]$ip
                if ($infPath -match '[\\/]([^\\/]+\.inf)$') { $infPath = $Matches[1] }
            }
        }
        $entry = [ordered]@{
            deviceId       = $id
            friendlyName   = if ($dev.FriendlyName) { $dev.FriendlyName } else { $id }
            hardwareIds    = $id
            provider       = ''
            driverVersion  = $ver
            infName        = $infPath
            driverKey      = ''
            status         = 'OK'
            releaseDate    = $driverDate
        }
        $seen[$id] = $true
        $drivers += $entry
    }
}

$drivers | ConvertTo-Json -Depth 4 -Compress
