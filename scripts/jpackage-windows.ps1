# Build WinZenith installer with jpackage (requires JDK 21+ with jpackage on PATH).
# Run from repo root after: mvn -q package
#
# Usage:
#   .\jpackage-windows.ps1              # Build app-image (portable)
#   .\jpackage-windows.ps1 -Msi        # Build MSI installer
#   .\jpackage-windows.ps1 -All        # Build both app-image and MSI

param(
    [switch]$Msi,
    [switch]$All
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$version = "1.0.0"
$jarName = "win-zenith-$version-shaded.jar"
$jar = Join-Path $root "target\$jarName"
if (-not (Test-Path $jar)) {
    Write-Error "Shaded JAR not found: $jar`nRun: mvn package"
    exit 1
}
$dest = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$iconPath = Join-Path $root "src\main\resources\app.ico"
$licensePath = Join-Path $root "LICENSE.txt"

$jpackage = $null
$jpackagePaths = @(
    "jpackage",
    "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\jpackage.exe"
)
foreach ($p in $jpackagePaths) {
    if (Get-Command $p -ErrorAction SilentlyContinue) {
        $jpackage = $p
        break
    }
}
if (-not $jpackage) {
    Write-Error "jpackage not found. Requires JDK 14+ with jpackage."
    exit 1
}

# Common jpackage arguments
$commonArgs = @(
    "--name", "WinZenith",
    "--input", (Join-Path $root "target"),
    "--main-jar", $jarName,
    "--main-class", "com.sbtools.App",
    "--dest", $dest,
    "--app-version", $version
)

if (Test-Path $iconPath) {
    $commonArgs += "--icon"
    $commonArgs += $iconPath
}

if (Test-Path $licensePath) {
    $commonArgs += "--license-file"
    $commonArgs += $licensePath
}

$commonArgs += "--about-url"
$commonArgs += "https://winzenith.github.io"

if ($All -or (-not $Msi)) {
    Write-Host "Building app-image..."
    & $jpackage --type app-image @commonArgs
    $appImageRoot = Join-Path $dest "WinZenith"
    if (Test-Path $appImageRoot) {
        Write-Host "App image created at: $appImageRoot"
    } else {
        Write-Error "App image creation failed"
        exit 1
    }
}

if ($All -or $Msi) {
    Write-Host "Building MSI installer..."
    $msiArgs = $commonArgs + @(
        "--type", "msi",
        "--win-menu",
        "--win-menu-group", "WinZenith",
        "--win-shortcut",
        "--win-per-user-install",
        "--win-dir-chooser"
    )
    & $jpackage @msiArgs
    $msiFile = Join-Path $dest "WinZenith-$version.msi"
    if (Test-Path $msiFile) {
        Write-Host "MSI installer created at: $msiFile"
    } else {
        Write-Error "MSI creation failed"
        exit 1
    }
}

Write-Host "Build complete!"
