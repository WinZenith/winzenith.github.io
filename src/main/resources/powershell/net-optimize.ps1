param([string]$Preset = "Default")

$results = @()
$failed = $false

function Add-Result($key, $value, $ok) {
    $script:results += [PSCustomObject]@{ Key = $key; Value = $value; Success = $ok }
    if (-not $ok) { $script:failed = $true }
}

function Invoke-Netsh {
    param([string]$Cmd)
    Invoke-Expression $Cmd 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        return $false
    }
    return $true
}

switch ($Preset) {
    "MaxPerformance" {
        $ok = Invoke-Netsh "netsh int tcp set global autotuninglevel=normal"
        Add-Result "TCP AutoTuning" "normal" $ok

        $ok = Invoke-Netsh "netsh int tcp set global rss=enabled"
        Add-Result "RSS" "enabled" $ok

        $ok = Invoke-Netsh "netsh int tcp set global rsc=enabled"
        Add-Result "RSC" "enabled" $ok

        $ok = Invoke-Netsh "netsh int tcp set global ecncapability=disabled"
        Add-Result "ECN" "disabled" $ok

        $regPath = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters"
        Remove-ItemProperty -Path $regPath -Name "TcpAckFrequency" -ErrorAction SilentlyContinue
        Remove-ItemProperty -Path $regPath -Name "TCPNoDelay" -ErrorAction SilentlyContinue
        Add-Result "TCP Ack Frequency" "removed (registry default)" $true
        Add-Result "TCP No Delay" "removed (registry default)" $true
        break
    }
    "MaxStability" {
        $ok = Invoke-Netsh "netsh int tcp set global autotuninglevel=disabled"
        Add-Result "TCP AutoTuning" "disabled" $ok

        $ok = Invoke-Netsh "netsh int tcp set global ecncapability=enabled"
        Add-Result "ECN" "enabled" $ok

        $ok = Invoke-Netsh "netsh int tcp set global rss=enabled"
        Add-Result "RSS" "enabled" $ok

        $regPath = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters"
        Remove-ItemProperty -Path $regPath -Name "TcpAckFrequency" -ErrorAction SilentlyContinue
        Remove-ItemProperty -Path $regPath -Name "TCPNoDelay" -ErrorAction SilentlyContinue
        Add-Result "TCP Ack Frequency" "removed (registry default)" $true
        Add-Result "TCP No Delay" "removed (registry default)" $true
        break
    }
    "Gaming" {
        $ok = Invoke-Netsh "netsh int tcp set global autotuninglevel=disabled"
        Add-Result "TCP AutoTuning" "disabled" $ok

        $ok = Invoke-Netsh "netsh int tcp set global rss=enabled"
        Add-Result "RSS" "enabled" $ok

        $ok = Invoke-Netsh "netsh int tcp set global ecncapability=disabled"
        Add-Result "ECN" "disabled" $ok

        $regPath = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters"
        Set-ItemProperty -Path $regPath -Name "TcpAckFrequency" -Value 1 -Type DWord -ErrorAction SilentlyContinue
        Set-ItemProperty -Path $regPath -Name "TCPNoDelay" -Value 1 -Type DWord -ErrorAction SilentlyContinue
        Add-Result "TCP Ack Frequency" "1 (set via registry)" $true
        Add-Result "TCP No Delay" "1 (set via registry)" $true
        break
    }
    default {
        $ok = Invoke-Netsh "netsh int tcp set global autotuninglevel=normal"
        Add-Result "TCP AutoTuning" "normal" $ok

        $ok = Invoke-Netsh "netsh int tcp set global rss=default"
        Add-Result "RSS" "default" $ok

        $ok = Invoke-Netsh "netsh int tcp set global ecncapability=default"
        Add-Result "ECN" "default" $ok

        $ok = Invoke-Netsh "netsh int tcp set global rsc=default"
        Add-Result "RSC" "default" $ok

        $regPath = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters"
        Remove-ItemProperty -Path $regPath -Name "TcpAckFrequency" -ErrorAction SilentlyContinue
        Remove-ItemProperty -Path $regPath -Name "TCPNoDelay" -ErrorAction SilentlyContinue
        Add-Result "TCP Ack Frequency" "removed (registry default)" $true
        Add-Result "TCP No Delay" "removed (registry default)" $true
        break
    }
}

ConvertTo-Json -Compress $results

if ($failed) {
    exit 1
}
