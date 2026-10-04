param([string]$AdapterName = "", [int]$InterfaceIndex = 0)

if (-not $AdapterName -and $InterfaceIndex -le 0) {
    ConvertTo-Json -Compress @{ error = "AdapterName or InterfaceIndex is required" }
    exit 1
}

try {
    if ($InterfaceIndex -gt 0) {
        $ad = Get-NetAdapter -InterfaceIndex $InterfaceIndex -ErrorAction Stop
    } else {
        $escapedName = [WildcardPattern]::Escape($AdapterName)
        $ad = Get-NetAdapter -Name $escapedName -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if (-not $ad) {
        ConvertTo-Json -Compress @{ error = "Adapter not found" }
        exit 1
    }
    $escapedName = [WildcardPattern]::Escape($ad.Name)
    $label = if ($AdapterName) { $AdapterName } else { $ad.Name }

    $props = Get-NetAdapterAdvancedProperty -InterfaceIndex $ad.InterfaceIndex -ErrorAction SilentlyContinue |
        Select-Object DisplayName, DisplayValue |
        ForEach-Object { @{ Name = $_.DisplayName; Value = $_.DisplayValue } }
    if (-not $props) { $props = @() }

    try {
        $ipIf = Get-NetIPInterface -InterfaceIndex $ad.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($ipIf -and $ipIf.NlMtu) { $props += @{ Name = "MTU (IPv4)"; Value = "$($ipIf.NlMtu)" } }
    } catch {}
    try {
        if ($ad.DriverVersion) { $props += @{ Name = "Driver Version"; Value = "$($ad.DriverVersion)" } }
        if ($ad.DriverDate) { $props += @{ Name = "Driver Date"; Value = "$($ad.DriverDate)" } }
        if ($ad.MediaType) { $props += @{ Name = "Media Type"; Value = "$($ad.MediaType)" } }
        if ($ad.AdminStatus) { $props += @{ Name = "Admin Status"; Value = "$($ad.AdminStatus)" } }
    } catch {}
    try {
        $dnsAddrs = @()
        foreach ($d in @(Get-DnsClientServerAddress -InterfaceIndex $ad.InterfaceIndex -ErrorAction SilentlyContinue)) {
            if ($d -and $d.ServerAddresses) { $dnsAddrs += @($d.ServerAddresses | Where-Object { $_ }) }
        }
        if ($dnsAddrs.Count -gt 0) { $props += @{ Name = "DNS Servers"; Value = ($dnsAddrs -join ", ") } }
    } catch {}

    ConvertTo-Json -Compress @{ adapter = $label; properties = @($props) }
} catch {
    ConvertTo-Json -Compress @{ error = $_.Exception.Message }
}
