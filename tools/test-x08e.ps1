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
$mediaBeforeOutput = @(adb -s $deviceSerial shell media volume --stream 3 --get) -join "`n"
if ($LASTEXITCODE -ne 0 -or $mediaBeforeOutput -notmatch 'volume is (\d+) in range') {
    throw 'Unable to snapshot physical media volume.'
}
$mediaBefore = [int]$Matches[1]
$musicBefore = adb -s $deviceSerial shell dumpsys audio | Select-String 'STREAM_MUSIC:' -Context 0,1 | Select-Object -First 1
$mutedBefore = $musicBefore.Context.PostContext[0] -match 'Muted: true'
$brightnessBefore = (adb -s $deviceSerial shell settings get system screen_brightness).Trim()
$brightnessModeBefore = (adb -s $deviceSerial shell settings get system screen_brightness_mode).Trim()
$timeoutBefore = (adb -s $deviceSerial shell settings get system screen_off_timeout).Trim()
if ($brightnessModeBefore -notin @('0', '1') -or $brightnessBefore -notmatch '^\d+$' -or $timeoutBefore -notmatch '^\d+$') {
    throw 'Unable to snapshot display mode, brightness, and screen timeout safely.'
}
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

$safetyViolations = [System.Collections.Generic.List[string]]::new()
Push-Location $repositoryRoot
try {
    & .\gradlew.bat connectedX08eTestAndroidTest '-Pdormpanel.testBuildType=x08eTest' '-Pandroid.experimental.androidTest.builtin_test_platform=true'
    $gradleExitCode = $LASTEXITCODE
} finally {
    Pop-Location
    $mediaAfterOutput = @(adb -s $deviceSerial shell media volume --stream 3 --get) -join "`n"
    if ($mediaAfterOutput -notmatch 'volume is (\d+) in range') {
        $safetyViolations.Add('Could not read media volume after the suite.')
    } elseif ([int]$Matches[1] -ne $mediaBefore) {
        adb -s $deviceSerial shell media volume --stream 3 --set $mediaBefore | Out-Host
    }
    $mediaFinalOutput = @(adb -s $deviceSerial shell media volume --stream 3 --get) -join "`n"
    if ($mediaFinalOutput -match 'volume is (\d+) in range') {
        $mediaFinal = [int]$Matches[1]
    } else {
        $mediaFinal = -1
    }
    if ($mediaFinal -ne $mediaBefore) {
        $safetyViolations.Add("Media volume was not restored to $mediaBefore.")
    }
    $musicAfter = adb -s $deviceSerial shell dumpsys audio | Select-String 'STREAM_MUSIC:' -Context 0,1 | Select-Object -First 1
    if (($musicAfter.Context.PostContext[0] -match 'Muted: true') -ne $mutedBefore) {
        # A test failure must never leave daily playback muted. The media key toggles this stream.
        adb -s $deviceSerial shell input keyevent KEYCODE_VOLUME_MUTE
    }
    $musicFinal = adb -s $deviceSerial shell dumpsys audio | Select-String 'STREAM_MUSIC:' -Context 0,1 | Select-Object -First 1
    if (($musicFinal.Context.PostContext[0] -match 'Muted: true') -ne $mutedBefore) {
        $safetyViolations.Add('Media mute state could not be restored.')
    }
    $modeAfter = (adb -s $deviceSerial shell settings get system screen_brightness_mode).Trim()
    if ($modeAfter -ne $brightnessModeBefore) {
        $safetyViolations.Add("Screen brightness mode changed from $brightnessModeBefore to $modeAfter during the suite.")
        adb -s $deviceSerial shell settings put system screen_brightness_mode $brightnessModeBefore
    }
    if ((adb -s $deviceSerial shell settings get system screen_brightness_mode).Trim() -ne $brightnessModeBefore) {
        $safetyViolations.Add("Screen brightness mode could not be restored to $brightnessModeBefore.")
    }
    $brightnessAfter = (adb -s $deviceSerial shell settings get system screen_brightness).Trim()
    if ($brightnessModeBefore -eq '0') {
        if ($brightnessAfter -ne $brightnessBefore) {
            $safetyViolations.Add("Manual system brightness changed from $brightnessBefore to $brightnessAfter during the suite.")
            adb -s $deviceSerial shell settings put system screen_brightness $brightnessBefore
        }
        if ((adb -s $deviceSerial shell settings get system screen_brightness).Trim() -ne $brightnessBefore) {
            $safetyViolations.Add("Manual system brightness could not be restored to $brightnessBefore.")
        }
    } else {
        Write-Host "Automatic brightness: system value $brightnessBefore -> $brightnessAfter; no brightness value was written."
    }
    $timeoutAfter = (adb -s $deviceSerial shell settings get system screen_off_timeout).Trim()
    if ($timeoutAfter -ne $timeoutBefore) {
        adb -s $deviceSerial shell settings put system screen_off_timeout $timeoutBefore
        $safetyViolations.Add("System screen timeout changed from $timeoutBefore to $timeoutAfter during the suite; restoration attempted.")
    }
    if ((adb -s $deviceSerial shell settings get system screen_off_timeout).Trim() -ne $timeoutBefore) {
        $safetyViolations.Add("System screen timeout could not be restored to $timeoutBefore.")
    }
}

$after = @(adb -s $deviceSerial shell pm path $dailyPackage)
if ($LASTEXITCODE -ne 0 -or -not ($after -match '^package:')) {
    Write-Error "Daily-use package $dailyPackage is missing after the suite."
    exit 1
}
Write-Host "Verified daily-use package $dailyPackage remains installed."
if ($safetyViolations.Count -gt 0) {
    throw ($safetyViolations -join ' ')
}
if ($gradleExitCode -eq 0) {
    $manifestPath = Join-Path $repositoryRoot 'app\build\intermediates\packaged_manifests\x08eTestAndroidTest\processX08eTestAndroidTestManifest\AndroidManifest.xml'
    if (-not (Test-Path -LiteralPath $manifestPath)) {
        throw 'The x08eTest instrumentation manifest is missing after the suite.'
    }
    [xml]$manifest = Get-Content -LiteralPath $manifestPath -Raw
    $targetPackage = $manifest.manifest.instrumentation.GetAttribute('targetPackage', 'http://schemas.android.com/apk/res/android')
    if ($targetPackage -ne $testbedPackage) {
        throw "The instrumentation APK targeted $targetPackage instead of $testbedPackage."
    }
    Write-Host "Verified instrumentation APK targets $testbedPackage."
}
exit $gradleExitCode
