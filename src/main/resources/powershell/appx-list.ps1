$apps = Get-AppxPackage -AllUsers | Where-Object { -not $_.IsFramework -and -not $_.IsResourcePackage -and $_.InstallLocation } | ForEach-Object {
    $installDate = ""
    $sizeKB = 0
    if ($_.InstallLocation -and (Test-Path $_.InstallLocation)) {
        try {
            $folder = Get-Item $_.InstallLocation
            $installDate = $folder.LastWriteTime.ToString("yyyyMMdd")
            $size = (Get-ChildItem $_.InstallLocation -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
            if ($size) { $sizeKB = [math]::Round($size / 1024) }
        } catch {}
    }
    [PSCustomObject]@{
        Name = $_.Name
        PackageFullName = $_.PackageFullName
        Version = $_.Version
        Publisher = $_.Publisher
        PublisherId = $_.PublisherId
        InstallLocation = $_.InstallLocation
        InstallDate = $installDate
        InstalledSize = $sizeKB
    }
}
if ($null -eq $apps) {
    "[]"
} else {
    @($apps) | ConvertTo-Json -Depth 3
}
