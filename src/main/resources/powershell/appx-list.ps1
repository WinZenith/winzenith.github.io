try {
    $apps = Get-AppxPackage -AllUsers -ErrorAction Stop | Where-Object { -not $_.IsFramework -and -not $_.IsResourcePackage -and $_.InstallLocation }
} catch {
    $apps = Get-AppxPackage | Where-Object { -not $_.IsFramework -and -not $_.IsResourcePackage -and $_.InstallLocation }
}

$results = $apps | ForEach-Object {
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
    # Friendly names live in the package manifest; fall back to package identity fields.
    # Manifest DisplayName is often an ms-resource: reference (unresolvable here) — only
    # use literal values so we never display raw resource URIs.
    $displayName = $_.Name
    $displayPublisher = $_.Publisher
    try {
        $manifest = Get-AppxPackageManifest -Package $_.PackageFullName -ErrorAction Stop
        if ($manifest -and $manifest.Package -and $manifest.Package.Properties) {
            $pubDisplay = $manifest.Package.Properties.PublisherDisplayName
            if ($pubDisplay -and $pubDisplay -notlike "ms-resource*") { $displayPublisher = $pubDisplay }
        }
        if ($manifest -and $manifest.Package -and $manifest.Package.Applications) {
            $firstApp = @($manifest.Package.Applications.Application) | Select-Object -First 1
            if ($firstApp -and $firstApp.VisualElements) {
                $vn = $firstApp.VisualElements.DisplayName
                if ($vn -and $vn -notlike "ms-resource*") { $displayName = $vn }
            }
        }
    } catch {}
    [PSCustomObject]@{
        Name = $displayName
        PackageName = $_.Name
        PackageFullName = $_.PackageFullName
        Version = $_.Version
        Publisher = $displayPublisher
        PublisherId = $_.PublisherId
        InstallLocation = $_.InstallLocation
        InstallDate = $installDate
        InstalledSize = $sizeKB
    }
}
if ($null -eq $results) {
    "[]"
} else {
    @($results) | ConvertTo-Json -Depth 3
}
