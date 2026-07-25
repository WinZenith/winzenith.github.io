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

Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue |
    Where-Object { $_.PNPDeviceID -and -not $seen.ContainsKey($_.PNPDeviceID) } |
    ForEach-Object {
        $ver = if ($_.DriverVersion) { $_.DriverVersion } else { '' }
        $driverDate = ''
        if ($_.DriverDate) {
            try {
                $driverDate = [Management.ManagementDateTimeConverter]::ToDateTime($_.DriverDate).ToString('yyyy-MM-dd')
            } catch {
                $driverDate = ''
            }
        }
        $entry = [ordered]@{
            deviceId       = $_.PNPDeviceID
            friendlyName   = if ($_.Name) { $_.Name } else { $_.PNPDeviceID }
            hardwareIds    = $_.PNPDeviceID
            provider       = $_.AdapterCompatibility
            driverVersion  = $ver
            infName        = ''
            driverKey      = ''
            status         = 'OK'
            releaseDate    = $driverDate
        }
        $seen[$_.PNPDeviceID] = $true
        $drivers += $entry
    }

# Batch-fetch device properties for Display class devices (single pipeline call instead of N calls)
$displayDevices = Get-PnpDevice -Class Display -PresentOnly -ErrorAction SilentlyContinue |
    Where-Object { $_.InstanceId -and -not $seen.ContainsKey($_.InstanceId) }

if ($displayDevices) {
    $propMap = @{}
    $displayDevices | Get-PnpDeviceProperty -KeyName 'DEVPKEY_Device_DriverVersion','DEVPKEY_Device_DriverDate' -ErrorAction SilentlyContinue |
        ForEach-Object {
            $id = $_.InstanceId
            if (-not $propMap.ContainsKey($id)) { $propMap[$id] = @{} }
            $key = $_.KeyName
            if ($key -eq 'DEVPKEY_Device_DriverVersion') { $propMap[$id]['version'] = $_.Data }
            if ($key -eq 'DEVPKEY_Device_DriverDate') { $propMap[$id]['date'] = $_.Data }
        }

    foreach ($dev in $displayDevices) {
        $id = $dev.InstanceId
        $ver = ''
        $driverDate = ''
        if ($propMap.ContainsKey($id)) {
            $v = $propMap[$id]['version']
            if ($null -ne $v) { $ver = [string]$v }
            $d = $propMap[$id]['date']
            if ($null -ne $d) {
                try { $driverDate = $d.ToString('yyyy-MM-dd') } catch { $driverDate = '' }
            }
        }
        $entry = [ordered]@{
            deviceId       = $id
            friendlyName   = if ($dev.FriendlyName) { $dev.FriendlyName } else { $id }
            hardwareIds    = $id
            provider       = ''
            driverVersion  = $ver
            infName        = ''
            driverKey      = ''
            status         = 'OK'
            releaseDate    = $driverDate
        }
        $seen[$id] = $true
        $drivers += $entry
    }
}

$drivers | ConvertTo-Json -Depth 4 -Compress
