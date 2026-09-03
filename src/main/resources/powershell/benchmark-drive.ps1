param([string]$DriveLetter, [int]$TestSizeMB = 64, [string]$StopFlagPath = "")
$ErrorActionPreference = 'Stop'

$letter = $DriveLetter.Replace(':', '')
# Critical fix: previously hardcoded to "$letter:\Users\Public\__winzenith_bench__" which
# polluted non-system drives with a Users\Public hierarchy and could fail under
# Controlled Folder Access. Use a unique drive-root temp dir instead.
$runId = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$testDir = "$($letter):\.winzenith-bench-$runId"
$legacyTestDir = "$($letter):\Users\Public\__winzenith_bench__"
$testFile = Join-Path $testDir "bench_test.tmp"
$bufferSize = 1024 * 1024
# Backward compat: older Java passed "-StopFlagPath <path>" via $args; new Java passes
# it as the 3rd positional param. Support both.
if ((-not $StopFlagPath) -and ($args.Count -ge 3)) {
    for ($i = 0; $i -lt $args.Count - 1; $i++) {
        if ($args[$i] -eq '-StopFlagPath') { $StopFlagPath = $args[$i + 1]; break }
    }
    if ((-not $StopFlagPath) -and ($args.Count -ge 1) -and ($args[0] -notlike '-*')) {
        $StopFlagPath = $args[0]
    }
}

function Check-Stop {
    if ($StopFlagPath -and (Test-Path -LiteralPath $StopFlagPath -ErrorAction SilentlyContinue)) {
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
    if ($TestSizeMB -lt 1 -or $TestSizeMB -gt 1024) {
        throw "Invalid test size: $TestSizeMB MB (allowed 1-1024)"
    }

    # Critical fix: pre-check free space so a nearly-full drive is never filled to 0
    # (which can freeze the OS / apps). Require test size + 100 MB headroom.
    $totalBytes = [long]$TestSizeMB * 1024 * 1024
    try {
        $vol = Get-PSDrive -Name $letter -ErrorAction SilentlyContinue
        $freeBytes = if ($vol -and $null -ne $vol.Free) { [long]$vol.Free } else { -1 }
        if ($freeBytes -ge 0 -and $freeBytes -lt ($totalBytes + 100MB)) {
            throw "Insufficient free space on $($letter): (needs ~$TestSizeMB MB + 100 MB headroom, free $([math]::Round($freeBytes/1MB,1)) MB)"
        }
    } catch {
        if ($_.Exception.Message -match 'Insufficient free space') { throw }
        # If free-space query itself failed, continue and let the write fail naturally.
    }

    if (-not (Test-Path -LiteralPath $testDir)) {
        New-Item -ItemType Directory -Path $testDir -Force | Out-Null
    }

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
        if ($testFile -and (Test-Path -LiteralPath $testFile)) {
            Remove-Item -LiteralPath $testFile -Force -ErrorAction SilentlyContinue
        }
        if ($testDir -and (Test-Path -LiteralPath $testDir)) {
            Remove-Item -LiteralPath $testDir -Recurse -Force -ErrorAction SilentlyContinue
        }
        # Best-effort cleanup of legacy dirs from older versions (only if empty to avoid
        # deleting unrelated user data that happens to share the path).
        if ($legacyTestDir -and (Test-Path -LiteralPath $legacyTestDir)) {
            $leftover = Join-Path $legacyTestDir "bench_test.tmp"
            if (Test-Path -LiteralPath $leftover) {
                Remove-Item -LiteralPath $leftover -Force -ErrorAction SilentlyContinue
            }
            $remaining = Get-ChildItem -LiteralPath $legacyTestDir -Force -ErrorAction SilentlyContinue
            if (-not $remaining) {
                Remove-Item -LiteralPath $legacyTestDir -Force -ErrorAction SilentlyContinue
            }
        }
    } catch {}
}

$result | ConvertTo-Json -Depth 2 -Compress
