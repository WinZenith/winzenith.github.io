param([string]$AdapterName = "", [int]$InterfaceIndex = 0)

if (-not $AdapterName -and $InterfaceIndex -le 0) {
    ConvertTo-Json -Compress @{ success = $false; message = "AdapterName or InterfaceIndex is required." }
    exit 1
}
if ($InterfaceIndex -le 0 -and $AdapterName -match '[\*\?]') {
    ConvertTo-Json -Compress @{ success = $false; message = "Adapter name must not contain wildcard characters (* or ?)." }
    exit 1
}

try {
    if ($InterfaceIndex -gt 0) {
        $target = Get-NetAdapter -InterfaceIndex $InterfaceIndex -ErrorAction Stop
    } else {
        $target = Get-NetAdapter -ErrorAction Stop | Where-Object { $_.Name -eq $AdapterName }
    }
    if (-not $target) {
        ConvertTo-Json -Compress @{ success = $false; message = "Adapter not found." }
        exit 1
    }
    if (@($target).Count -gt 1) {
        ConvertTo-Json -Compress @{ success = $false; message = "Multiple adapters matched; refusing to renew." }
        exit 1
    }
    $exact = [string]$target.Name
    $idx = [int]$target.InterfaceIndex
    $renewOut = ""
    $ok = $false
    if ($exact -match '[\*\?]' -or $InterfaceIndex -gt 0) {
        # ipconfig treats * and ? as wildcards — renew by WMI/CIM interface index instead.
        $cfg = Get-CimInstance -ClassName Win32_NetworkAdapterConfiguration -Filter "InterfaceIndex=$idx" -ErrorAction Stop
        if (-not $cfg) {
            ConvertTo-Json -Compress @{ success = $false; message = "No IP configuration for interface index $idx." }
            exit 1
        }
        $result = Invoke-CimMethod -InputObject $cfg -MethodName RenewDHCPLease -ErrorAction Stop
        $ok = ($null -ne $result) -and ($result.ReturnValue -eq 0)
        $renewOut = "RenewDHCPLease ReturnValue=$($result.ReturnValue)"
    } else {
        $renewOut = & ipconfig /renew $exact 2>&1
        $ok = $LASTEXITCODE -eq 0
    }
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
