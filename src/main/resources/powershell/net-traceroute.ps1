param([string]$TargetHost = "", [int]$MaxHops = 30)

try {
    if (-not $TargetHost) {
        Write-Output '[]'
        exit 1
    }

    $output = & tracert -d -h $MaxHops $TargetHost 2>&1
    $hops = @()

    foreach ($line in $output) {
        $t = $line.Trim()
        if (-not $t) { continue }
        # Skip header lines (locale independent: they don't start with number)
        if ($t -notmatch '^\s*\d+\s+') { continue }

        # Timeout line: locale variants "Request timed out." / "Zeitüberschreitung" / "Délai d'attente"
        if ($t -match '^\s*(\d+)\s+(\*|Request timed out|Zeit.*|Délai.*|Request timed)') {
            $hopNum = [int]$Matches[1]
            # Check if line contains latency-like asterisk pattern
            if ($t -match '\*\s+\*\s+\*') {
                $hops += [PSCustomObject]@{ hopNumber = $hopNum; address = "*"; latency1 = "*"; latency2 = "*"; latency3 = "*" }
                continue
            }
        }
        if ($t -match '^\s*(\d+)\s+\*\s+\*\s+\*') {
            $hops += [PSCustomObject]@{ hopNumber = [int]$Matches[1]; address = "*"; latency1 = "*"; latency2 = "*"; latency3 = "*" }
            continue
        }

        # Parse hop with regex extracting latencies and address: e.g. "  1    <1 ms    <1 ms    <1 ms  192.168.1.1"
        # Use flexible whitespace split
        $parts = $t -split '\s{2,}'
        if ($parts.Count -ge 5) {
            $hopStr = $parts[0].Trim()
            if ($hopStr -match '^\d+$') {
                $lat1 = $parts[1].Trim()
                $lat2 = $parts[2].Trim()
                $lat3 = $parts[3].Trim()
                $addr = ($parts[4..($parts.Count - 1)] -join '  ').Trim()
                # Clean address: may be "hostname [IP]" or just IP
                if ($addr -match '\[([^\]]+)\]') { $addr = $Matches[1] }
                $hops += [PSCustomObject]@{ hopNumber = [int]$hopStr; address = $addr; latency1 = $lat1; latency2 = $lat2; latency3 = $lat3 }
            }
        } elseif ($parts.Count -ge 2) {
            $hopStr = $parts[0].Trim()
            if ($hopStr -match '^\d+$') {
                # Fallback: try to extract address as last token that looks like IP/host
                $addr = $parts[$parts.Count - 1].Trim()
                if ($addr -match '\[([^\]]+)\]') { $addr = $Matches[1] }
                $lat1 = if ($parts.Count -gt 1) { $parts[1].Trim() } else { "" }
                $lat2 = if ($parts.Count -gt 2) { $parts[2].Trim() } else { "" }
                $hops += [PSCustomObject]@{ hopNumber = [int]$hopStr; address = $addr; latency1 = $lat1; latency2 = $lat2; latency3 = "" }
            }
        }
    }

    ConvertTo-Json -Compress @($hops) -Depth 3
} catch {
    Write-Output '[]'
}
