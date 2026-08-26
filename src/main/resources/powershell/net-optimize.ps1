param([string]$Preset = "Default")

$results = @()
$failed = $false

function Add-Result($key, $value, $ok) {
    $script:results += [PSCustomObject]@{ Key = $key; Value = $value; Success = $ok }
    if (-not $ok) { $script:failed = $true }
}

function Invoke-Netsh {
    param([string[]]$Args)
    & netsh @Args 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        return $false
    }
    return $true
}

function Invoke-RegistryRemove {
    param([string]$Path, [string]$Name)
    try {
        Remove-ItemProperty -Path $Path -Name $Name -ErrorAction Stop | Out-Null
        return $true
    } catch {
        # If property doesn't exist, treat as success (already default)
        if ($_.Exception.Message -like "*does not exist*" -or $_.Exception.Message -like "*Property*not found*") {
            return $true
        }
        # Access denied or other error is real failure
        if ($_.Exception.Message -like "*Access*denied*" -or $_.Exception.Message -like "*Unauthorized*") {
            return $false
        }
        # For other errors (e.g., path exists but property missing), consider success
        return $true
    }
}

function Invoke-RegistrySet {
    param([string]$Path, [string]$Name, [int]$Value)
    try {
        if (-not (Test-Path $Path)) { New-Item -Path $Path -Force | Out-Null }
        Set-ItemProperty -Path $Path -Name $Name -Value $Value -Type DWord -ErrorAction Stop
        return $true
    } catch {
        return $false
    }
}

switch ($Preset) {
    "MaxPerformance" {
        $ok = Invoke-Netsh @("int","tcp","set","global","autotuninglevel=normal")
        Add-Result "TCP AutoTuning" "normal" $ok

        $ok = Invoke-Netsh @("int","tcp","set","global","rss=enabled")
        Add-Result "RSS" "enabled" $ok

        $ok = Invoke-Netsh @("int","tcp","set","global","rsc=enabled")
        Add-Result "RSC" "enabled" $ok

        $ok = Invoke-Netsh @("int","tcp","set","global","ecncapability=disabled")
        Add-Result "ECN" "disabled" $ok

        $regPath = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters"
        $ok1 = Invoke-RegistryRemove -Path $regPath -Name "TcpAckFrequency"
        $ok2 = Invoke-RegistryRemove -Path $regPath -Name "TCPNoDelay"
        Add-Result "TCP Ack Frequency" "removed (registry default)" $ok1
        Add-Result "TCP No Delay" "removed (registry default)" $ok2
        break
    }
    "MaxStability" {
        $ok = Invoke-Netsh @("int","tcp","set","global","autotuninglevel=disabled")
        Add-Result "TCP AutoTuning" "disabled" $ok

        $ok = Invoke-Netsh @("int","tcp","set","global","ecncapability=enabled")
        Add-Result "ECN" "enabled" $ok

        $ok = Invoke-Netsh @("int","tcp","set","global","rss=enabled")
        Add-Result "RSS" "enabled" $ok

        $regPath = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters"
        $ok1 = Invoke-RegistryRemove -Path $regPath -Name "TcpAckFrequency"
        $ok2 = Invoke-RegistryRemove -Path $regPath -Name "TCPNoDelay"
        Add-Result "TCP Ack Frequency" "removed (registry default)" $ok1
        Add-Result "TCP No Delay" "removed (registry default)" $ok2
        break
    }
    "Gaming" {
        $ok = Invoke-Netsh @("int","tcp","set","global","autotuninglevel=disabled")
        Add-Result "TCP AutoTuning" "disabled" $ok

        $ok = Invoke-Netsh @("int","tcp","set","global","rss=enabled")
        Add-Result "RSS" "enabled" $ok

        $ok = Invoke-Netsh @("int","tcp","set","global","ecncapability=disabled")
        Add-Result "ECN" "disabled" $ok

        $regPath = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters"
        $ok1 = Invoke-RegistrySet -Path $regPath -Name "TcpAckFrequency" -Value 1
        $ok2 = Invoke-RegistrySet -Path $regPath -Name "TCPNoDelay" -Value 1
        Add-Result "TCP Ack Frequency" "1 (set via registry)" $ok1
        Add-Result "TCP No Delay" "1 (set via registry)" $ok2
        break
    }
    default {
        $ok = Invoke-Netsh @("int","tcp","set","global","autotuninglevel=normal")
        Add-Result "TCP AutoTuning" "normal" $ok

        $ok = Invoke-Netsh @("int","tcp","set","global","rss=default")
        Add-Result "RSS" "default" $ok

        $ok = Invoke-Netsh @("int","tcp","set","global","ecncapability=default")
        Add-Result "ECN" "default" $ok

        $ok = Invoke-Netsh @("int","tcp","set","global","rsc=default")
        Add-Result "RSC" "default" $ok

        $regPath = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters"
        $ok1 = Invoke-RegistryRemove -Path $regPath -Name "TcpAckFrequency"
        $ok2 = Invoke-RegistryRemove -Path $regPath -Name "TCPNoDelay"
        Add-Result "TCP Ack Frequency" "removed (registry default)" $ok1
        Add-Result "TCP No Delay" "removed (registry default)" $ok2
        break
    }
}

ConvertTo-Json -Compress $results

if ($failed) {
    exit 1
}
