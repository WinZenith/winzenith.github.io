# Search Windows Update for OS/software updates (non-driver). Outputs JSON array.
param(
    [string]$Action = 'Search'
)
$ErrorActionPreference = 'Stop'
$session = New-Object -ComObject Microsoft.Update.Session
$searcher = $session.CreateUpdateSearcher()
$criteria = "IsInstalled=0 and Type='Software'"
$result = $searcher.Search($criteria)
$updates = @()
for ($i = 0; $i -lt $result.Updates.Count; $i++) {
    $u = $result.Updates.Item($i)
    $kb = ''
    try {
        if ($u.KBArticleIDs.Count -gt 0) { $kb = 'KB' + $u.KBArticleIDs.Item(0) }
    } catch { }
    $updates += [ordered]@{
        updateId    = $u.Identity.UpdateID
        title       = $u.Title
        description = $u.Description
        sizeBytes   = $u.MaxDownloadSize
        importance  = [string]$u.MsrcSeverity
        kbArticle   = $kb
        rebootRequired = $u.RebootRequired
    }
}
$updates | ConvertTo-Json -Depth 5 -Compress
