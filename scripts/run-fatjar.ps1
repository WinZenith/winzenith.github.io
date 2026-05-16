$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$jar = Join-Path $root "target\basic-s-driver-update-1.0-SNAPSHOT-shaded.jar"
if (-not (Test-Path $jar)) {
    Write-Error "Shaded JAR not found. Run: mvn package"
    exit 1
}

# JavaFX is not part of the JDK; native libs must stay on the module path.
$javafxVersion = "21.0.5"
$javafxPlatform = "win"
$repo = Join-Path $env:USERPROFILE ".m2\repository\org\openjfx"
$modulePath = @(
    (Join-Path $repo "javafx-controls\$javafxVersion\javafx-controls-$javafxVersion-$javafxPlatform.jar"),
    (Join-Path $repo "javafx-graphics\$javafxVersion\javafx-graphics-$javafxVersion-$javafxPlatform.jar"),
    (Join-Path $repo "javafx-base\$javafxVersion\javafx-base-$javafxVersion-$javafxPlatform.jar")
) -join [IO.Path]::PathSeparator

foreach ($fxJar in $modulePath.Split([IO.Path]::PathSeparator)) {
    if (-not (Test-Path $fxJar)) {
        Write-Error "JavaFX JAR not found: $fxJar`nRun: mvn package (or open the project in the IDE so Maven downloads dependencies)"
        exit 1
    }
}

& java --enable-native-access=javafx.graphics --module-path $modulePath --add-modules javafx.controls -jar $jar @args
