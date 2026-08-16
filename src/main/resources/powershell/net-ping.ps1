param([string]$TargetHost = "", [int]$Count = 4)

try {
    if (-not $TargetHost) {
        Write-Output '{"error": "Host parameter is required"}'
        exit 1
    }

    $output = ping -n $Count $TargetHost 2>&1
    $rawOutput = ($output | Out-String).Trim()

    $sent = $Count
    $received = 0
    $lossPct = 100
    $minMs = 0.0
    $maxMs = 0.0
    $avgMs = 0.0

    $received = ([regex]::Matches($rawOutput, 'Reply from')).Count

    if ($sent -gt 0) {
        $lossPct = [math]::Round((($sent - $received) / $sent) * 100)
    }

    $times = [regex]::Matches($rawOutput, 'time[<=](\d+)ms')
    if ($times.Count -gt 0) {
        $msValues = $times | ForEach-Object { [double]$_.Groups[1].Value }
        $minMs = ($msValues | Measure-Object -Minimum).Minimum
        $maxMs = ($msValues | Measure-Object -Maximum).Maximum
        $avgMs = [math]::Round(($msValues | Measure-Object -Average).Average, 2)
    }

    $result = @{
        host             = $TargetHost
        packetsSent      = $sent
        packetsReceived  = $received
        packetLossPercent = $lossPct
        minMs            = $minMs
        maxMs            = $maxMs
        avgMs            = $avgMs
        rawOutput        = $rawOutput
    }

    ConvertTo-Json -Compress $result
} catch {
    Write-Output ('{"error": "' + $_.Exception.Message.Replace('"', '""') + '"}')
}
