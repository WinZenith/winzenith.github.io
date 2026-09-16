param([string]$AdapterName)

if (-not $AdapterName) {
    ConvertTo-Json -Compress @{ success = $false; adapterName = ""; dnsServers = @(); error = "AdapterName is required." }
    exit 1
}
if ($AdapterName -match '[\*\?]') {
    ConvertTo-Json -Compress @{ success = $false; adapterName = $AdapterName; dnsServers = @(); error = "Adapter name must not contain wildcard characters (* or ?)." }
    exit 1
}

try {
    $target = Get-NetAdapter -ErrorAction Stop | Where-Object { $_.Name -eq $AdapterName }
    if (-not $target) {
        ConvertTo-Json -Compress @{ success = $false; adapterName = $AdapterName; dnsServers = @(); error = "Adapter not found: $AdapterName" }
        exit 1
    }
    if (@($target).Count -gt 1) {
        ConvertTo-Json -Compress @{ success = $false; adapterName = $AdapterName; dnsServers = @(); error = "Multiple adapters matched." }
        exit 1
    }
    $ifIndex = [int]$target.InterfaceIndex
    if ($ifIndex -le 0) {
        ConvertTo-Json -Compress @{ success = $false; adapterName = $AdapterName; dnsServers = @(); error = "Adapter has no valid interface index." }
        exit 1
    }
    # All address families (IPv4 + IPv6): filtering IPv4-only hid applied IPv6
    # presets, so the UI reported "None (DHCP)" right after a successful apply.
    $dnsServers = Get-DnsClientServerAddress -InterfaceIndex $ifIndex -ErrorAction Stop
    $addresses = @()
    foreach ($entry in $dnsServers) {
        if ($entry.ServerAddresses) {
            $addresses += $entry.ServerAddresses
        }
    }
    $output = @{
        success = $true
        adapterName = $AdapterName
        dnsServers = $addresses
    }
    ConvertTo-Json -Compress $output
} catch {
    $output = @{
        success = $false
        adapterName = $AdapterName
        dnsServers = @()
        error = $_.Exception.Message
    }
    ConvertTo-Json -Compress $output
}
