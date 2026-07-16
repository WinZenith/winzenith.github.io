param([string]$FolderPath, [int]$PassCount = 3)
$ErrorActionPreference = 'Stop'
$result = [ordered]@{ success = $false; message = ''; filesDeleted = 0; foldersDeleted = 0; scheduledForReboot = @() }

try {
    if (-not (Test-Path -LiteralPath $FolderPath -ErrorAction SilentlyContinue)) {
        $result.message = "Folder not found: $FolderPath"
        $result | ConvertTo-Json -Depth 3 -Compress; exit 1; return
    }

    $bufferSize = 4096
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()

    $files = Get-ChildItem -LiteralPath $FolderPath -Recurse -File -Force -ErrorAction SilentlyContinue
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

        try {
            if ($file.Length -eq 0) {
                Remove-Item -LiteralPath $file.FullName -Force
                $result.filesDeleted++
                continue
            }

            $stream = [System.IO.File]::Open($file.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
            $buffer = New-Object byte[] $bufferSize
            for ($pass = 0; $pass -lt $PassCount; $pass++) {
                $passType = $pass % 3
                if ($passType -eq 0) { [Array]::Clear($buffer, 0, $bufferSize) }
                elseif ($passType -eq 1) { for ($i = 0; $i -lt $bufferSize; $i++) { $buffer[$i] = 0xFF } }
                else { $rng.GetBytes($buffer) }

                $stream.Seek(0, [System.IO.SeekOrigin]::Begin) | Out-Null
                $remaining = $file.Length
                while ($remaining -gt 0) {
                    $writeSize = [Math]::Min($bufferSize, $remaining)
                    $stream.Write($buffer, 0, [int]$writeSize)
                    $remaining -= $writeSize
                }
                $stream.Flush()
            }
            $stream.Close()
            $stream.Dispose()
            Remove-Item -LiteralPath $file.FullName -Force
            $result.filesDeleted++
        } catch [System.UnauthorizedAccessException] {
            $result.scheduledForReboot += $file.FullName
        } catch [System.IO.IOException] {
            if ($_.Exception.Message -match 'being used|cannot access') {
                $result.scheduledForReboot += $file.FullName
            } else {
                $result.scheduledForReboot += $file.FullName
            }
        } catch {
            $result.scheduledForReboot += $file.FullName
        }
    }

    $remainingDirs = Get-ChildItem -LiteralPath $FolderPath -Recurse -Directory -Force -ErrorAction SilentlyContinue | Sort-Object -Property FullName -Descending
    foreach ($dir in $remainingDirs) {
        try {
            Remove-Item -LiteralPath $dir.FullName -Force -ErrorAction SilentlyContinue
            $result.foldersDeleted++
        } catch {}
    }
    try {
        Remove-Item -LiteralPath $FolderPath -Force -ErrorAction SilentlyContinue
        $result.foldersDeleted++
    } catch {}

    $result.success = $true
    $result.message = "Secure folder delete: $($result.filesDeleted) files overwritten, $($result.foldersDeleted) folders removed."

} catch {
    $result.message = $_.Exception.Message
    $result | ConvertTo-Json -Depth 3 -Compress; exit 1
}

$result | ConvertTo-Json -Depth 3 -Compress
