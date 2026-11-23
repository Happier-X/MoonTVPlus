$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $root "android-host\app\src\main\assets\nsh"

Write-Host "Building TSX bundle -> $assets"
nsh build (Join-Path $root "embed\App.tsx") --release --out $assets
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Building Android host"
Push-Location (Join-Path $root "android-host")
try {
    if (-not $env:JAVA_HOME) {
        $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    }
    .\gradlew.bat assembleDebug --no-daemon
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "APK: app\build\outputs\apk\debug\app-debug.apk"
} finally {
    Pop-Location
}


