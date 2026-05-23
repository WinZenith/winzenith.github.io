# Enumerate signed PnP drivers as JSON array (fast path: signed drivers only).
$ErrorActionPreference = 'Stop'
$drivers = Get-CimInstance Win32_PnPSignedDriver |
    Where-Object { $_.DeviceID -and $_.DriverVersion } |
    ForEach-Object {
        $hwIds = ''
        try {
            $device = Get-PnpDevice -Id $_.DeviceID -ErrorAction SilentlyContinue
            if ($device) {
                $hwIds = ($device.HardwareId) -join ';'
            }
        } catch {
            # Fallback to DeviceID if PnpDevice fails
            $hwIds = $_.DeviceID
        }
        [ordered]@{
            deviceId       = $_.DeviceID
            friendlyName   = if ($_.DeviceName) { $_.DeviceName } else { $_.DeviceID }
            hardwareIds    = $hwIds
            provider       = $_.DriverProviderName
            driverVersion  = $_.DriverVersion
            infName        = $_.InfName
            driverKey      = if ($_.Driver) { $_.Driver } else { '' }
            status         = 'OK'
        }
    }
# Debug: Log sample hardware IDs
$sampleDrivers = $drivers | Select-Object -First 5 | ForEach-Object {
    "Driver: $($_.friendlyName), HW IDs: $($_.hardwareIds), Provider: $($_.provider)"
}
Write-Host "Sample drivers (first 5):"
$sampleDrivers | ForEach-Object { Write-Host $_ }
$drivers | ConvertTo-Json -Depth 4 -Compress
