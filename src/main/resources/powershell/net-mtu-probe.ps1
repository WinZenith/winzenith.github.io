param([string]$TargetHost = "")
# Read-only MTU discovery via ping -f -l sweep (no system changes).
if (-not $TargetHost) {
    ConvertTo-Json -Compress @{ success = $false; optimalMtu = -1; details = "TargetHost is required." }
    exit 0
}
$sizes = @(1472, 1464, 1452, 1400, 1300, 1200, 1000)
$best = -1
$logLines = @()
foreach ($s in $sizes) {
    try {
        $out = ping -f -n 1 -l $s $TargetHost 2>&1 | Out-String
        $logLines += "--- ping -f -l $s $TargetHost ---"
        $logLines += $out.Trim()
        if ($out -match "Reply from") {
            $best = $s + 28
            break
        }
    } catch {
        $logLines += ("size " + $s + ": error " + $_.Exception.Message)
    }
}
if ($best -gt 0) {
    $payload = $best - 28
    $tail = "Optimal MTU estimate: $best (payload $payload + 28 IP/ICMP header). Suggestion only - no changes applied. Set MTU manually in adapter advanced properties if needed."
    $details = (($logLines -join "`n") + "`n`n" + $tail)
    ConvertTo-Json -Compress @{ success = $true; optimalMtu = $best; details = $details }
} else {
    $tail2 = "No reply with DF set. Check host/firewall and try the gateway IP instead."
    $details2 = (($logLines -join "`n") + "`n`n" + $tail2)
    ConvertTo-Json -Compress @{ success = $false; optimalMtu = -1; details = $details2 }
}
