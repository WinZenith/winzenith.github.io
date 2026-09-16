# Read-only snapshot of registry-based TCP tuning values.
# No Set-ItemProperty / netsh set calls here — safe to run without admin.
# TcpAckFrequency / TCPNoDelay are per-interface (Interfaces\{GUID}).
$ifRoot = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces"

function Read-InterfaceDword {
    param([string]$Name)
    $vals = @()
    foreach ($p in @(Get-ChildItem -Path $ifRoot -ErrorAction SilentlyContinue)) {
        try {
            $props = Get-ItemProperty -Path $p.PSPath -ErrorAction SilentlyContinue
            if ($null -ne $props -and $props.PSObject.Properties.Name -contains $Name) {
                $vals += [string]$props.$Name
            }
        } catch { }
    }
    if ($vals.Count -eq 0) { return $null }
    $uniq = @($vals | Select-Object -Unique)
    if ($uniq.Count -eq 1) { return $uniq[0] }
    return "mixed"
}

$ack = Read-InterfaceDword -Name "TcpAckFrequency"
$noDelay = Read-InterfaceDword -Name "TCPNoDelay"

# Match by setting name — ordinal colon-value index breaks when Windows reorders
# or localizes `netsh int tcp show global`. English labels used by Preview.
$autoTuning = $null
$rss = $null
$rsc = $null
$ecn = $null
try {
    $raw = @(netsh int tcp show global 2>$null)
    foreach ($line in $raw) {
        if ($null -eq $line) { continue }
        $t = [string]$line
        if ($t -match '(?i)Receive-Side Scaling[^:]*:\s*(.+)$') { $rss = $Matches[1].Trim(); continue }
        if ($t -match '(?i)Auto-Tuning[^:]*:\s*(.+)$') { $autoTuning = $Matches[1].Trim(); continue }
        if ($t -match '(?i)ECN Capability[^:]*:\s*(.+)$') { $ecn = $Matches[1].Trim(); continue }
        if ($t -match '(?i)Receive Segment Coalescing[^:]*:\s*(.+)$') { $rsc = $Matches[1].Trim(); continue }
    }
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
