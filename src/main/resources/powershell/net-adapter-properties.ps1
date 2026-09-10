param([string]$AdapterName = "")

if (-not $AdapterName) {
    ConvertTo-Json -Compress @{ error = "AdapterName is required" }
    exit 1
}

try {
    # -Name / -InterfaceAlias accept wildcards: escape so "*" matches literally.
    $escapedName = [WildcardPattern]::Escape($AdapterName)
    $props = Get-NetAdapterAdvancedProperty -Name $escapedName -ErrorAction SilentlyContinue |
        Select-Object DisplayName, DisplayValue |
        ForEach-Object { @{ Name = $_.DisplayName; Value = $_.DisplayValue } }
    if (-not $props) { $props = @() }

    # Read-only enrichment: MTU, driver version, link/power hints. No Set-* calls.
    try {
        $ipIf = Get-NetIPInterface -InterfaceAlias $escapedName -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($ipIf -and $ipIf.NlMtu) { $props += @{ Name = "MTU (IPv4)"; Value = "$($ipIf.NlMtu)" } }
    } catch {}
    try {
        $ad = Get-NetAdapter -Name $escapedName -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($ad) {
            if ($ad.DriverVersion) { $props += @{ Name = "Driver Version"; Value = "$($ad.DriverVersion)" } }
            if ($ad.DriverDate) { $props += @{ Name = "Driver Date"; Value = "$($ad.DriverDate)" } }
            if ($ad.MediaType) { $props += @{ Name = "Media Type"; Value = "$($ad.MediaType)" } }
            if ($ad.AdminStatus) { $props += @{ Name = "Admin Status"; Value = "$($ad.AdminStatus)" } }
        }
    } catch {}
    try {
        # All address families (IPv4 + IPv6) so applied IPv6 DNS is visible.
        $dnsAddrs = @()
        foreach ($d in @(Get-DnsClientServerAddress -InterfaceAlias $escapedName -ErrorAction SilentlyContinue)) {
            if ($d -and $d.ServerAddresses) { $dnsAddrs += @($d.ServerAddresses | Where-Object { $_ }) }
        }
        if ($dnsAddrs.Count -gt 0) { $props += @{ Name = "DNS Servers"; Value = ($dnsAddrs -join ", ") } }
    } catch {}

    ConvertTo-Json -Compress @{ adapter = $AdapterName; properties = @($props) }
} catch {
    ConvertTo-Json -Compress @{ error = $_.Exception.Message }
}
