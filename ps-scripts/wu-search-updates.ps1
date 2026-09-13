# Search Windows Update for available software/OS updates (non-driver). Outputs JSON array.
# TimeoutSec is enforced by the host process; kept for compatibility with callers.
param(
    [int]$TimeoutSec = 120
)
$ErrorActionPreference = 'Stop'

try {
    $session = New-Object -ComObject Microsoft.Update.Session
    $searcher = $session.CreateUpdateSearcher()
    # Include IsHidden filter and exclude already downloaded hidden items.
    # NOTE: the search runs synchronously on purpose. A previous Start-Job variant
    # returned hollow entries (COM properties do not survive the job boundary),
    # so real updates never surfaced. Host-side timeout still bounds hung runs.
    $criteria = "IsInstalled=0 and IsHidden=0 and Type='Software'"
    $result = $searcher.Search($criteria)

    $updates = @()
    for ($i = 0; $i -lt $result.Updates.Count; $i++) {
        $u = $result.Updates.Item($i)
        # One corrupt/deserialized entry must never kill the whole scan (see null date below).
        if ($null -eq $u) { continue }
        # Skip drivers if they sneaked in via Category check
        $isDriver = $false
        try {
            foreach ($cat in $u.Categories) {
                if ($cat.Name -like "*Driver*") { $isDriver = $true; break }
            }
        } catch {}
        if ($isDriver) { continue }
        $kb = ''
        try { $kb = ($u.KBArticleIDs | Select-Object -First 1) } catch { }
        $updates += [ordered]@{
            updateId    = $u.Identity.UpdateID
            title       = $u.Title
            description = $u.Description
            version     = if ($kb -and $kb.Length -gt 0) { $kb } elseif ($u.LastDeploymentChangeDate) { $u.LastDeploymentChangeDate.ToString('yyyy-MM-dd') } else { '' }
            sizeBytes   = [long]$u.MaxDownloadSize
            severity    = [string]$u.MsrcSeverity
            kbArticle   = [string]$kb
            categories  = @($u.Categories | ForEach-Object { $_.Name })
        }
    }
    if ($updates.Count -eq 0) {
        "[]" | Write-Output
    } else {
        $updates | ConvertTo-Json -Depth 5 -Compress
    }
} catch {
    Write-Error "Windows Update search failed: $_"
    exit 1
}
