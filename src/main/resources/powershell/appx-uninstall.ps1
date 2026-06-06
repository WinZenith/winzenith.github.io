param(
    [string]$PackageFullName
)

if ([string]::IsNullOrEmpty($PackageFullName)) {
    Write-Error "PackageFullName parameter is required"
    exit 1
}

try {
    # Attempt to remove for all users
    Remove-AppxPackage -Package $PackageFullName -AllUsers -ErrorAction Stop
    Write-Output "SUCCESS"
} catch {
    # Fallback to current user
    try {
        Remove-AppxPackage -Package $PackageFullName -ErrorAction Stop
        Write-Output "SUCCESS"
    } catch {
        Write-Error $_.Exception.Message
        exit 1
    }
}
