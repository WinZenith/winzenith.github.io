param([string]$FilePath)
$ErrorActionPreference = 'Stop'
$result = [ordered]@{ success = $false; message = '' }
try {
    $path = "HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager"
    $name = "PendingFileRenameOperations"
    $fullPath = (Resolve-Path -LiteralPath $FilePath -ErrorAction Stop).Path
    $existing = @()
    if (Test-Path "$path\$name") {
        $existing = Get-ItemProperty -Path $path -Name $name -ErrorAction SilentlyContinue
        if ($existing -and $existing.$name) { $existing = $existing.$name } else { $existing = @() }
    }
    $entry = "\??\$fullPath"
    $updated = @($existing) + @($entry)
    if (-not (Test-Path $path)) { New-Item -Path $path -Force | Out-Null }
    Set-ItemProperty -Path $path -Name $name -Value $updated -Type MultiString
    $result.success = $true
    $result.message = "File scheduled for deletion on next system restart: $fullPath"
} catch {
    $result.message = "Failed to schedule deletion: $($_.Exception.Message)"
    $result | ConvertTo-Json -Depth 2 -Compress; exit 1
}
$result | ConvertTo-Json -Depth 2 -Compress
