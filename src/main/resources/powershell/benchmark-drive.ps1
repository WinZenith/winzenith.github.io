param([string]$DriveLetter, [int]$TestSizeMB = 64)
$ErrorActionPreference = 'Stop'

$letter = $DriveLetter.Replace(':', '')
$testDir = "$($letter):\Users\Public\__winzenith_bench__"
$testFile = "$testDir\bench_test.tmp"
$bufferSize = 1024 * 1024
$stopFlagPath = $null
if ($args.Count -gt 2 -and $args[2] -eq '-StopFlagPath') {
    $stopFlagPath = $args[3]
}

function Check-Stop {
    if ($stopFlagPath -and (Test-Path -LiteralPath $stopFlagPath -ErrorAction SilentlyContinue)) {
        throw "Benchmark cancelled by user"
    }
}

$result = [ordered]@{
    success = $false
    driveLetter = "${letter}:"
    testSizeMB = $TestSizeMB
    seqWriteMBps = 0.0
    seqReadMBps = 0.0
    randomReadIOPS = 0.0
    message = ''
}

try {
    if (-not (Test-Path -LiteralPath "$($letter):\" -ErrorAction SilentlyContinue)) {
        throw "Drive $($letter): not found"
    }

    if (-not (Test-Path -LiteralPath $testDir)) {
        New-Item -ItemType Directory -Path $testDir -Force | Out-Null
    }

    $totalBytes = [long]$TestSizeMB * 1024 * 1024
    $buffer = New-Object byte[] $bufferSize
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($buffer)

    Write-Output ('{"progress":0,"phase":"write"}')
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    $stream = [System.IO.File]::Open($testFile, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    $written = 0
    while ($written -lt $totalBytes) {
        Check-Stop
        $toWrite = [Math]::Min($bufferSize, $totalBytes - $written)
        $stream.Write($buffer, 0, [int]$toWrite)
        $written += $toWrite
        $pct = [int]([double]$written / $totalBytes * 50)
        if ($pct % 5 -eq 0) {
            Write-Output "{`"progress`":$pct,`"phase`":`"write`"}"
        }
    }
    $stream.Flush()
    $stream.Close()
    $sw.Stop()

    $writeSeconds = $sw.Elapsed.TotalSeconds
    if ($writeSeconds -gt 0) {
        $result.seqWriteMBps = [math]::Round($TestSizeMB / $writeSeconds, 2)
    }

    Write-Output ('{"progress":50,"phase":"read"}')
    $sw.Restart()

    $stream = [System.IO.File]::Open($testFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    $readBuffer = New-Object byte[] $bufferSize
    $totalRead = 0
    while ($totalRead -lt $totalBytes) {
        Check-Stop
        $bytesRead = $stream.Read($readBuffer, 0, $bufferSize)
        if ($bytesRead -eq 0) { break }
        $totalRead += $bytesRead
        $pct = 50 + [int]([double]$totalRead / $totalBytes * 40)
        if ($pct % 5 -eq 0) {
            Write-Output "{`"progress`":$pct,`"phase`":`"read`"}"
        }
    }
    $stream.Close()
    $sw.Stop()

    $readSeconds = $sw.Elapsed.TotalSeconds
    if ($readSeconds -gt 0) {
        $result.seqReadMBps = [math]::Round($TestSizeMB / $readSeconds, 2)
    }

    Write-Output ('{"progress":90,"phase":"random_read"}')
    $sw.Restart()
    $stream = [System.IO.File]::Open($testFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    $randomOps = 0
    $maxOps = 200
    $rngForSeek = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $seekBuf = New-Object byte[] 8
    while ($randomOps -lt $maxOps) {
        Check-Stop
        $rngForSeek.GetBytes($seekBuf)
        $offset = [long]([BitConverter]::ToUInt64($seekBuf, 0) % [math]::Max(1, $totalBytes - $bufferSize))
        $stream.Seek($offset, [System.IO.SeekOrigin]::Begin) | Out-Null
        $stream.Read($readBuffer, 0, $bufferSize) | Out-Null
        $randomOps++
        if ($randomOps % 50 -eq 0) {
            $pct = 90 + [int]([double]$randomOps / $maxOps * 10)
            Write-Output "{`"progress`":$pct,`"phase`":`"random_read`"}"
        }
    }
    $stream.Close()
    $sw.Stop()

    $randomSeconds = $sw.Elapsed.TotalSeconds
    if ($randomSeconds -gt 0) {
        $result.randomReadIOPS = [math]::Round($maxOps / $randomSeconds, 1)
    }

    $result.success = $true
    $result.message = "Benchmark completed."
    Write-Output ('{"progress":100,"phase":"done"}')

} catch {
    $result.message = $_.Exception.Message
    Write-Output ("{`"progress`":0,`"phase`":`"error`",`"message`":`"" + $_.Exception.Message.Replace('"', '\"') + "`"}")
} finally {
    try {
        if (Test-Path -LiteralPath $testFile) {
            Remove-Item -LiteralPath $testFile -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath $testDir) {
            Remove-Item -LiteralPath $testDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    } catch {}
}

$result | ConvertTo-Json -Depth 2 -Compress
