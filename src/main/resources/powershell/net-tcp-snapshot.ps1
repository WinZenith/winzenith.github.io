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

# ponytail: ordinal parse of `netsh int tcp show global` colon-lines; reorder if a future Windows build changes output
$autoTuning = $null
$rss = $null
$rsc = $null
$ecn = $null
try {
    $raw = @(netsh int tcp show global 2>$null)
    $vals = @()
    foreach ($line in $raw) {
        if ($line -match ':\s*(.+)$') { $vals += $Matches[1].Trim() }
    }
    if ($vals.Count -ge 1) { $rss = $vals[0] }
    if ($vals.Count -ge 2) { $autoTuning = $vals[1] }
    if ($vals.Count -ge 4) { $ecn = $vals[3] }
    if ($vals.Count -ge 7) { $rsc = $vals[6] }
} catch { }

$out = @{
    TcpAckFrequency = if ($null -eq $ack) { $null } else { "$ack" }
    TCPNoDelay = if ($null -eq $noDelay) { $null } else { "$noDelay" }
    AutoTuning = $autoTuning
    RSS = $rss
    RSC = $rsc
    ECN = $ecn
}
ConvertTo-Json -Compress $out
