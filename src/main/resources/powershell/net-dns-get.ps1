param([string]$AdapterName = "", [int]$InterfaceIndex = 0)

if (-not $AdapterName -and $InterfaceIndex -le 0) {
    ConvertTo-Json -Compress @{ success = $false; adapterName = ""; dnsServers = @(); error = "AdapterName or InterfaceIndex is required." }
    exit 1
}
if ($InterfaceIndex -le 0 -and $AdapterName -match '[\*\?]') {
    ConvertTo-Json -Compress @{ success = $false; adapterName = $AdapterName; dnsServers = @(); error = "Adapter name must not contain wildcard characters (* or ?)." }
    exit 1
}

try {
    if ($InterfaceIndex -gt 0) {
        $target = Get-NetAdapter -InterfaceIndex $InterfaceIndex -ErrorAction Stop
    } else {
        $target = Get-NetAdapter -ErrorAction Stop | Where-Object { $_.Name -eq $AdapterName }
    }
    if (-not $target) {
        ConvertTo-Json -Compress @{ success = $false; adapterName = $AdapterName; dnsServers = @(); error = "Adapter not found." }
        exit 1
    }
    if (@($target).Count -gt 1) {
        ConvertTo-Json -Compress @{ success = $false; adapterName = $AdapterName; dnsServers = @(); error = "Multiple adapters matched." }
        exit 1
    }
    $ifIndex = [int]$target.InterfaceIndex
    $label = if ($AdapterName) { $AdapterName } else { $target.Name }
    if ($ifIndex -le 0) {
        ConvertTo-Json -Compress @{ success = $false; adapterName = $label; dnsServers = @(); error = "Adapter has no valid interface index." }
        exit 1
    }
    $dnsServers = Get-DnsClientServerAddress -InterfaceIndex $ifIndex -ErrorAction Stop
    $addresses = @()
    foreach ($entry in $dnsServers) {
        if ($entry.ServerAddresses) {
            $addresses += $entry.ServerAddresses
        }
    }
    $output = @{
        success = $true
        adapterName = $label
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
