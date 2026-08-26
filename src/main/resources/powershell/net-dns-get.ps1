param([string]$AdapterName)

try {
    # Escape wildcard chars in adapter name to prevent matching all adapters (e.g., "*" -> literal "*")
    $escapedAlias = [WildcardPattern]::Escape($AdapterName)
    $dnsServers = Get-DnsClientServerAddress -InterfaceAlias $escapedAlias -AddressFamily IPv4 -ErrorAction Stop
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
