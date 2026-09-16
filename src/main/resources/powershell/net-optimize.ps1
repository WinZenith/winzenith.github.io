param([string]$Preset = "Default")

$results = @()
$failed = $false
$tcpipParams = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters"
$tcpipIfRoot = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces"

function Add-Result($key, $value, $ok) {
    $script:results += [PSCustomObject]@{ Key = $key; Value = $value; Success = $ok }
    if (-not $ok) { $script:failed = $true }
}

function Invoke-Netsh {
    param([string[]]$Args)
    $out = & netsh @Args 2>&1
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 1 }
    return $code -eq 0
}

function Invoke-RegistryRemove {
    param([string]$Path, [string]$Name)
    try {
        Remove-ItemProperty -Path $Path -Name $Name -ErrorAction Stop | Out-Null
        return $true
    } catch {
        if ($_.Exception.Message -like "*does not exist*" -or $_.Exception.Message -like "*Property*not found*") {
            return $true
        }
        return $false
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

function Get-TcpInterfacePaths {
    @(Get-ChildItem -Path $tcpipIfRoot -ErrorAction SilentlyContinue | ForEach-Object { $_.PSPath })
}

# TcpAckFrequency / TCPNoDelay are per-interface (Interfaces\{GUID}), not global Parameters.
function Set-InterfaceDword {
    param([string]$Name, [int]$Value)
    $paths = Get-TcpInterfacePaths
    if ($paths.Count -eq 0) { return $false }
    $ok = $true
    foreach ($p in $paths) {
        if (-not (Invoke-RegistrySet -Path $p -Name $Name -Value $Value)) { $ok = $false }
    }
    return $ok
}

function Remove-AckNoDelayKey {
    param([string]$Name)
    $ok = Invoke-RegistryRemove -Path $tcpipParams -Name $Name
    foreach ($p in Get-TcpInterfacePaths) {
        if (-not (Invoke-RegistryRemove -Path $p -Name $Name)) { $ok = $false }
    }
    return $ok
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

        $ok1 = Remove-AckNoDelayKey -Name "TcpAckFrequency"
        $ok2 = Remove-AckNoDelayKey -Name "TCPNoDelay"
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

        $ok1 = Remove-AckNoDelayKey -Name "TcpAckFrequency"
        $ok2 = Remove-AckNoDelayKey -Name "TCPNoDelay"
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

        # Drop stale global Parameters copies so snapshot/preview do not read a no-op location.
        Invoke-RegistryRemove -Path $tcpipParams -Name "TcpAckFrequency" | Out-Null
        Invoke-RegistryRemove -Path $tcpipParams -Name "TCPNoDelay" | Out-Null
        $ok1 = Set-InterfaceDword -Name "TcpAckFrequency" -Value 1
        $ok2 = Set-InterfaceDword -Name "TCPNoDelay" -Value 1
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

        $ok1 = Remove-AckNoDelayKey -Name "TcpAckFrequency"
        $ok2 = Remove-AckNoDelayKey -Name "TCPNoDelay"
        Add-Result "TCP Ack Frequency" "removed (registry default)" $ok1
        Add-Result "TCP No Delay" "removed (registry default)" $ok2
        break
    }
}

ConvertTo-Json -Compress $results

if ($failed) {
    exit 1
}
