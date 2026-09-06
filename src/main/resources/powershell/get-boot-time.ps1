try {
    $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
    $boot = $os.LastBootUpTime
    if ($null -eq $boot) { throw "LastBootUpTime unavailable" }
    # Emit ISO-8601 so Java can parse without locale issues
    $iso = $boot.ToUniversalTime().ToString("o")
    @{ BootTime = $iso } | ConvertTo-Json -Depth 2
} catch {
    @{ BootTime = $null; Error = $_.Exception.Message } | ConvertTo-Json -Depth 2
}
