param([string]$Server = "")
# Read-only DNS latency probe: Resolve-DnsName timing for google.com (3 samples).
# No system settings are changed.
$target = "google.com"
$samples = @()
$note = ""
try {
    for ($i = 0; $i -lt 3; $i++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            if ($Server -and $Server.Trim() -ne "") {
                $r = Resolve-DnsName -Name $target -Server $Server.Trim() -ErrorAction Stop -DnsOnly
            } else {
                $r = Resolve-DnsName -Name $target -ErrorAction Stop -DnsOnly
            }
            $sw.Stop()
            $samples += $sw.Elapsed.TotalMilliseconds
        } catch {
            $sw.Stop()
            $note = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 150
    }
    if ($samples.Count -gt 0) {
        $avg = [math]::Round(($samples | Measure-Object -Average).Average, 1)
        $min = [math]::Round(($samples | Measure-Object -Minimum).Minimum, 1)
        $max = [math]::Round(($samples | Measure-Object -Maximum).Maximum, 1)
        ConvertTo-Json -Compress @{ success = $true; avgMs = $avg; minMs = $min; maxMs = $max; note = "" }
    } else {
        ConvertTo-Json -Compress @{ success = $false; avgMs = -1; minMs = -1; maxMs = -1; note = $note }
    }
} catch {
    ConvertTo-Json -Compress @{ success = $false; avgMs = -1; minMs = -1; maxMs = -1; note = $_.Exception.Message }
}
