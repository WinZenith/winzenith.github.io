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
}

$props = Get-NetAdapterAdvancedProperty -Name $wifi.Name -ErrorAction SilentlyContinue
foreach ($p in $props) {
    if ($p.DisplayName -eq "802.11n/ac Wireless Mode") { $info.radioType = $p.DisplayValue }
}

$connProfile = Get-NetConnectionProfile -ErrorAction SilentlyContinue |
    Where-Object { $_.InterfaceAlias -eq $wifi.Name } | Select-Object -First 1
if ($connProfile) {
    $info.ssid = $connProfile.Name
}

try {
    $wlanOutput = netsh wlan show interfaces 2>$null
    if ($wlanOutput -and ($wlanOutput | Select-String 'Signal')) {
        foreach ($line in $wlanOutput) {
            $line = $line.Trim()
            if ($line -match '^\s*SSID\s*:\s*(.+)$' -and $Matches[1].Trim() -ne "") {
                $info.ssid = $Matches[1].Trim()
            }
            elseif ($line -match '^\s*Signal\s*:\s*(\d+)%') {
                $info.signalPercent = [int]$Matches[1]
            }
            elseif ($line -match '^\s*Radio type\s*:\s*(.+)$') {
                $info.radioType = $Matches[1].Trim()
            }
            elseif ($line -match '^\s*Channel\s*:\s*(.+)$') {
                $info.channel = $Matches[1].Trim()
            }
            elseif ($line -match '^\s*Receive rate\s*:\s*(.+)$') {
                $info.receiveRate = $Matches[1].Trim()
            }
            elseif ($line -match '^\s*Transmit rate\s*:\s*(.+)$') {
                $info.transmitRate = $Matches[1].Trim()
            }
        }
    }
} catch {}

ConvertTo-Json -Compress $info
