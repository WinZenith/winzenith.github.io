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

Get-PnpDevice -Class Display -PresentOnly -ErrorAction SilentlyContinue |
    Where-Object { $_.InstanceId -and -not $seen.ContainsKey($_.InstanceId) } |
    ForEach-Object {
        $dev = $_
        $ver = ''
        $driverDate = ''
        try {
            $ver = (Get-PnpDeviceProperty -InstanceId $dev.InstanceId -KeyName 'DEVPKEY_Device_DriverVersion' -ErrorAction SilentlyContinue).Data
            if ($null -eq $ver) { $ver = '' }
            $dateObj = (Get-PnpDeviceProperty -InstanceId $dev.InstanceId -KeyName 'DEVPKEY_Device_DriverDate' -ErrorAction SilentlyContinue).Data
            if ($null -ne $dateObj) {
                $driverDate = $dateObj.ToString('yyyy-MM-dd')
            }
        } catch {
            $ver = ''
        }
        $entry = [ordered]@{
            deviceId       = $dev.InstanceId
            friendlyName   = if ($dev.FriendlyName) { $dev.FriendlyName } else { $dev.InstanceId }
            hardwareIds    = $dev.InstanceId
            provider       = ''
            driverVersion  = $ver
            infName        = ''
            driverKey      = ''
            status         = 'OK'
            releaseDate    = $driverDate
        }
        $seen[$dev.InstanceId] = $true
        $drivers += $entry
    }

$drivers | ConvertTo-Json -Depth 4 -Compress
