param([string]$TargetHost = "", [int]$MaxHops = 30)

try {
    if (-not $TargetHost) {
        Write-Output '[]'
        exit 1
    }

    $output = tracert -d -h $MaxHops $TargetHost 2>&1
    $rawOutput = ($output | Out-String).Trim()

    $hops = @()

    foreach ($line in $output) {
        $line = $line.Trim()
        if ($line -match '^\s*(\d+)\s+Request timed out') {
            $hops += [PSCustomObject]@{
                hopNumber = [int]$Matches[1]
                address   = "*"
                latency1  = "*"
                latency2  = "*"
                latency3  = "*"
            }
            continue
        }
        $parts = $line -split '\s{2,}'
        if ($parts.Count -ge 5) {
            $hopStr = $parts[0].Trim()
            if ($hopStr -match '^\d+$') {
                $lat1 = $parts[1].Trim()
                $lat2 = $parts[2].Trim()
                $lat3 = $parts[3].Trim()
                $addr = ($parts[4..($parts.Count - 1)] -join '  ').Trim()
                $hops += [PSCustomObject]@{
                    hopNumber = [int]$hopStr
                    address   = $addr
                    latency1  = $lat1
                    latency2  = $lat2
                    latency3  = $lat3
                }
            }
        } elseif ($parts.Count -ge 3) {
            $hopStr = $parts[0].Trim()
            if ($hopStr -match '^\d+$') {
                $hops += [PSCustomObject]@{
                    hopNumber = [int]$hopStr
                    address   = $parts[1].Trim()
                    latency1  = $parts[2].Trim()
                    latency2  = ""
                    latency3  = ""
                }
            }
        }
    }

    ConvertTo-Json -Compress $hops
} catch {
    Write-Output '[]'
}
