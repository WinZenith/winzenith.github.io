# Non-install smoke: build project and run driver enumeration script only.
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $script = Join-Path $root "src\main\resources\powershell\enumerate-devices.ps1"
    if (-not (Test-Path $script)) {
        Write-Error "Missing enumerate-devices.ps1"
        exit 1
    }
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $script 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Error $out
        exit $LASTEXITCODE
    }
    $json = $out | Out-String
    if ($json.Length -lt 10) {
        Write-Error "Enumeration returned empty output"
        exit 1
    }
    Write-Host "Smoke scan OK ($($json.Length) bytes JSON)"
} finally {
    Pop-Location
}
