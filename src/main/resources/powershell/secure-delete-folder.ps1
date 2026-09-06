param([string]$FolderPath, [int]$PassCount = 3)
$ErrorActionPreference = 'Stop'
$result = [ordered]@{ success = $false; message = ''; filesDeleted = 0; foldersDeleted = 0; scheduledForReboot = @() }

try {
    if (-not (Test-Path -LiteralPath $FolderPath -ErrorAction SilentlyContinue)) {
        $result.message = "Folder not found: $FolderPath"
        $result | ConvertTo-Json -Depth 3 -Compress; exit 1; return
    }

    $bufferSize = 65536
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()

    # Strict-safety: never follow directory junctions / symlinks (would escape target).
    # Enumerate files without following reparse points; skip any reparse-point file.
    $files = Get-ChildItem -LiteralPath $FolderPath -Recurse -File -Force -ErrorAction SilentlyContinue |
        Where-Object { -not ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) }
    $skippedReparse = @()
    try {
        $allFiles = Get-ChildItem -LiteralPath $FolderPath -Recurse -File -Force -ErrorAction SilentlyContinue
        foreach ($a in @($allFiles)) {
            try {
                if ($a.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { $skippedReparse += $a.FullName }
            } catch {}
        }
    } catch {}
    if (-not $files) {
        Remove-Item -LiteralPath $FolderPath -Recurse -Force -ErrorAction SilentlyContinue
        $result.success = $true
        $result.message = "Empty folder removed."
        $result | ConvertTo-Json -Depth 3 -Compress; return
    }

    $totalFiles = $files.Count
    $currentFile = 0

    foreach ($file in $files) {
        $currentFile++
        $pct = [int]([double]$currentFile / $totalFiles * 100)
        Write-Output "{`"progress`":$pct,`"phase`":`"overwrite`",`"file`":`"$($file.Name.Replace('"','\"'))`",`"current`":$currentFile,`"total`":$totalFiles}"

        $stream = $null
        try {
            if ($file.Length -eq 0) {
                Remove-Item -LiteralPath $file.FullName -Force -ErrorAction SilentlyContinue
                if (-not (Test-Path -LiteralPath $file.FullName)) { $result.filesDeleted++ } else { $result.scheduledForReboot += $file.FullName }
                continue
            }

            $stream = [System.IO.File]::Open($file.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
            $buffer = New-Object byte[] $bufferSize
            for ($pass = 0; $pass -lt $PassCount; $pass++) {
                $passType = $pass % 3
                $stream.Seek(0, [System.IO.SeekOrigin]::Begin) | Out-Null
                $remaining = $file.Length
                while ($remaining -gt 0) {
                    $writeSize = [Math]::Min($bufferSize, $remaining)
                    $chunk = $buffer
                    if ($writeSize -lt $bufferSize) { $chunk = New-Object byte[] $writeSize }
                    if ($passType -eq 0) { [Array]::Clear($chunk, 0, $writeSize) }
                    elseif ($passType -eq 1) { for ($i = 0; $i -lt $writeSize; $i++) { $chunk[$i] = 0xFF } }
                    else { $rng.GetBytes($chunk) }
                    $stream.Write($chunk, 0, [int]$writeSize)
                    $remaining -= $writeSize
                }
                $stream.Flush()
            }
            $stream.Close(); $stream.Dispose(); $stream = $null
            Remove-Item -LiteralPath $file.FullName -Force -ErrorAction SilentlyContinue
            if (-not (Test-Path -LiteralPath $file.FullName)) { $result.filesDeleted++ } else { $result.scheduledForReboot += $file.FullName }
        } catch [System.UnauthorizedAccessException] {
            if ($stream) { try { $stream.Close(); $stream.Dispose() } catch {} ; $stream = $null }
            $result.scheduledForReboot += $file.FullName
        } catch [System.IO.IOException] {
            if ($stream) { try { $stream.Close(); $stream.Dispose() } catch {} ; $stream = $null }
            $result.scheduledForReboot += $file.FullName
        } catch {
            if ($stream) { try { $stream.Close(); $stream.Dispose() } catch {} ; $stream = $null }
            $result.scheduledForReboot += $file.FullName
        } finally {
            if ($stream) { try { $stream.Close(); $stream.Dispose() } catch {} }
        }
    }

    $remainingDirs = Get-ChildItem -LiteralPath $FolderPath -Recurse -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object { -not ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) } |
        Sort-Object -Property FullName -Descending
    foreach ($dir in $remainingDirs) {
        try {
            if (-not (Test-Path -LiteralPath $dir.FullName)) { continue }
            Remove-Item -LiteralPath $dir.FullName -Force -ErrorAction SilentlyContinue
            if (-not (Test-Path -LiteralPath $dir.FullName)) { $result.foldersDeleted++ }
        } catch {}
    }
    try {
        if (Test-Path -LiteralPath $FolderPath) {
            Remove-Item -LiteralPath $FolderPath -Force -ErrorAction SilentlyContinue
            if (-not (Test-Path -LiteralPath $FolderPath)) { $result.foldersDeleted++ }
        } else {
            # Already removed if empty
            if ($result.foldersDeleted -eq 0) { $result.foldersDeleted = 1 }
        }
    } catch {}

    $allFilesHandled = ($result.filesDeleted + $result.scheduledForReboot.Count) -ge $totalFiles
    $result.success = $allFilesHandled
    if ($result.scheduledForReboot.Count -gt 0) {
        $result.message = "Secure folder delete: $($result.filesDeleted) files overwritten, $($result.foldersDeleted) folders removed, $($result.scheduledForReboot.Count) scheduled for reboot."
    } else {
        $result.message = "Secure folder delete: $($result.filesDeleted) files overwritten, $($result.foldersDeleted) folders removed."
    }
    if ($skippedReparse.Count -gt 0) {
        $result.message += " Skipped $($skippedReparse.Count) symlink/junction(s) (not followed)."
    }

} catch {
    $result.message = $_.Exception.Message
    $result | ConvertTo-Json -Depth 3 -Compress; exit 1
}

$result | ConvertTo-Json -Depth 3 -Compress
