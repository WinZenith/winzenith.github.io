param([string]$Host = "", [int]$Port = 80)

try {
    if (-not $Host) {
        Write-Output '{"error": "Host parameter is required"}'
        exit 1
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $result = Test-NetConnection -ComputerName $Host -Port $Port -WarningAction SilentlyContinue 2>&1
    $sw.Stop()

    $tcpTest = $false
    $latency = 0

    if ($result -is [Microsoft.PowerShell.Commands.TestNetConnectionCommand]) {
        $tcpTest = $result.TcpTestSucceeded
        if ($result.PingReplyDetails) {
            $latency = $result.PingReplyDetails.RoundtripTime
        }
    } else {
        $outputStr = ($result | Out-String)
        if ($outputStr -match 'TcpTestSucceeded\s*:\s*(True|False)') {
            $tcpTest = $Matches[1] -eq 'True'
        }
    }

    $latencyMs = $sw.ElapsedMilliseconds

    $output = @{
        host            = $Host
        port            = $Port
        open            = $tcpTest
        latencyMs       = $latencyMs
        rawOutput       = ($result | Out-String).Trim()
    }

    ConvertTo-Json -Compress $output
} catch {
    Write-Output ('{"error": "' + $_.Exception.Message.Replace('"', '""') + '"}')
}
