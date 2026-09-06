# Read-only Wi-Fi survey: parse `netsh wlan show networks mode=bssid`.
# No connection changes are made.
try {
    $lines = netsh wlan show networks mode=bssid 2>$null
    if (-not $lines) { Write-Output "[]"; exit 0 }
    $networks = @()
    $cur = $null
    foreach ($raw in $lines) {
        $line = $raw.Trim()
        if (-not $line) { continue }
        if ($line -match '^SSID\s+\d+\s*:\s*(.*)$') {
            if ($cur -and $cur.ssid) { $networks += $cur }
            $cur = [PSCustomObject]@{ ssid = $Matches[1].Trim(); bssid = ""; signalPercent = 0; auth = ""; channel = ""; radio = "" }
        } elseif ($line -match '^(BSSID|BSSID \d+)\s*:\s*(.*)$' -and $cur) {
            if (-not $cur.bssid) { $cur.bssid = $Matches[2].Trim() }
        } elseif ($line -match '^Signal\s*:\s*(\d+)\s*%?' -and $cur) {
            $cur.signalPercent = [int]$Matches[1]
        } elseif ($line -match '^Authentication\s*:\s*(.*)$' -and $cur) {
            if (-not $cur.auth) { $cur.auth = $Matches[1].Trim() }
        } elseif ($line -match '^Channel\s*:\s*(.*)$' -and $cur) {
            if (-not $cur.channel) { $cur.channel = $Matches[1].Trim() }
        } elseif ($line -match '^Radio type\s*:\s*(.*)$' -and $cur) {
            if (-not $cur.radio) { $cur.radio = $Matches[1].Trim() }
        }
    }
    if ($cur -and $cur.ssid) { $networks += $cur }
    if ($networks.Count -eq 0) { Write-Output "[]"; exit 0 }
    $out = $networks | Select-Object @{n='ssid';e={$_.ssid}}, @{n='bssid';e={$_.bssid}}, @{n='signalPercent';e={[int]$_.signalPercent}}, @{n='auth';e={$_.auth}}, @{n='channel';e={$_.channel}}, @{n='radio';e={$_.radio}}
    ConvertTo-Json -Compress @($out) -Depth 3
} catch {
    Write-Output "[]"
}
