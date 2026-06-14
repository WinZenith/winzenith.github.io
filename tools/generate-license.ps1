# WinZenith License Key Generator
# Usage:
#   .\generate-license.ps1 2027-06-14    (key with specific date)
#   .\generate-license.ps1 12            (12 months from now)
#   .\generate-license.ps1               (interactive mode)

param([string]$Expiry)

$JAVA = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe"
$JAVAC = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\javac.exe"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Compile if needed
if (-not (Test-Path "$ScriptDir\LicenseGenerator.class")) {
    Write-Host "Compiling LicenseGenerator..." -ForegroundColor Yellow
    & $JAVAC "$ScriptDir\LicenseGenerator.java"
}

# Run
if ($Expiry) {
    # Check if it's a number (months) or date
    if ($Expiry -match '^\d+$') {
        $months = [int]$Expiry
        $targetDate = (Get-Date).AddMonths($months).ToString("yyyy-MM-dd")
        & $JAVA -cp $ScriptDir LicenseGenerator $targetDate
    } else {
        & $JAVA -cp $ScriptDir LicenseGenerator $Expiry
    }
} else {
    & $JAVA -cp $ScriptDir LicenseGenerator
}
