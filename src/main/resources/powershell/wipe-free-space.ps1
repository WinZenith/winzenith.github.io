param([string[]]$DriveLetters, [string]$StopFlagPath = "", [int]$PassCount = 3)
$ErrorActionPreference = 'Continue'
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$tempFiles = @()

function Write-ProgressJson {
    param([string]$driveLetter, [int]$percent, [int]$pass, [int]$totalPasses,
          [bool]$done = $false, [string]$message = "", [string]$tempFile = "")
    $o = [ordered]@{
        drive       = $driveLetter
        percent     = $percent
        pass        = $pass
        totalPasses = $totalPasses
        done        = $done
        message     = $message
        tempFile    = $tempFile
    }
    Write-Output ($o | ConvertTo-Json -Depth 2 -Compress)
}

function Test-ShouldStop {
    if ($StopFlagPath -and (Test-Path $StopFlagPath -ErrorAction SilentlyContinue)) { return $true }
    return $false
}

function Cleanup-TempFiles {
    foreach ($f in $tempFiles) {
        if (Test-Path $f -ErrorAction SilentlyContinue) {
            Remove-Item $f -Force -ErrorAction SilentlyContinue
        }
    }
}

$bufferSize = 10 * 1024 * 1024
$buffer = New-Object byte[] $bufferSize
$maxWriteBytes = 512MB
$reserveBytes = 500MB

foreach ($driveLetter in $DriveLetters) {
    if (Test-ShouldStop) { break }
    $drive = $driveLetter -replace ':', ''

    $volume = Get-PSDrive -Name $drive -ErrorAction SilentlyContinue
    if (-not $volume) {
        Write-ProgressJson -driveLetter $driveLetter -percent 0 -pass 0 -totalPasses $PassCount -done $true -message "Drive not found: $driveLetter"
        continue
    }

    $freeBytes = $volume.Free
    $totalToWrite = [Math]::Max(0, [Math]::Min(($freeBytes - $reserveBytes), $maxWriteBytes))

    if ($totalToWrite -le 0) {
        Write-ProgressJson -driveLetter $driveLetter -percent 100 -pass $PassCount -totalPasses $PassCount -done $true -message "Insufficient free space on $driveLetter"
        continue
    }

    $tempFile = "$drive`:\\~sbtools-wipe-$([System.IO.Path]::GetRandomFileName()).tmp"
    $tempFiles += $tempFile
    $stream = $null

    Write-ProgressJson -driveLetter $driveLetter -percent 0 -pass 0 -totalPasses $PassCount -tempFile $tempFile

    try {
        $stream = [System.IO.File]::OpenWrite($tempFile)

        for ($pass = 1; $pass -le $PassCount; $pass++) {
            if (Test-ShouldStop) { break }
            $stream.Seek(0, [System.IO.SeekOrigin]::Begin) | Out-Null
            $remaining = $totalToWrite
            $written = 0L

            while ($remaining -gt 0) {
                if (Test-ShouldStop) { break }
                $chunkSize = [int][Math]::Min($bufferSize, $remaining)
                $chunk = $buffer
                if ($chunkSize -lt $bufferSize) { $chunk = New-Object byte[] $chunkSize }

                $passType = ($pass - 1) % 3
                if ($passType -eq 1) {
                    for ($i = 0; $i -lt $chunkSize; $i++) { $chunk[$i] = 0xFF }
                } elseif ($passType -eq 2) {
                    $rng.GetBytes($chunk)
                } else {
                    [Array]::Clear($chunk, 0, $chunkSize)
                }

                $stream.Write($chunk, 0, $chunkSize)
                $stream.Flush()
                $remaining -= $chunkSize
                $written += $chunkSize
                $percent = [int](($written * 100) / $totalToWrite)
                Write-ProgressJson -driveLetter $driveLetter -percent $percent -pass $pass -totalPasses $PassCount -tempFile $tempFile
            }

            if (-not (Test-ShouldStop)) {
                Write-ProgressJson -driveLetter $driveLetter -percent 100 -pass $pass -totalPasses $PassCount -tempFile $tempFile
            }
        }

        $stream.Close(); $stream = $null
    } catch {
        Write-ProgressJson -driveLetter $driveLetter -percent 0 -pass 0 -totalPasses $PassCount -done $true -tempFile $tempFile -message $_.Exception.Message
    } finally {
        if ($stream) { try { $stream.Close() } catch {} }
    }

    if (-not (Test-ShouldStop)) {
        Cleanup-TempFiles
        Write-ProgressJson -driveLetter $driveLetter -percent 100 -pass $PassCount -totalPasses $PassCount -done $true -tempFile $tempFile -message "Wipe completed on $driveLetter"
    } else {
        Cleanup-TempFiles
        Write-ProgressJson -driveLetter $driveLetter -percent 0 -pass 0 -totalPasses $PassCount -done $true -tempFile $tempFile -message "Stopped by user on $driveLetter"
    }
}

Cleanup-TempFiles
if ($rng) { $rng.Dispose() }
