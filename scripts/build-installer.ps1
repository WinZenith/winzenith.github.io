# Full build pipeline: Maven package + jpackage MSI installer
# Run from repo root
#
# Prerequisites:
#   - JDK 21+ on PATH (for jpackage)
#   - Maven on PATH (or use IntelliJ's bundled Maven)
#
# Usage:
#   .\build-installer.ps1              # Full build
#   .\build-installer.ps1 -SkipMaven  # Skip Maven, only run jpackage

param(
    [switch]$SkipMaven
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent

# Find Maven
$mvn = $null
$mavenPaths = @(
    "mvn",
    "mvn.cmd",
    "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd",
    "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.2\plugins\maven\lib\maven3\bin\mvn.cmd"
)
foreach ($p in $mavenPaths) {
    if (Get-Command $p -ErrorAction SilentlyContinue) {
        $mvn = $p
        break
    }
}
if (-not $mvn) {
    Write-Error "Maven not found. Add mvn to PATH or install Maven."
    exit 1
}

# Step 1: Maven package
if (-not $SkipMaven) {
    Write-Host "=== Step 1: Maven package ===" -ForegroundColor Cyan
    & $mvn clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Maven build failed"
        exit 1
    }
    Write-Host "Maven package complete" -ForegroundColor Green
} else {
    Write-Host "Skipping Maven build" -ForegroundColor Yellow
}

# Step 2: jpackage MSI
Write-Host "=== Step 2: jpackage MSI ===" -ForegroundColor Cyan
$script = Join-Path $PSScriptRoot "jpackage-windows.ps1"
& $script -Msi
if ($LASTEXITCODE -ne 0) {
    Write-Error "jpackage MSI failed"
    exit 1
}

Write-Host ""
Write-Host "=== Build Complete ===" -ForegroundColor Green
Write-Host "MSI installer: dist\WinZenith-1.0.5.msi"
