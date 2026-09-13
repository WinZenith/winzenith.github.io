param()

try {
    $adapters = @(Get-NetAdapter -ErrorAction Stop | Select-Object Name, InterfaceDescription, Status, LinkSpeed, MacAddress, AdminStatus)
    $ipInfo = @()
    try { $ipInfo = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object InterfaceAlias, IPAddress, PrefixOrigin) } catch {}
    if (-not $ipInfo) { $ipInfo = @() }
    $gateways = @{}
    try {
        foreach ($rc in @(Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue | Select-Object InterfaceAlias, NextHop)) {
            if ($rc.InterfaceAlias -and -not $gateways.ContainsKey($rc.InterfaceAlias)) { $gateways[$rc.InterfaceAlias] = $rc.NextHop }
        }
    } catch {}
    $dnsMap = @{}
    try {
        # All address families (IPv4 + IPv6), merged per alias so applied IPv6
        # DNS stays visible instead of being overwritten by the last family.
        foreach ($d in @(Get-DnsClientServerAddress -ErrorAction SilentlyContinue | Select-Object InterfaceAlias, ServerAddresses)) {
            if ($d.InterfaceAlias) {
                $addrs = @($d.ServerAddresses | Where-Object { $_ })
                if ($addrs.Count -gt 0) {
                    if ($dnsMap.ContainsKey($d.InterfaceAlias)) {
                        $dnsMap[$d.InterfaceAlias] = $dnsMap[$d.InterfaceAlias] + ", " + ($addrs -join ", ")
                    } else {
                        $dnsMap[$d.InterfaceAlias] = ($addrs -join ", ")
                    }
                }
            }
        }
    } catch {}

    $result = @()
    foreach ($adapter in $adapters) {
        $ipRow = ($ipInfo | Where-Object { $_.InterfaceAlias -eq $adapter.Name } | Select-Object -First 1)
        $ip = if ($ipRow) { $ipRow.IPAddress } else { "" }
        $dhcp = ""
        try {
            # -InterfaceAlias accepts wildcards: escape so a literal "*" in a
            # name cannot match the wrong interface (read-only DHCP flag).
            $escapedIf = [WildcardPattern]::Escape($adapter.Name)
            $cfg = Get-NetIPInterface -InterfaceAlias $escapedIf -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($cfg) { $dhcp = $cfg.Dhcp }
        } catch {}
        $gw = if ($gateways.ContainsKey($adapter.Name)) { $gateways[$adapter.Name] } else { "" }
        $dns = if ($dnsMap.ContainsKey($adapter.Name)) { $dnsMap[$adapter.Name] } else { "" }
        $result += [PSCustomObject]@{
            Name = $adapter.Name
            InterfaceDescription = $adapter.InterfaceDescription
            Status = $adapter.Status
            LinkSpeed = if ($adapter.LinkSpeed) { $adapter.LinkSpeed.ToString() } else { "" }
            MacAddress = $adapter.MacAddress
            IPAddress = if ($ip) { $ip } else { "" }
            AdminStatus = $adapter.AdminStatus.ToString()
            Dhcp = if ($dhcp) { "$dhcp" } else { "" }
            Gateway = if ($gw) { "$gw" } else { "" }
            DnsServers = if ($dns) { "$dns" } else { "" }
        }
    }

    if (@($result).Count -eq 0) {
        Write-Output "[]"
    } else {
        ConvertTo-Json -Compress @($result) -Depth 3
    }
} catch {
    $output = @{ success = $false; error = $_.Exception.Message }
    ConvertTo-Json -Compress $output
    exit 1
}
