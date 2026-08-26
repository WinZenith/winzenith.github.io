param([string]$FilePath)
$ErrorActionPreference = 'Stop'
$result = [ordered]@{ success = $false; message = '' }
try {
    $path = "HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager"
    $name = "PendingFileRenameOperations"
    try {
        $fullPath = (Resolve-Path -LiteralPath $FilePath -ErrorAction Stop).Path
    } catch {
        # Fallback for paths that are already partially deleted or use extended syntax
        try { $fullPath = [System.IO.Path]::GetFullPath($FilePath) } catch { $fullPath = $FilePath }
    }
    $existing = @()
    try {
        $prop = Get-ItemProperty -Path $path -Name $name -ErrorAction Stop
        if ($prop -and $null -ne $prop.$name) {
            $existing = $prop.$name
            if ($null -eq $existing) { $existing = @() }
            elseif ($existing -isnot [Array]) { $existing = @($existing) }
        }
    } catch {
        $existing = @()
    }
    # PendingFileRenameOperations requires pairs: source and destination (empty string for delete)
    $entrySource = "\??\$fullPath"
    $entryDest = ""
    $updated = @()
    if ($existing.Count -gt 0) { $updated += $existing }
    $updated += $entrySource
    $updated += $entryDest
    if (-not (Test-Path $path)) { New-Item -Path $path -Force | Out-Null }
    Set-ItemProperty -Path $path -Name $name -Value $updated -Type MultiString
    $result.success = $true
    $result.message = "File scheduled for deletion on next system restart: $fullPath"
} catch {
    $result.message = "Failed to schedule deletion: $($_.Exception.Message)"
    $result | ConvertTo-Json -Depth 2 -Compress; exit 1
}
$result | ConvertTo-Json -Depth 2 -Compress
