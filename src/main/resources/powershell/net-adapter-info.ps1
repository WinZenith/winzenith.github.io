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
        foreach ($d in @(Get-DnsClientServerAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object InterfaceAlias, ServerAddresses)) {
            if ($d.InterfaceAlias) { $dnsMap[$d.InterfaceAlias] = (($d.ServerAddresses | Where-Object { $_ }) -join ", ") }
        }
    } catch {}

    $result = @()
    foreach ($adapter in $adapters) {
        $ipRow = ($ipInfo | Where-Object { $_.InterfaceAlias -eq $adapter.Name } | Select-Object -First 1)
        $ip = if ($ipRow) { $ipRow.IPAddress } else { "" }
        $dhcp = ""
        try {
            $cfg = Get-NetIPInterface -InterfaceAlias $adapter.Name -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -First 1
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
    Write-Output "[]"
}
