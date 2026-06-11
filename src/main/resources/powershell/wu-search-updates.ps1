# Search Windows Update for available software/OS updates (non-driver). Outputs JSON array.
param(
    [int]$TimeoutSec = 120
)
$ErrorActionPreference = 'Stop'

try {
    $session = New-Object -ComObject Microsoft.Update.Session
    $searcher = $session.CreateUpdateSearcher()
    $criteria = "IsInstalled=0 and Type='Software'"
    $result = $searcher.Search($criteria)
    $updates = @()
    for ($i = 0; $i -lt $result.Updates.Count; $i++) {
        $u = $result.Updates.Item($i)
        $kb = ''
        try { $kb = ($u.KBArticleIDs | Select-Object -First 1) } catch { }
        $updates += [ordered]@{
            updateId    = $u.Identity.UpdateID
            title       = $u.Title
            description = $u.Description
            version     = $u.Identity.RevisionNumber
            sizeBytes   = [long]$u.MaxDownloadSize
            severity    = [string]$u.MsrcSeverity
            kbArticle   = [string]$kb
            categories  = @($u.Categories | ForEach-Object { $_.Name })
        }
    }
    $updates | ConvertTo-Json -Depth 5 -Compress
} catch {
    Write-Error "Windows Update search failed: $_"
    exit 1
}
