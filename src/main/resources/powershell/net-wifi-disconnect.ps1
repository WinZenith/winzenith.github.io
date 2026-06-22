$result = netsh wlan disconnect 2>&1
if ($LASTEXITCODE -eq 0) {
    ConvertTo-Json -Compress @{ success = $true; message = "Wi-Fi disconnected." }
} else {
    ConvertTo-Json -Compress @{ success = $false; message = "$result" }
}
