# Build a portable app image with jpackage (requires JDK 21+ with jpackage on PATH).
# Run from repo root after: mvn -q package
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$jar = Join-Path $root "target\basic-s-driver-update-1.0-SNAPSHOT-shaded.jar"
if (-not (Test-Path $jar)) {
    Write-Error "Shaded JAR not found. Run: mvn package"
    exit 1
}
$dest = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dest | Out-Null
& jpackage `
    --type app-image `
    --name SBasicDriverUpdater `
    --input (Join-Path $root "target") `
    --main-jar "basic-s-driver-update-1.0-SNAPSHOT-shaded.jar" `
    --main-class com.basicsdriverupdate.App `
    --dest $dest

$appImageRoot = Join-Path $dest "SBasicDriverUpdater"
if (-not (Test-Path $appImageRoot)) {
    Write-Error "App image not created at: $appImageRoot"
    exit 1
}
Write-Host "Packaged app image at $appImageRoot"
