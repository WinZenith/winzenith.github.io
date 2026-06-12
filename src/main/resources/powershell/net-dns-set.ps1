param(
    [string]$AdapterName,
    [string]$PrimaryDNS,
    [string]$SecondaryDNS
)

try {
    $serverAddresses = @()
    if ($PrimaryDNS -and $PrimaryDNS.Trim() -ne "") {
        $serverAddresses += $PrimaryDNS.Trim()
    }
    if ($SecondaryDNS -and $SecondaryDNS.Trim() -ne "") {
        $serverAddresses += $SecondaryDNS.Trim()
    }
    if ($serverAddresses.Count -eq 0) {
        # Reset to DHCP (automatic DNS)
        Set-DnsClientServerAddress -InterfaceAlias $AdapterName -ResetServerAddresses -ErrorAction Stop
        $output = @{
            success = $true
            message = "DNS reset to automatic (DHCP)."
            adapterName = $AdapterName
            dnsServers = @()
        }
    } else {
        Set-DnsClientServerAddress -InterfaceAlias $AdapterName -ServerAddresses $serverAddresses -ErrorAction Stop
        $output = @{
            success = $true
            message = "DNS servers updated successfully."
            adapterName = $AdapterName
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
}
