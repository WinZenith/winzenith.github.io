param([string]$SSID = "")

if (-not $SSID) {
    ConvertTo-Json -Compress @{ success = $false; message = "SSID is required." }
    exit 1
}

# Safe handling: avoid double-quoted interpolation; use argument list with proper escaping
# netsh expects name="SSID" with quotes only if SSID contains spaces. PowerShell will
# handle quoting of the argument value automatically when passed as a single token.
try {
    # Properly quote SSID for netsh: name="SSID with spaces". Escape embedded double quotes.
    # Passing as single token with quotes ensures netsh parses spaced SSIDs correctly.
    $escapedSsid = $SSID.Replace('"', '""')
    $argName = 'name="' + $escapedSsid + '"'
    $result = & netsh wlan delete profile $argName 2>&1
    if ($LASTEXITCODE -eq 0) {
        ConvertTo-Json -Compress @{ success = $true; message = "Profile '$SSID' forgotten." }
    } else {
        $msg = ($result | Out-String).Trim()
        if (-not $msg) { $msg = "Failed to delete profile." }
        ConvertTo-Json -Compress @{ success = $false; message = $msg }
    }
} catch {
    ConvertTo-Json -Compress @{ success = $false; message = $_.Exception.Message }
}
