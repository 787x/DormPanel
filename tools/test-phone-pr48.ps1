param([string]$Serial = '210b601e', [string]$Classes = 'com.dormpanel.phone.PhoneTimetableTest')
$ErrorActionPreference = 'Stop'
# Isolated testbed only. Never installs, clears or modifies the daily package.
& adb -s $Serial install -r phone-app/build/outputs/apk/x08eTest/phone-app-x08eTest.apk
if ($LASTEXITCODE) { throw 'Testbed installation failed' }
& adb -s $Serial install -r phone-app/build/outputs/apk/androidTest/x08eTest/phone-app-x08eTest-androidTest.apk
if ($LASTEXITCODE) { throw 'Test APK installation failed' }
# Freshly replaced test APK can remain stopped/invisible on MIUI. Resolve the actual
# exported provider from shell before instrumentation, then setup asserts it again.
$result = & adb -s $Serial shell content query --uri content://com.dormpanel.timetable.testbed.test.fixtures/wakeup.ics
$result
if ($LASTEXITCODE -or ($result -join '') -notmatch '_display_name=wakeup.ics') { throw 'Fixture provider preflight failed' }
$testResult = & adb -s $Serial shell am instrument -w -e pr35RootUi true -e class $Classes com.dormpanel.timetable.testbed.test/androidx.test.runner.AndroidJUnitRunner
$testResult
if ($LASTEXITCODE -or ($testResult -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw 'Instrumentation failed or did not report a passing JUnit summary' }
