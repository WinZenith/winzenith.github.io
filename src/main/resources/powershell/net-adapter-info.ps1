param()

$adapters = Get-NetAdapter | Select-Object Name, InterfaceDescription, Status, LinkSpeed, MacAddress, AdminStatus
$ipInfo = Get-NetIPAddress -AddressFamily IPv4 | Select-Object InterfaceAlias, IPAddress

$result = $adapters | ForEach-Object {
    $adapter = $_
    $ip = ($ipInfo | Where-Object { $_.InterfaceAlias -eq $adapter.Name } | Select-Object -First 1).IPAddress
    [PSCustomObject]@{
        Name = $adapter.Name
        InterfaceDescription = $adapter.InterfaceDescription
        Status = $adapter.Status
        LinkSpeed = $adapter.LinkSpeed
        MacAddress = $adapter.MacAddress
        IPAddress = if ($ip) { $ip } else { "" }
        AdminStatus = $adapter.AdminStatus
    }
}

if ($result.Count -eq 0) {
    Write-Output "[]"
} else {
    ConvertTo-Json -Compress $result
}
