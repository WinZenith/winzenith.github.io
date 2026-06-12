param([string]$AdapterName)

try {
    $dnsServers = Get-DnsClientServerAddress -InterfaceAlias $AdapterName -AddressFamily IPv4 -ErrorAction Stop
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
