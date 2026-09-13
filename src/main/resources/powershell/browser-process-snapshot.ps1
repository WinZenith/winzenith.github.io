$OutputEncoding = [System.Text.Encoding]::UTF8
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$result = @()
$names = @('chrome.exe', 'msedge.exe', 'firefox.exe', 'brave.exe', 'opera.exe', 'vivaldi.exe')

try {
    Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $n = $_.Name
        if ($null -eq $n) { return $false }
        $names -contains ($n.ToLowerInvariant())
    } | ForEach-Object {
        $result += [PSCustomObject]@{
            name             = $_.Name
            executablePath   = if ($_.ExecutablePath) { $_.ExecutablePath } else { "" }
            commandLine      = if ($_.CommandLine) { $_.CommandLine } else { "" }
        }
    }
} catch {
    [Console]::Error.WriteLine("browser-process-snapshot: $($_.Exception.Message)")
    exit 1
}

if ($result.Count -eq 0) {
    Write-Output "[]"
} else {
    $jsonOut = ConvertTo-Json -Compress -InputObject @($result)
    if ($result.Count -eq 1 -and $jsonOut.TrimStart().StartsWith("{")) {
        $jsonOut = "[" + $jsonOut + "]"
    }
    Write-Output $jsonOut
}
exit 0
