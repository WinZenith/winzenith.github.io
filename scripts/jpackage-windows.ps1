# Build WinZenith installer with jpackage (requires JDK 14+ with jpackage).
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
$version = "1.1.0"
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
$javafxVersion = "21.0.5"

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

# Derive JDK bin dir from jpackage path to copy java.exe/javaw.exe later
$jpackageResolved = (Get-Command $jpackage -ErrorAction SilentlyContinue).Source
if (-not $jpackageResolved) { $jpackageResolved = $jpackage }
$jpackageDir = Split-Path $jpackageResolved -Parent
$jdkBinDir = $jpackageDir

# Copy JavaFX JARs to target/ so jpackage includes them in app directory
$m2Base = Join-Path $env:USERPROFILE ".m2\repository\org\openjfx"
$javafxJars = @(
    "javafx-base\$javafxVersion\javafx-base-$javafxVersion-win.jar",
    "javafx-controls\$javafxVersion\javafx-controls-$javafxVersion-win.jar",
    "javafx-graphics\$javafxVersion\javafx-graphics-$javafxVersion-win.jar"
)
foreach ($jarRel in $javafxJars) {
    $src = Join-Path $m2Base $jarRel
    $destJar = Join-Path (Join-Path $root "target") (Split-Path $jarRel -Leaf)
    if ((Test-Path $src) -and (-not (Test-Path $destJar))) {
        Copy-Item -Path $src -Destination $destJar
        Write-Host "Copied $(Split-Path $jarRel -Leaf) to target/"
    }
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

if ($All -or (-not $Msi)) {
    $appImageRoot = Join-Path $dest "WinZenith"
    if (Test-Path $appImageRoot) {
        Remove-Item -Recurse -Force $appImageRoot
        Write-Host "Removed old app-image directory"
    }
    Write-Host "Building app-image..."
    & $jpackage --type app-image @commonArgs
    $appImageRoot = Join-Path $dest "WinZenith"
    if (Test-Path $appImageRoot) {
        Write-Host "App image created at: $appImageRoot"
    } else {
        Write-Error "App image creation failed"
        exit 1
    }

    # JDK 17 jpackage bug workaround: copy missing java.exe/javaw.exe into runtime
    $runtimeBin = Join-Path $appImageRoot "runtime\bin"
    foreach ($exe in @("java.exe", "javaw.exe")) {
        $destExe = Join-Path $runtimeBin $exe
        if (-not (Test-Path $destExe)) {
            $srcExe = Join-Path $jdkBinDir $exe
            if (Test-Path $srcExe) {
                Copy-Item -Path $srcExe -Destination $destExe -Force
                Write-Host "Copied missing $exe from JDK to runtime"
            } else {
                Write-Warning "Could not find $exe in JDK bin: $jdkBinDir"
            }
        }
    }

    # JDK 17 jpackage bug workaround: rewrite .cfg to fix --module-path (split on spaces)
    # and write without UTF-8 BOM (jpackage launcher doesn't handle BOM)
    $cfgPath = Join-Path $appImageRoot "app\WinZenith.cfg"
    if (Test-Path $cfgPath) {
        $cfgContent = @"
[Application]
app.classpath=`$APPDIR\win-zenith-$version-shaded.jar
app.mainclass=com.sbtools.App

[JavaOptions]
java-options=-Djpackage.app-version=$version
java-options=--module-path=`$APPDIR\javafx-base-$javafxVersion-win.jar;`$APPDIR\javafx-controls-$javafxVersion-win.jar;`$APPDIR\javafx-graphics-$javafxVersion-win.jar
java-options=--add-modules=javafx.controls
"@
        [System.IO.File]::WriteAllText($cfgPath, $cfgContent, [System.Text.UTF8Encoding]::new($false))
        Write-Host "Fixed WinZenith.cfg with JavaFX module-path (no BOM)"
    }

    # Clean up junk files copied from target/ into app/ (Maven build artifacts, Launch4j exe, etc.)
    $appDir = Join-Path $appImageRoot "app"
    foreach ($junkDir in @("classes", "generated-sources", "maven-archiver", "maven-status", "test-classes")) {
        $junkPath = Join-Path $appDir $junkDir
        if (Test-Path $junkPath) { Remove-Item -Recurse -Force $junkPath -ErrorAction SilentlyContinue }
    }
    foreach ($junkFile in @("win-zenith-$version.jar")) {
        $junkPath = Join-Path $appDir $junkFile
        if (Test-Path $junkPath) { Remove-Item -Force $junkPath -ErrorAction SilentlyContinue }
    }
    Write-Host "Cleaned app directory of build artifacts"
}

if ($All -or $Msi) {
    Write-Host "Building MSI installer..."
    # Build MSI from the app-image so all fixes (javaw.exe, .cfg, module-path) are preserved
    $appImageRoot = Join-Path $dest "WinZenith"
    $msiArgs = @(
        "--type", "msi",
        "--name", "WinZenith",
        "--dest", $dest,
        "--app-version", $version,
        "--win-menu",
        "--win-menu-group", "WinZenith",
        "--win-shortcut",
        "--win-per-user-install",
        "--win-dir-chooser",
        "--about-url", "https://winzenith.github.io"
    )
    if (Test-Path $iconPath) {
        $msiArgs += "--icon"
        $msiArgs += $iconPath
    }
    if (Test-Path $licensePath) {
        $msiArgs += "--license-file"
        $msiArgs += $licensePath
    }
    if (Test-Path $appImageRoot) {
        # Use pre-built app-image (includes all fixes)
        $msiArgs += "--app-image"
        $msiArgs += $appImageRoot
        Write-Host "Building MSI from fixed app-image..."
    } else {
        Write-Warning "App-image not found at $appImageRoot, building MSI from scratch"
        $msiArgs += "--input"
        $msiArgs += (Join-Path $root "target")
        $msiArgs += "--main-jar"
        $msiArgs += $jarName
        $msiArgs += "--main-class"
        $msiArgs += "com.sbtools.App"
    }
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
