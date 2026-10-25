param(
    [switch]$Release
)

# Builds the APK and copies it to dist/ for easy pickup.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if (-not $env:JAVA_HOME) {
        $candidate = "C:\Program Files\Android\Android Studio\jbr"
        if (Test-Path $candidate) { $env:JAVA_HOME = $candidate }
    }
    if (-not $env:ANDROID_HOME) {
        $candidate = Join-Path $env:LOCALAPPDATA "Android\Sdk"
        if (Test-Path $candidate) { $env:ANDROID_HOME = $candidate }
    }
    if (-not $env:JAVA_HOME) { throw "JAVA_HOME is not set (JDK 17+ required)." }
    if (-not $env:ANDROID_HOME) { throw "ANDROID_HOME is not set." }

    $task = if ($Release) { ":app:assembleRelease" } else { ":app:assembleDebug" }
    & ".\gradlew.bat" $task --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build failed." }

    $variant = if ($Release) { "release" } else { "debug" }
    $apk = Join-Path $root "app\build\outputs\apk\$variant\app-$variant.apk"
    if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

    $dist = Join-Path $root "dist"
    New-Item -ItemType Directory -Force $dist | Out-Null
    $target = Join-Path $dist "mobile-agent-$variant.apk"
    Copy-Item $apk $target -Force
    Write-Host "APK: $target"
} finally {
    Pop-Location
}
