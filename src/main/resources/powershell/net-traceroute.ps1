param([string]$Host = "", [int]$MaxHops = 30)

try {
    if (-not $Host) {
        Write-Output '[]'
        exit 1
    }

    $output = tracert -d -h $MaxHops $Host 2>&1
    $rawOutput = ($output | Out-String).Trim()

    $hops = @()

    foreach ($line in $output) {
        $line = $line.Trim()
        if ($line -match '^\s*(\d+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)') {
            $hops += [PSCustomObject]@{
                hopNumber = [int]$Matches[1]
                address   = $Matches[2]
                latency1  = $Matches[3]
                latency2  = $Matches[4]
                latency3  = $Matches[5]
            }
        } elseif ($line -match '^\s*(\d+)\s+(\S+)\s+(\S+)\s+(\S+)') {
            $hops += [PSCustomObject]@{
                hopNumber = [int]$Matches[1]
                address   = $Matches[2]
                latency1  = $Matches[3]
                latency2  = $Matches[4]
                latency3  = ""
            }
        } elseif ($line -match '^\s*(\d+)\s+(\S+)\s+(\S+)') {
            $hops += [PSCustomObject]@{
                hopNumber = [int]$Matches[1]
                address   = $Matches[2]
                latency1  = $Matches[3]
                latency2  = ""
                latency3  = ""
            }
        } elseif ($line -match '^\s*(\d+)\s+Request timed out') {
            $hops += [PSCustomObject]@{
                hopNumber = [int]$Matches[1]
                address   = "*"
                latency1  = "*"
                latency2  = "*"
                latency3  = "*"
            }
        }
    }

    ConvertTo-Json -Compress $hops
} catch {
    Write-Output '[]'
}
