param([string]$FilePath, [int]$PassCount = 3)
$ErrorActionPreference = 'Stop'
$result = [ordered]@{ success = $false; message = ''; deleted = $false; scheduledForReboot = $false }
$stream = $null
$rng = $null
try {
    if (-not (Test-Path -LiteralPath $FilePath)) {
        $result.message = "File not found: $FilePath"
        $result | ConvertTo-Json -Depth 2 -Compress; exit 1; return
    }
    $file = Get-Item -LiteralPath $FilePath -Force
    $length = $file.Length
    if ($length -eq 0) {
        Remove-Item -LiteralPath $FilePath -Force -ErrorAction SilentlyContinue
        if (-not (Test-Path -LiteralPath $FilePath)) {
            $result.success = $true; $result.deleted = $true; $result.message = 'Empty file deleted.'
        } else {
            $result.message = "Failed to delete empty file."
            $result | ConvertTo-Json -Depth 2 -Compress; exit 1
        }
        $result | ConvertTo-Json -Depth 2 -Compress; return
    }
    $bufferSize = 4096
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $stream = [System.IO.File]::Open($file.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    $buffer = New-Object byte[] $bufferSize
    for ($pass = 0; $pass -lt $PassCount; $pass++) {
        $stream.Seek(0, [System.IO.SeekOrigin]::Begin) | Out-Null
        $remaining = $length
        $passType = $pass % 3
        while ($remaining -gt 0) {
            $writeSize = [Math]::Min($bufferSize, $remaining)
            $chunk = New-Object byte[] $writeSize
            if ($passType -eq 0) { [Array]::Clear($chunk, 0, $writeSize) }
            elseif ($passType -eq 1) { for ($i = 0; $i -lt $writeSize; $i++) { $chunk[$i] = 0xFF } }
            else { $rng.GetBytes($chunk) }
            $stream.Write($chunk, 0, $writeSize)
            $remaining -= $writeSize
        }
        $stream.Flush()
    }
    $stream.Close(); $stream.Dispose(); $stream = $null
    [System.GC]::Collect()
    [System.GC]::WaitForPendingFinalizers()
    Remove-Item -LiteralPath $FilePath -Force -ErrorAction SilentlyContinue
    if (-not (Test-Path -LiteralPath $FilePath)) {
        $result.success = $true; $result.deleted = $true; $result.message = "File securely deleted with $PassCount pass(es)."
    } else {
        $result.message = "Failed to delete file after overwrite."
        $result | ConvertTo-Json -Depth 2 -Compress; exit 1
    }
} catch [System.UnauthorizedAccessException] {
    if ($stream) { try { $stream.Close(); $stream.Dispose() } catch {} ; $stream = $null }
    $result.message = "Access denied. File may be in use. Scheduling for deletion on next reboot."
    $result.deleted = $false; $result.scheduledForReboot = $true
} catch [System.IO.IOException] {
    if ($stream) { try { $stream.Close(); $stream.Dispose() } catch {} ; $stream = $null }
    if ($_.Exception.Message -match 'being used by another process|The process cannot access the file') {
        $result.message = "File is in use. Scheduling for deletion on next reboot."
        $result.deleted = $false; $result.scheduledForReboot = $true
    } else {
        $result.message = $_.Exception.Message
        $result | ConvertTo-Json -Depth 2 -Compress; exit 1
    }
} catch {
    if ($stream) { try { $stream.Close(); $stream.Dispose() } catch {} ; $stream = $null }
    $result.message = $_.Exception.Message
    $result | ConvertTo-Json -Depth 2 -Compress; exit 1
} finally {
    if ($stream) { try { $stream.Close(); $stream.Dispose() } catch {} }
    if ($rng) { try { $rng.Dispose() } catch {} }
}
$result | ConvertTo-Json -Depth 2 -Compress
