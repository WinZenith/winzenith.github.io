param([string]$AdapterName = "")

if (-not $AdapterName) {
    ConvertTo-Json -Compress @{ success = $false; message = "AdapterName is required." }
    exit 1
}
if ($AdapterName -match '[\*\?]') {
    ConvertTo-Json -Compress @{ success = $false; message = "Adapter name must not contain wildcard characters (* or ?)." }
    exit 1
}

try {
    # Exact-match only. Never pass the raw name to ipconfig until -eq confirms it:
    # ipconfig treats * and ? as wildcards and would release/renew every match.
    $target = Get-NetAdapter -ErrorAction Stop | Where-Object { $_.Name -eq $AdapterName }
    if (-not $target) {
        ConvertTo-Json -Compress @{ success = $false; message = "Adapter not found: $AdapterName" }
        exit 1
    }
    if (@($target).Count -gt 1) {
        ConvertTo-Json -Compress @{ success = $false; message = "Multiple adapters matched; refusing to renew." }
        exit 1
    }
    $exact = [string]$target.Name
    if ($exact -match '[\*\?]') {
        ConvertTo-Json -Compress @{ success = $false; message = "Adapter name must not contain wildcard characters (* or ?)." }
        exit 1
    }
    # Renew only — do not /release first. /release drops the lease and leaves the
    # NIC without an address if /renew then fails or times out.
    $renewOut = & ipconfig /renew $exact 2>&1
    $ok = $LASTEXITCODE -eq 0
    $msg = if ($ok) { "IP address renewed for $exact." } else { "IP renewal failed for $exact." }
    ConvertTo-Json -Compress @{
        success = $ok
        message = $msg
        adapterName = $exact
        detail = (($renewOut | Out-String).Trim())
    }
    if (-not $ok) { exit 1 }
} catch {
    ConvertTo-Json -Compress @{ success = $false; message = $_.Exception.Message }
    exit 1
}
