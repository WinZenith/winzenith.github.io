# Search Windows Update for available software/OS updates (non-driver). Outputs JSON array.
param(
    [int]$TimeoutSec = 120
)
$ErrorActionPreference = 'Stop'

try {
    $session = New-Object -ComObject Microsoft.Update.Session
    $searcher = $session.CreateUpdateSearcher()
    # Include IsHidden filter and exclude already downloaded hidden items; Timeout handled via Job
    $criteria = "IsInstalled=0 and IsHidden=0 and Type='Software'"
    $job = Start-Job -ScriptBlock {
        param($crit)
        $s = New-Object -ComObject Microsoft.Update.Session
        $searcher2 = $s.CreateUpdateSearcher()
        return $searcher2.Search($crit)
    } -ArgumentList $criteria
    $completed = Wait-Job $job -Timeout $TimeoutSec
    if (-not $completed) {
        try { Stop-Job $job -Force | Out-Null } catch {}
        try { Remove-Job $job -Force | Out-Null } catch {}
        Write-Error "Windows Update search timed out after $TimeoutSec seconds"
        exit 2
    }
    $result = Receive-Job $job
    Remove-Job $job -Force | Out-Null

    $updates = @()
    for ($i = 0; $i -lt $result.Updates.Count; $i++) {
        $u = $result.Updates.Item($i)
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
            version     = if ($kb -and $kb.Length -gt 0) { $kb } else { $u.LastDeploymentChangeDate.ToString('yyyy-MM-dd') }
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
