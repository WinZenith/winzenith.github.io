# Run from IDE-compiled classes (mvn compile / Build Project) without packaging.
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$classes = Join-Path $root "target\classes"
if (-not (Test-Path (Join-Path $classes "com\basicsdriverupdate\App.class"))) {
    Write-Error "Compiled classes not found. Build the project in IntelliJ or run: mvn compile"
    exit 1
}

$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$repo = Join-Path $env:USERPROFILE ".m2\repository"
$fx = "21.0.5"
$platform = "win"
$modulePath = @(
    (Join-Path $repo "org\openjfx\javafx-controls\$fx\javafx-controls-$fx-$platform.jar"),
    (Join-Path $repo "org\openjfx\javafx-graphics\$fx\javafx-graphics-$fx-$platform.jar"),
    (Join-Path $repo "org\openjfx\javafx-base\$fx\javafx-base-$fx-$platform.jar")
) -join [IO.Path]::PathSeparator

$jackson = "2.18.2"
$classpath = @(
    $classes,
    (Join-Path $repo "com\fasterxml\jackson\core\jackson-databind\$jackson\jackson-databind-$jackson.jar"),
    (Join-Path $repo "com\fasterxml\jackson\core\jackson-core\$jackson\jackson-core-$jackson.jar"),
    (Join-Path $repo "com\fasterxml\jackson\core\jackson-annotations\$jackson\jackson-annotations-$jackson.jar"),
    (Join-Path $repo "com\fasterxml\jackson\datatype\jackson-datatype-jsr310\$jackson\jackson-datatype-jsr310-$jackson.jar"
)) -join [IO.Path]::PathSeparator

& $jdk `
    --enable-native-access=javafx.graphics `
    --module-path $modulePath `
    --add-modules javafx.controls `
    -cp $classpath `
    com.basicsdriverupdate.App `
    @args
