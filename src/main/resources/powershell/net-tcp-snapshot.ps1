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

$netshGlobalRaw = @(netsh int tcp show global 2>$null)

function Get-NetshGlobalValue {
    param([string[]]$LabelPatterns)
    try {
        foreach ($line in $netshGlobalRaw) {
            if ($null -eq $line) { continue }
            $t = [string]$line
            foreach ($pat in $LabelPatterns) {
                if ($t -match $pat) {
                    $idx = $t.IndexOf(':')
                    if ($idx -ge 0 -and $idx -lt ($t.Length - 1)) {
                        return $t.Substring($idx + 1).Trim()
                    }
                }
            }
        }
    } catch { }
    return $null
}

$ack = Read-InterfaceDword -Name "TcpAckFrequency"
$noDelay = Read-InterfaceDword -Name "TCPNoDelay"

# Label patterns: English + common localized netsh captions (value tokens stay ASCII).
$autoTuning = Get-NetshGlobalValue @(
    '(?i)Auto-?Tuning',
    '(?i)Automatische.*Abstimmung',
    '(?i)Ajustement automatique',
    '(?i)Ajuste automatico',
    '(?i)Automatyczne dostrajanie'
)
$rss = Get-NetshGlobalValue @(
    '(?i)Receive-?Side Scaling',
    '(?i)Empfangsseitige Skalierung',
    '(?i)Mise a l''echelle cote reception',
    '(?i)Escalado en el lado de recepcion'
)
$rsc = Get-NetshGlobalValue @(
    '(?i)Receive Segment Coalescing',
    '(?i)Empfangssegmentzusammenfuhrung',
    '(?i)Regroupement de segments'
)
$ecn = Get-NetshGlobalValue @(
    '(?i)ECN Capability',
    '(?i)ECN-?Fahigkeit',
    '(?i)Capacite ECN',
    '(?i)Capacidad ECN'
)

# ponytail: if captions change, ordinal fallback on colon lines (RSS, RSC, Auto-Tuning, ECN order on Win10+).
if ($null -eq $rss -or $null -eq $autoTuning) {
    $valueLines = @($netshGlobalRaw | Where-Object { $_ -match ':\s*\S' })
    if ($valueLines.Count -ge 4) {
        if ($null -eq $rss) { $rss = ($valueLines[0] -split ':', 2)[1].Trim() }
        if ($null -eq $rsc) { $rsc = ($valueLines[1] -split ':', 2)[1].Trim() }
        if ($null -eq $autoTuning) { $autoTuning = ($valueLines[2] -split ':', 2)[1].Trim() }
        if ($null -eq $ecn) { $ecn = ($valueLines[3] -split ':', 2)[1].Trim() }
    }
}

$out = @{
    TcpAckFrequency = if ($null -eq $ack) { $null } else { "$ack" }
    TCPNoDelay = if ($null -eq $noDelay) { $null } else { "$noDelay" }
    AutoTuning = $autoTuning
    RSS = $rss
    RSC = $rsc
    ECN = $ecn
}
ConvertTo-Json -Compress $out
