# Enumerate signed PnP drivers as JSON array (fast path: signed drivers only).
$ErrorActionPreference = 'Stop'
$drivers = Get-CimInstance Win32_PnPSignedDriver |
    Where-Object { $_.DeviceID -and $_.DriverVersion } |
    ForEach-Object {
        [ordered]@{
            deviceId       = $_.DeviceID
            friendlyName   = if ($_.DeviceName) { $_.DeviceName } else { $_.DeviceID }
            hardwareIds    = ''
            provider       = $_.DriverProviderName
            driverVersion  = $_.DriverVersion
            infName        = $_.InfName
            driverKey      = if ($_.Driver) { $_.Driver } else { '' }
            status         = 'OK'
        }
    }
$drivers | ConvertTo-Json -Depth 4 -Compress
