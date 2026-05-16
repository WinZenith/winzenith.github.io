# Search Windows Update for available driver updates. Outputs JSON array.
# TimeoutSec is enforced by the host process; kept for compatibility with callers.
param(
    [int]$TimeoutSec = 120
)
$ErrorActionPreference = 'Stop'
$session = New-Object -ComObject Microsoft.Update.Session
$searcher = $session.CreateUpdateSearcher()
$criteria = "IsInstalled=0 and Type='Driver'"
$result = $searcher.Search($criteria)
$updates = @()
for ($i = 0; $i -lt $result.Updates.Count; $i++) {
    $u = $result.Updates.Item($i)
    $kb = ''
    try { $kb = $u.KBArticleIDs | Select-Object -First 1 } catch { }
    $updates += [ordered]@{
        updateId    = $u.Identity.UpdateID
        title       = $u.Title
        description = $u.Description
        version     = if ($u.DriverModel) { $u.DriverModel } else { $u.Title }
        sizeBytes   = $u.MaxDownloadSize
        severity    = [string]$u.MsrcSeverity
        kbArticle   = [string]$kb
        categories  = @($u.Categories | ForEach-Object { $_.Name })
    }
}
$updates | ConvertTo-Json -Depth 5 -Compress
