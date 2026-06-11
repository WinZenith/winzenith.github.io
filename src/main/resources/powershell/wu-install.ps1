# Install Windows Update by UpdateID. Pass UpdateIDs as remaining arguments or -UpdateIds.
param(
    [string[]]$UpdateIds = @()
)
$ErrorActionPreference = 'Stop'
if ($UpdateIds.Count -eq 0) {
    $UpdateIds = @($args)
}
if ($UpdateIds.Count -eq 0) {
    Write-Error 'No UpdateIDs provided'
    exit 1
}

try {
    $session = New-Object -ComObject Microsoft.Update.Session
    $searcher = $session.CreateUpdateSearcher()
    $toInstall = New-Object -ComObject Microsoft.Update.UpdateColl
    foreach ($id in $UpdateIds) {
        $criteria = "UpdateID='$id'"
        $result = $searcher.Search($criteria)
        if ($result.Updates.Count -gt 0) {
            [void]$toInstall.Add($result.Updates.Item(0))
        }
    }
    if ($toInstall.Count -eq 0) {
        Write-Error 'No matching updates found'
        exit 1
    }

    # Download phase
    $downloader = $session.CreateUpdateDownloader()
    $downloader.Updates = $toInstall
    $downloadResult = $downloader.Download()
    if ($downloadResult.ResultCode -ne 2) {
        Write-Error "Download failed: $($downloadResult.ResultCode)"
        exit 2
    }

    # Install phase
    $installer = $session.CreateUpdateInstaller()
    $installer.Updates = $toInstall
    $installResult = $installer.Install()

    @{
        resultCode    = [int]$installResult.ResultCode
        rebootRequired = $installResult.RebootRequired
        installed     = $installResult.GetUpdateResult(0).ResultCode
    } | ConvertTo-Json -Compress
} catch {
    Write-Error "Windows Update install failed: $_"
    exit 3
}
