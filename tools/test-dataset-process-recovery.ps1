param([Parameter(Mandatory = $true)][string]$Serial, [string]$OutputDirectory, [switch]$RebootAfterCommit)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $root 'artifacts/pr47/process' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
if ((& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') { throw 'This process-death fixture only runs on an explicitly selected emulator.' }
$package = 'com.dormpanel.schedule.core.test'
& $adb -s $Serial install -r (Join-Path $root 'schedule-core/build/outputs/apk/androidTest/debug/schedule-core-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed' }
foreach ($phase in @('inspect', 'prepare', 'commit', 'preferences')) {
    $out = Join-Path $OutputDirectory "$phase.txt"
    $err = Join-Path $OutputDirectory "$phase-error.txt"
    $arguments = "-s $Serial shell am instrument -w -e class com.dormpanel.app.schedule.ScheduleDatasetProcessRecoveryTest -e migrationPhase $phase $package/androidx.test.runner.AndroidJUnitRunner"
    $process = Start-Process -FilePath $adb -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    $found = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $marker = & $adb -s $Serial shell run-as $package cat files/pr47-process-boundary 2>$null
        if ($marker -eq $phase) { $found = $true; break }
        if ($process.HasExited) { break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $found) { throw "Durable $phase barrier was not reached; inspect $out" }
    & $adb -s $Serial shell am force-stop $package
    if (-not $process.WaitForExit(15000)) { throw 'Instrumentation client did not exit after force-stop' }
    Write-Output "PASS: process force-stop after durable $phase; next phase uses a fresh process."
    if ($phase -eq 'commit' -and $RebootAfterCommit) {
        & $adb -s $Serial reboot
        $booted = $false
        for ($attempt = 0; $attempt -lt 120; $attempt++) {
            Start-Sleep -Milliseconds 500
            $state = & $adb -s $Serial shell getprop sys.boot_completed 2>$null
            if ($state -eq '1') { $booted = $true; break }
        }
        if (-not $booted) { throw 'Emulator did not finish reboot' }
        Write-Output 'PASS: normal emulator reboot after Room commit and before SharedPreferences update.'
    }
}
$verify = Join-Path $OutputDirectory 'verify.txt'
& $adb -s $Serial shell am instrument -w -e class com.dormpanel.app.schedule.ScheduleDatasetProcessRecoveryTest -e migrationPhase verify "$package/androidx.test.runner.AndroidJUnitRunner" > $verify
if (-not ([IO.File]::ReadAllText($verify).Contains('OK (1 test)'))) { throw "Recovery verification failed; inspect $verify" }
Write-Output 'PASS: original business state / Writer / recovery evidence retained; only target witnesses active; normal target sync resumed.'
