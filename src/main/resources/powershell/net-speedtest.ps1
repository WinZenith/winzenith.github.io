param()

try {
    $downloadMbps = 0.0
    $uploadMbps = 0.0
    $latencyMs = 0
    $serverInfo = ""

    $speedtestExe = Get-Command speedtest -ErrorAction SilentlyContinue
    if (-not $speedtestExe) {
        $speedtestExe = Get-Command speedtest-cli -ErrorAction SilentlyContinue
    }

    if ($speedtestExe) {
        $exePath = $speedtestExe.Source
        $output = & $exePath --json 2>&1
        $json = $output | ConvertFrom-Json
        $downloadMbps = [math]::Round($json.download / 1000000, 2)
        $uploadMbps = [math]::Round($json.upload / 1000000, 2)
        $latencyMs = [math]::Round($json.ping)
        $serverInfo = $json.server.name + " (" + $json.server.country + ")"
    } else {
        $testUrls = @(
            "http://speedtest.tele2.net/1MB.zip",
            "http://proof.ovh.net/files/1Mb.dat",
            "http://speedtest.ftp.otenet.gr/files/test1Mb.db"
        )

        $downloaded = $false
        foreach ($url in $testUrls) {
            try {
                $sw = [System.Diagnostics.Stopwatch]::StartNew()
                $tempFile = [System.IO.Path]::GetTempFileName()
                $wc = New-Object System.Net.WebClient
                try {
                    $wc.DownloadFile($url, $tempFile)
                } finally {
                    $wc.Dispose()
                }
                $sw.Stop()
                $fileSize = (Get-Item $tempFile).Length
                Remove-Item $tempFile -Force -ErrorAction SilentlyContinue
                $seconds = $sw.Elapsed.TotalSeconds
                if ($seconds -gt 0) {
                    $bytesPerSec = $fileSize / $seconds
                    $downloadMbps = [math]::Round(($bytesPerSec * 8) / 1000000, 2)
                    $latencyMs = 0
                    $serverInfo = $url + " (fallback mode - upload and latency not measured)"
                    $downloaded = $true
                    break
                }
            } catch {
                continue
            }
        }

        if (-not $downloaded) {
            Write-Output '{"error": "Could not download from any test server. Check internet connection."}'
            exit 1
        }
    }

    $output = @{
        downloadMbps = $downloadMbps
        uploadMbps   = $uploadMbps
        latencyMs    = $latencyMs
        serverInfo   = $serverInfo
        rawOutput    = "Download: $downloadMbps Mbps | Upload: $(if ($speedtestExe) { "$uploadMbps Mbps" } else { "N/A (fallback mode)" }) | Latency: $latencyMs ms"
    }

    ConvertTo-Json -Compress $output
} catch {
    Write-Output ('{"error": "' + $_.Exception.Message.Replace('"', '""') + '"}')
}
