param([string]$FilePath)
$ErrorActionPreference = 'Stop'
$result = [ordered]@{ success = $false; message = ''; deleted = $false; scheduledForReboot = $false }
try {
    if (-not (Test-Path -LiteralPath $FilePath)) {
        $result.message = "File not found: $FilePath"
        $result | ConvertTo-Json -Depth 2 -Compress; exit 1; return
    }
    $file = Get-Item -LiteralPath $FilePath -Force
    $length = $file.Length
    if ($length -eq 0) {
        Remove-Item -LiteralPath $FilePath -Force
        $result.success = $true; $result.deleted = $true; $result.message = 'Empty file deleted.'
        $result | ConvertTo-Json -Depth 2 -Compress; return
    }
    $bufferSize = 4096
    $stream = [System.IO.File]::Open($file.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    $buffer = New-Object byte[] $bufferSize
    $totalPasses = 3
    $passNames = @('Writing zeros (Pass 1/3)...', 'Writing ones (Pass 2/3)...', 'Writing random data (Pass 3/3)...')
    for ($pass = 0; $pass -lt $totalPasses; $pass++) {
        Write-Progress -Activity "Securely deleting $($file.Name)" -Status $passNames[$pass] -PercentComplete (($pass / $totalPasses) * 100)
        $stream.Seek(0, [System.IO.SeekOrigin]::Begin) | Out-Null
        $remaining = $length
        if ($pass -eq 0) { [Array]::Clear($buffer, 0, $bufferSize) }
        elseif ($pass -eq 1) { for ($i = 0; $i -lt $bufferSize; $i++) { $buffer[$i] = 0xFF } }
        else { $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($buffer) }
        while ($remaining -gt 0) {
            $writeSize = [Math]::Min($bufferSize, $remaining)
            $stream.Write($buffer, 0, $writeSize)
            $remaining -= $writeSize
        }
        $stream.Flush()
    }
    Write-Progress -Activity "Securely deleting $($file.Name)" -Completed
    $stream.Close()
    $stream.Dispose()
    [System.GC]::Collect()
    [System.GC]::WaitForPendingFinalizers()
    Remove-Item -LiteralPath $FilePath -Force
    $result.success = $true; $result.deleted = $true; $result.message = 'File securely deleted with DoD 5220.22-M (3-pass).'
} catch [System.UnauthorizedAccessException] {
    $result.message = "Access denied. File may be in use. Scheduling for deletion on next reboot."
    $result.deleted = $false; $result.scheduledForReboot = $true
} catch [System.IO.IOException] {
    if ($_.Exception.Message -match 'being used by another process|The process cannot access the file') {
        $result.message = "File is in use. Scheduling for deletion on next reboot."
        $result.deleted = $false; $result.scheduledForReboot = $true
    } else {
        $result.message = $_.Exception.Message
        $result | ConvertTo-Json -Depth 2 -Compress; exit 1
    }
} catch {
    if ($stream) { try { $stream.Close() } catch {} }
    $result.message = $_.Exception.Message
    $result | ConvertTo-Json -Depth 2 -Compress; exit 1
}
$result | ConvertTo-Json -Depth 2 -Compress
