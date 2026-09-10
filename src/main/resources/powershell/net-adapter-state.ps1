param([string]$AdapterName = "", [string]$Enable = "true")

if (-not $AdapterName) {
    ConvertTo-Json -Compress @{ success = $false; message = "AdapterName is required." }
    exit 1
}

try {
    # String + Parse (repo convention, cf. browser-extensions.ps1): -File argv
    # arrives as text, and [bool] params do not bind reliably from the CLI.
    $enableFlag = [bool]::Parse($Enable)
    # Exact-match lookup only. Never pass the raw name to -Name / -InterfaceAlias:
    # both parameters accept wildcards, so an adapter literally named "*" would
    # otherwise match (and disable) every adapter on the system.
    # "-eq" is an exact, non-wildcard comparison; piping binds by InputObject
    # so no name string is ever re-parsed as a wildcard pattern.
    $target = Get-NetAdapter -ErrorAction Stop | Where-Object { $_.Name -eq $AdapterName }
    if (-not $target) {
        ConvertTo-Json -Compress @{ success = $false; message = "Adapter not found: $AdapterName" }
        exit 1
    }
    if (@($target).Count -gt 1) {
        ConvertTo-Json -Compress @{ success = $false; message = "Multiple adapters matched; refusing to change state." }
        exit 1
    }
    if ($enableFlag) {
        $target | Enable-NetAdapter -Confirm:$false -ErrorAction Stop | Out-Null
    } else {
        $target | Disable-NetAdapter -Confirm:$false -ErrorAction Stop | Out-Null
    }
    $verb = if ($enableFlag) { "Enabled" } else { "Disabled" }
    ConvertTo-Json -Compress @{ success = $true; message = "$verb $AdapterName." }
} catch {
    ConvertTo-Json -Compress @{ success = $false; message = $_.Exception.Message }
    exit 1
}
