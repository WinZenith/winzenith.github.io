param([string]$AdapterName = "", [string]$Enable = "true", [int]$InterfaceIndex = 0)

if (-not $AdapterName -and $InterfaceIndex -le 0) {
    ConvertTo-Json -Compress @{ success = $false; message = "AdapterName or InterfaceIndex is required." }
    exit 1
}

if ($InterfaceIndex -le 0 -and $AdapterName -match '[\*\?]') {
    ConvertTo-Json -Compress @{ success = $false; message = "Adapter name must not contain wildcard characters (* or ?)." }
    exit 1
}

try {
    $enableFlag = [bool]::Parse($Enable)
    if ($InterfaceIndex -gt 0) {
        $target = @(Get-NetAdapter -InterfaceIndex $InterfaceIndex -ErrorAction Stop)
    } else {
        $target = @(Get-NetAdapter -ErrorAction Stop | Where-Object { $_.Name -eq $AdapterName })
    }
    if (-not $target -or $target.Count -eq 0) {
        ConvertTo-Json -Compress @{ success = $false; message = "Adapter not found." }
        exit 1
    }
    if ($target.Count -gt 1) {
        ConvertTo-Json -Compress @{ success = $false; message = "Multiple adapters matched; refusing to change state." }
        exit 1
    }
    $one = $target[0]
    if ($enableFlag) {
        $one | Enable-NetAdapter -Confirm:$false -ErrorAction Stop | Out-Null
    } else {
        $one | Disable-NetAdapter -Confirm:$false -ErrorAction Stop | Out-Null
    }
    $verb = if ($enableFlag) { "Enabled" } else { "Disabled" }
    $label = if ($AdapterName) { $AdapterName } else { $one.Name }
    ConvertTo-Json -Compress @{ success = $true; message = "$verb $label." }
} catch {
    ConvertTo-Json -Compress @{ success = $false; message = $_.Exception.Message }
    exit 1
}
