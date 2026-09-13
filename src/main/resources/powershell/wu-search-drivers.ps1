# Search Windows Update for available driver updates. Outputs JSON array.
# TimeoutSec is enforced by the host process; kept for compatibility with callers.
param(
    [int]$TimeoutSec = 120
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $OutputEncoding

# Fullest dotted version across the given fields (most parts wins, longest
# breaks ties). Replaces the old ordered cascade that let a short DriverModel
# shadow a full Title.
function Get-FullestVersion {
    param([string[]]$Fields)
    $best = ''
    $bestParts = 0
    foreach ($f in $Fields) {
        if (-not $f) { continue }
        foreach ($m in [regex]::Matches($f, '\b\d+(?:\.\d+)+\b')) {
            $v = $m.Value
            $parts = ($v -split '\.').Count
            if ($parts -gt $bestParts -or ($parts -eq $bestParts -and $v.Length -gt $best.Length)) {
                $best = $v
                $bestParts = $parts
            }
        }
    }
    return $best
}

try {
    $session = New-Object -ComObject Microsoft.Update.Session
    $searcher = $session.CreateUpdateSearcher()
    $criteria = "IsInstalled=0 and Type='Driver'"
    $result = $searcher.Search($criteria)
    $updates = @()
    for ($i = 0; $i -lt $result.Updates.Count; $i++) {
        # Per-update guard: one corrupt/superseded metadata entry must not
        # abort the whole search ($ErrorActionPreference='Stop' would exit 1
        # and hide every valid offer behind the 15m negative cache).
        try {
            $u = $result.Updates.Item($i)
            $id = ''
            try { $id = [string]$u.Identity.UpdateID } catch { $id = '' }
            if (-not $id -or -not $id.Trim()) { continue }
            $ti = ''
            try { $ti = [string]$u.Title } catch { $ti = '' }
            if (-not $ti -or -not $ti.Trim()) { continue }
            $kb = ''
            try { $kb = $u.KBArticleIDs | Select-Object -First 1 } catch { }
            $driverHw = ''
            $driverModel = ''
            $driverProvider = ''
            $driverClass = ''
            try { $driverHw = [string]$u.DriverHardwareID } catch { }
            try { $driverModel = [string]$u.DriverModel } catch { }
            try { $driverProvider = [string]$u.DriverProviderName } catch { }
            try { $driverClass = [string]$u.DriverClass } catch { }
            $updates += [ordered]@{
                updateId    = $id
                title       = $ti
                description = $u.Description
                driverHardwareId = $driverHw
                driverModel = $driverModel
                driverProvider = $driverProvider
                driverClass = $driverClass
                # Fullest version across DriverModel and Title: a short
                # DriverModel ("6.0.9678") must not shadow the full Title
                # ("6.0.9678.1") into missed updates / false VERIFIEDs.
                version     = Get-FullestVersion @($u.DriverModel, $u.Title)
                sizeBytes   = $u.MaxDownloadSize
                severity    = [string]$u.MsrcSeverity
                kbArticle   = [string]$kb
                categories  = @($u.Categories | ForEach-Object { $_.Name })
            }
        } catch { continue }
    }
    $updates | ConvertTo-Json -Depth 5 -Compress
} catch {
    Write-Error "Windows Update driver search failed: $_"
    exit 1
}
