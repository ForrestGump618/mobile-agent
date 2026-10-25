param(
    [string]$Destination = "$env:TEMP\mobile-agent-test"
)

# Runs the JVM unit tests in an ASCII path.
# Reason: on Windows, Gradle passes the test-worker classpath via an @argfile whose non-ASCII
# entries are decoded with the platform charset, so projects under a non-ASCII path fail with
# ClassNotFoundException / initializationError before any test executes.

$ErrorActionPreference = "Stop"
$source = Split-Path -Parent $PSScriptRoot

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

Write-Host "Copying sources to $Destination ..."
if (Test-Path $Destination) { Remove-Item $Destination -Recurse -Force }
New-Item -ItemType Directory -Force $Destination | Out-Null
robocopy $source $Destination /E /XD build .gradle .kotlin /NFL /NDL /NJH /NJS /NP | Out-Null

Write-Host "Running unit tests ..."
Push-Location $Destination
try {
    & ".\gradlew.bat" test --console=plain
    $exit = $LASTEXITCODE
} finally {
    Pop-Location
}

$results = Get-ChildItem -Recurse -Path $Destination -Filter "TEST-*.xml" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match "testDebugUnitTest" }
$total = 0; $failed = 0
foreach ($file in $results) {
    $xml = [xml](Get-Content $file.FullName -Raw)
    $total += [int]$xml.testsuite.tests
    $failed += [int]$xml.testsuite.failures + [int]$xml.testsuite.errors
}
Write-Host ""
Write-Host "Debug unit tests: $total total, $failed failure(s). Exit code: $exit"
exit $exit
