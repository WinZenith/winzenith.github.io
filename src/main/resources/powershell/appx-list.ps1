$apps = Get-AppxPackage -AllUsers | Where-Object { -not $_.IsFramework -and -not $_.IsResourcePackage -and $_.InstallLocation } | Select-Object Name, PackageFullName, Version, Publisher, PublisherId, InstallLocation
if ($null -eq $apps) {
    "[]"
} else {
    @($apps) | ConvertTo-Json -Depth 3
}
