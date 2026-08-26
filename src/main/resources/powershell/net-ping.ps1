param([string]$TargetHost = "", [int]$Count = 4)

try {
    if (-not $TargetHost) {
        ConvertTo-Json -Compress @{ error = "Host parameter is required" }
        exit 1
    }

    $output = & ping -n $Count $TargetHost 2>&1
    $rawOutput = ($output | Out-String).Trim()

    $sent = $Count
    $received = 0
    $lossPct = 100
    $minMs = 0.0
    $maxMs = 0.0
    $avgMs = 0.0

    # Locale-independent: count successful replies via packet statistics line or "TTL=" / "time" patterns
    # English "Reply from", German "Antwort von", French "Reponse de", etc. - use TTL as universal marker
    $received = ([regex]::Matches($rawOutput, 'TTL=')).Count
    if ($received -eq 0) {
        # Fallback to "Reply from" / localized patterns including time
        $received = ([regex]::Matches($rawOutput, 'time[<=]\s*\d+ms')).Count
    }
    # Also try to parse the standard summary line: "Received = X" / "Empfangen = X"
    $receivedMatch = [regex]::Match($rawOutput, 'Received\s*=\s*(\d+)|Empfangen\s*=\s*(\d+)|Re(?:çu|ceived)\s*=\s*(\d+)')
    if ($receivedMatch.Success) {
        foreach ($g in $receivedMatch.Groups) {
            if ($g.Success -and $g.Value -match '^\d+$') {
                try { $received = [int]$g.Value; break } catch {}
            }
        }
    }

    if ($sent -gt 0) {
        $lossPct = [math]::Round((($sent - $received) / $sent) * 100)
        if ($lossPct -lt 0) { $lossPct = 0 }
        if ($lossPct -gt 100) { $lossPct = 100 }
    }

    $times = [regex]::Matches($rawOutput, 'time[<=]\s*(\d+)ms')
    if ($times.Count -gt 0) {
        $msValues = $times | ForEach-Object { [double]$_.Groups[1].Value }
        $minMs = ($msValues | Measure-Object -Minimum).Minimum
        $maxMs = ($msValues | Measure-Object -Maximum).Maximum
        $avgMs = [math]::Round(($msValues | Measure-Object -Average).Average, 2)
    } else {
        # Try to parse summary line: Average = Xm or Minimum/Maximum/Mittelwert
        $avgMatch = [regex]::Match($rawOutput, 'Average\s*=\s*(\d+)|Mittelwert\s*=\s*(\d+)|Moyenne\s*=\s*(\d+)')
        if ($avgMatch.Success) {
            foreach ($g in $avgMatch.Groups) {
                if ($g.Success -and $g.Value -match '^\d+$') { $avgMs = [double]$g.Value; break }
            }
        }
    }

    $result = @{
        host               = $TargetHost
        packetsSent        = $sent
        packetsReceived    = $received
        packetLossPercent  = $lossPct
        minMs              = $minMs
        maxMs              = $maxMs
        avgMs              = $avgMs
        rawOutput          = $rawOutput
    }

    ConvertTo-Json -Compress $result -Depth 3
} catch {
    ConvertTo-Json -Compress @{ error = $_.Exception.Message }
}
