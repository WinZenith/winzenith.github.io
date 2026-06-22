param([string]$SSID = "")

if (-not $SSID) {
    ConvertTo-Json -Compress @{ success = $false; message = "SSID is required." }
    exit 1
}

$result = netsh wlan delete profile name="$SSID" 2>&1
if ($LASTEXITCODE -eq 0) {
    ConvertTo-Json -Compress @{ success = $true; message = "Profile '$SSID' forgotten." }
} else {
    ConvertTo-Json -Compress @{ success = $false; message = "$result" }
}
