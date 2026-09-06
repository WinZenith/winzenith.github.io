# Read-only snapshot of registry-based TCP tuning values.
# No Set-ItemProperty / netsh set calls here — safe to run without admin.
$regPath = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters"
$ack = $null
$noDelay = $null
try {
    $props = Get-ItemProperty -Path $regPath -ErrorAction SilentlyContinue
    if ($null -ne $props) {
        if ($props.PSObject.Properties.Name -contains "TcpAckFrequency") { $ack = $props.TcpAckFrequency }
        if ($props.PSObject.Properties.Name -contains "TCPNoDelay") { $noDelay = $props.TCPNoDelay }
    }
} catch { }

$out = @{
    TcpAckFrequency = if ($null -eq $ack) { $null } else { "$ack" }
    TCPNoDelay = if ($null -eq $noDelay) { $null } else { "$noDelay" }
}
ConvertTo-Json -Compress $out
