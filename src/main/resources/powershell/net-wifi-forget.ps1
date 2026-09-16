param([string]$SSID = "")

if (-not $SSID) {
    ConvertTo-Json -Compress @{ success = $false; message = "SSID is required." }
    exit 1
}

# Safe handling: the SSID is concatenated into a netsh name="..." argument, so a
# double quote would break out and inject extra netsh arguments (deleting the
# wrong profile). Defense in depth with the Java-side sanitizeSsid: reject
# quote/control characters here too, since netsh has no escape sequence for
# an embedded quote. Spaces are legal in SSIDs and handled by the quotes.
try {
    if ($SSID -match '[\*\?]') {
        ConvertTo-Json -Compress @{ success = $false; message = "SSID must not contain wildcard characters (* or ?)." }
        exit 1
    }
    if ($SSID -match '["`$;\|&<>\r\n]') {
        ConvertTo-Json -Compress @{ success = $false; message = "SSID contains an unsupported character and was rejected for safety." }
        exit 1
    }
    $argName = 'name="' + $SSID + '"'
    $result = & netsh wlan delete profile $argName 2>&1
    if ($LASTEXITCODE -eq 0) {
        ConvertTo-Json -Compress @{ success = $true; message = "Profile '$SSID' forgotten." }
    } else {
        $msg = ($result | Out-String).Trim()
        if (-not $msg) { $msg = "Failed to delete profile." }
        ConvertTo-Json -Compress @{ success = $false; message = $msg }
        exit 1
    }
} catch {
    ConvertTo-Json -Compress @{ success = $false; message = $_.Exception.Message }
    exit 1
}
