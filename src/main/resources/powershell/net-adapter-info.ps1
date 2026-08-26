param()

try {
    $adapters = @(Get-NetAdapter -ErrorAction Stop | Select-Object Name, InterfaceDescription, Status, LinkSpeed, MacAddress, AdminStatus)
    $ipInfo = @()
    try { $ipInfo = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object InterfaceAlias, IPAddress) } catch {}
    if (-not $ipInfo) { $ipInfo = @() }

    $result = @()
    foreach ($adapter in $adapters) {
        $ip = ($ipInfo | Where-Object { $_.InterfaceAlias -eq $adapter.Name } | Select-Object -First 1).IPAddress
        $result += [PSCustomObject]@{
            Name = $adapter.Name
            InterfaceDescription = $adapter.InterfaceDescription
            Status = $adapter.Status
            LinkSpeed = if ($adapter.LinkSpeed) { $adapter.LinkSpeed.ToString() } else { "" }
            MacAddress = $adapter.MacAddress
            IPAddress = if ($ip) { $ip } else { "" }
            AdminStatus = $adapter.AdminStatus.ToString()
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
