param(
    [string]$AdapterName = "",
    [int]$InterfaceIndex = 0,
    [string]$PrimaryDNS,
    [string]$SecondaryDNS
)

if (-not $AdapterName -and $InterfaceIndex -le 0) {
    ConvertTo-Json -Compress @{ success = $false; message = "AdapterName or InterfaceIndex is required."; adapterName = ""; dnsServers = @() }
    exit 1
}
if ($InterfaceIndex -le 0 -and $AdapterName -match '[\*\?]') {
    ConvertTo-Json -Compress @{ success = $false; message = "Adapter name must not contain wildcard characters (* or ?)."; adapterName = $AdapterName; dnsServers = @() }
    exit 1
}

try {
    if ($InterfaceIndex -gt 0) {
        $target = Get-NetAdapter -InterfaceIndex $InterfaceIndex -ErrorAction Stop
    } else {
        $target = Get-NetAdapter -ErrorAction Stop | Where-Object { $_.Name -eq $AdapterName }
    }
    if (-not $target) {
        ConvertTo-Json -Compress @{ success = $false; message = "Adapter not found."; adapterName = $AdapterName; dnsServers = @() }
        exit 1
    }
    if (@($target).Count -gt 1) {
        ConvertTo-Json -Compress @{ success = $false; message = "Multiple adapters matched; refusing to change DNS."; adapterName = $AdapterName; dnsServers = @() }
        exit 1
    }
    $ifIndex = [int]$target.InterfaceIndex
    $label = if ($AdapterName) { $AdapterName } else { $target.Name }
    if ($ifIndex -le 0) {
        ConvertTo-Json -Compress @{ success = $false; message = "Adapter has no valid interface index."; adapterName = $label; dnsServers = @() }
        exit 1
    }
    $serverAddresses = @()
    if ($PrimaryDNS -and $PrimaryDNS.Trim() -ne "") {
        $serverAddresses += $PrimaryDNS.Trim()
    }
    if ($SecondaryDNS -and $SecondaryDNS.Trim() -ne "") {
        $serverAddresses += $SecondaryDNS.Trim()
    }
    if ($serverAddresses.Count -eq 0) {
        Set-DnsClientServerAddress -InterfaceIndex $ifIndex -ResetServerAddresses -ErrorAction Stop
        $output = @{
            success = $true
            message = "DNS reset to automatic (DHCP)."
            adapterName = $label
            dnsServers = @()
        }
    } else {
        Set-DnsClientServerAddress -InterfaceIndex $ifIndex -ServerAddresses $serverAddresses -ErrorAction Stop
        $output = @{
            success = $true
            message = "DNS servers updated successfully."
            adapterName = $label
            dnsServers = $serverAddresses
        }
    }
    ConvertTo-Json -Compress $output
} catch {
    $output = @{
        success = $false
        message = "Failed to set DNS servers: " + $_.Exception.Message
        adapterName = $AdapterName
        dnsServers = @()
    }
    ConvertTo-Json -Compress $output
    exit 1
}
