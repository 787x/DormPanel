param()

$ErrorActionPreference = 'Stop'
$dailyPackage = 'com.dormpanel.app'
$testbedPackage = 'com.dormpanel.app.testbed'
$repositoryRoot = Split-Path -Parent $PSScriptRoot

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw 'ADB is not on PATH.'
}

$deviceLines = @(adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '^\S+\s+\S+' })
if ($LASTEXITCODE -ne 0 -or $deviceLines.Count -ne 1 -or $deviceLines[0] -notmatch '^(\S+)\s+device$') {
    throw 'Connect exactly one authorized X08E ADB device before running the physical suite.'
}
$deviceSerial = $Matches[1]
$model = (adb -s $deviceSerial shell getprop ro.product.model).Trim()
$api = (adb -s $deviceSerial shell getprop ro.build.version.sdk).Trim()
$size = (adb -s $deviceSerial shell wm size | Select-String 'Physical size:').ToString().Trim()
if ($model -ne 'X08E' -or $api -ne '28' -or $size -ne 'Physical size: 1280x800') {
    throw "Expected X08E, API 28, 1280x800; found model=$model API=$api $size."
}

$before = @(adb -s $deviceSerial shell pm path $dailyPackage)
if ($LASTEXITCODE -ne 0 -or -not ($before -match '^package:')) {
    throw "Daily-use package $dailyPackage must be installed before testing."
}
Write-Host "Verified X08E and installed daily-use package $dailyPackage."
Write-Host "Running the complete $testbedPackage instrumentation suite."

Push-Location $repositoryRoot
try {
    & .\gradlew.bat connectedX08eTestAndroidTest '-Pdormpanel.testBuildType=x08eTest' '-Pandroid.experimental.androidTest.builtin_test_platform=true'
    $gradleExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

$after = @(adb -s $deviceSerial shell pm path $dailyPackage)
if ($LASTEXITCODE -ne 0 -or -not ($after -match '^package:')) {
    Write-Error "Daily-use package $dailyPackage is missing after the suite."
    exit 1
}
Write-Host "Verified daily-use package $dailyPackage remains installed."
exit $gradleExitCode
