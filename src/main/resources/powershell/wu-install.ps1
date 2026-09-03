# Install Windows Update by UpdateID. Pass UpdateIDs as remaining arguments or -UpdateIds.
param(
    [string[]]$UpdateIds = @()
)
$ErrorActionPreference = 'Stop'
if ($UpdateIds.Count -eq 0) {
    $UpdateIds = @($args)
}
if ($UpdateIds.Count -eq 0) {
    [Console]::Error.WriteLine('No UpdateIDs provided')
    exit 1
}

try {
    $session = New-Object -ComObject Microsoft.Update.Session
    $searcher = $session.CreateUpdateSearcher()
    $toInstall = New-Object -ComObject Microsoft.Update.UpdateColl
    Write-Output "Searching for matching Windows Update(s)..."
    foreach ($id in $UpdateIds) {
        $criteria = "UpdateID='$id'"
        $result = $searcher.Search($criteria)
        if ($result.Updates.Count -gt 0) {
            [void]$toInstall.Add($result.Updates.Item(0))
        }
    }
    if ($toInstall.Count -eq 0) {
        [Console]::Error.WriteLine('No matching updates found')
        exit 1
    }

    # Download phase (progress lines are consumed by the Java streaming UI; the final
    # JSON object on the last line carries the machine-readable result).
    Write-Output "Downloading update(s)... (this can take a long time for cumulative updates)"
    $downloader = $session.CreateUpdateDownloader()
    $downloader.Updates = $toInstall
    $downloadResult = $downloader.Download()
    if ($downloadResult.ResultCode -ne 2) {
        [Console]::Error.WriteLine("Download failed: ResultCode=$([int]$downloadResult.ResultCode)")
        exit 2
    }
    Write-Output "Download complete. Installing update(s)... (do not close; this can take a long time)"

    # Install phase
    $installer = $session.CreateUpdateInstaller()
    $installer.Updates = $toInstall
    $installResult = $installer.Install()

    @{
        resultCode     = [int]$installResult.ResultCode
        rebootRequired = [bool]$installResult.RebootRequired
        installed      = [int]$installResult.GetUpdateResult(0).ResultCode
    } | ConvertTo-Json -Compress

    if ($installResult.ResultCode -ne 2) {
        [Console]::Error.WriteLine("Windows Update install failed: ResultCode=$([int]$installResult.ResultCode)")
        exit 4
    }
} catch {
    [Console]::Error.WriteLine("Windows Update install failed: $_")
    exit 3
}
