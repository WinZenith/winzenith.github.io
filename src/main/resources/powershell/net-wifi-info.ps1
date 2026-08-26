$info = @{
    ssid          = ""
    state         = "disconnected"
    signalPercent = 0
    radioType     = ""
    channel       = ""
    receiveRate   = ""
    transmitRate  = ""
}

$wifi = Get-NetAdapter | Where-Object { $_.InterfaceDescription -match 'Wireless|Wi-Fi|802\.11' } | Select-Object -First 1
if ($wifi) {
    if ($wifi.Status -eq "Up") { $info.state = "connected" }
    else { $info.state = $wifi.Status.ToLower() }
    if ($wifi.LinkSpeed) { $info.receiveRate = $wifi.LinkSpeed }

    $props = Get-NetAdapterAdvancedProperty -Name $wifi.Name -ErrorAction SilentlyContinue
    foreach ($p in $props) {
        if ($p.DisplayName -match 'Wireless Mode|802\.11.*Wireless|Radio Type') {
            $info.radioType = $p.DisplayValue
        }
    }

    $connProfile = Get-NetConnectionProfile -ErrorAction SilentlyContinue |
        Where-Object { $_.InterfaceAlias -eq $wifi.Name } | Select-Object -First 1
    if ($connProfile) {
        $info.ssid = $connProfile.Name
    }
}

try {
    $wlanOutput = netsh wlan show interfaces 2>$null
    if ($wlanOutput) {
        foreach ($rawLine in $wlanOutput) {
            $line = $rawLine.Trim()
            if (-not $line -or $line -notmatch ':') { continue }
            # Generic key : value split - locale independent
            if ($line -match '^(.*?)\s*:\s*(.+)$') {
                $key = $Matches[1].Trim().ToLower()
                $val = $Matches[2].Trim()
                if (-not $val) { continue }
                # SSID - key is literally SSID in all locales (abbreviation) but also handle localized variants just in case
                if ($key -match 'ssid' -and $val -ne "" -and $val -notmatch '^<.*>$') {
                    $info.ssid = $val
                }
                elseif ($key -match 'signal|signalst.rke') {
                    if ($val -match '(\d+)%') { $info.signalPercent = [int]$Matches[1] }
                }
                elseif ($key -match 'radio type|funktyp|type radio|radio-typ') {
                    $info.radioType = $val
                }
                elseif ($key -match 'channel|kanal|canal') {
                    $info.channel = $val
                }
                elseif ($key -match 'receive rate|empfangsrate|taux de r.ception|velocidad de recep|rx rate') {
                    $info.receiveRate = $val
                }
                elseif ($key -match 'transmit rate|bertragungsrate|taux de transmission|velocidad de trans|tx rate') {
                    $info.transmitRate = $val
                }
                elseif ($key -match 'state|status|zustand|tat') {
                    # Normalize state from netsh if Get-NetAdapter didn't give it
                    if ($info.state -eq "disconnected" -and $val) {
                        $low = $val.ToLower()
                        if ($low -match 'connected|verbunden|connect') { $info.state = "connected" }
                        elseif ($low -match 'disconnected|getrennt|nicht verbunden') { $info.state = "disconnected" }
                        else { $info.state = $val }
                    }
                }
            }
        }
    }
} catch {
    $info.state = "error"
}

ConvertTo-Json -Compress $info
