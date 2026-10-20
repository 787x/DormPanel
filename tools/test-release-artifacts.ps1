param(
    [string]$Directory = (Join-Path $PSScriptRoot '..\artifacts\release\0.1.2-rc.1'),
    [string]$BuildToolsDirectory,
    [string]$PreviousX08eApk
)
# Real Android tools and real APK bytes. No production signing secrets needed.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-validation.ps1')
$tools = Get-AndroidReleaseTools $BuildToolsDirectory
Assert-ReleaseDirectory $Directory $tools
$fixture = Join-Path $PSScriptRoot ('..\artifacts\pr33\negative-' + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
$source = Join-Path $Directory "DormPanel-X08E-$releaseVersion.apk"
$apk = Join-Path $fixture 'negative.apk'
$count = 0
function Expect-Rejected([string]$Label, [scriptblock]$Action) {
    $message = $null
    try { & $Action } catch { $message = $_.Exception.Message }
    if (-not $message) { throw "Guard incorrectly accepted: $Label" }
    Write-Host "PASS rejects $Label : $message"
    $script:count++
}
Expect-Rejected 'missing APK' { Assert-ReleaseApk $apk $releaseApps[0] $tools }
Expect-Rejected 'wrong application APK' {
    Assert-ReleaseApk (Join-Path $Directory "DormPanel-Phone-$releaseVersion.apk") $releaseApps[0] $tools
}
if ($PreviousX08eApk) {
    Expect-Rejected 'previous signed version' { Assert-ReleaseApk $PreviousX08eApk $releaseApps[0] $tools }
}
Copy-Item -LiteralPath $source -Destination $apk
Set-Content -LiteralPath "$apk.sha256" -Value (('0' * 64) + '  negative.apk') -Encoding ascii
Expect-Rejected 'wrong SHA-256' { Assert-ReleaseSidecar $apk }
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$apk.sha256" -Value "$hash  wrong.apk" -Encoding ascii
Expect-Rejected 'wrong sidecar filename' { Assert-ReleaseSidecar $apk }

# Repack without JAR signatures; ZipArchive emits no APK signing block.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$unsigned = Join-Path $fixture 'unsigned.apk'
$inputZip = [IO.Compression.ZipFile]::OpenRead($source)
$outputZip = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($entry in $inputZip.Entries) {
        if ($entry.FullName -match '^META-INF/.*\.(SF|RSA|DSA|EC|MF)$') { continue }
        $copy = $outputZip.CreateEntry($entry.FullName)
        $reader = $entry.Open(); $writer = $copy.Open()
        try { $reader.CopyTo($writer) } finally { $reader.Dispose(); $writer.Dispose() }
    }
} finally { $inputZip.Dispose(); $outputZip.Dispose() }
Expect-Rejected 'unsigned APK' { Assert-ReleaseApk $unsigned $releaseApps[0] $tools }

# Disposable test identity only, never an official artifact or a production key.
$keytool = (Get-Command keytool -ErrorAction Stop).Source
$testKey = Join-Path $fixture 'negative-fixture.jks'
& $keytool -genkeypair -keystore $testKey -alias negative-fixture -storepass fixture-only -keypass fixture-only -dname 'CN=DormPanel Negative Fixture' -keyalg RSA -validity 1 2>&1 | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Could not generate disposable test identity.' }
$wrongSigner = Join-Path $fixture 'wrong-signer.apk'
Invoke-AndroidReleaseTool $tools.Signer @('sign', '--ks', $testKey, '--ks-key-alias', 'negative-fixture', '--ks-pass', 'pass:fixture-only', '--key-pass', 'pass:fixture-only', '--out', $wrongSigner, $unsigned) | Out-Host
Expect-Rejected 'valid APK with wrong certificate' { Assert-ReleaseApk $wrongSigner $releaseApps[0] $tools }

# Run the actual build entry point in an isolated fake repository. A no-op
# successful Gradle must not allow pre-existing valid APKs to become candidates.
$fakeRoot = Join-Path $fixture 'fake repository'
$fakeTools = Join-Path $fakeRoot 'tools'
New-Item -ItemType Directory -Path $fakeTools -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'build-release.ps1'), (Join-Path $PSScriptRoot 'release-validation.ps1') -Destination $fakeTools
Set-Content -LiteralPath (Join-Path $fakeRoot 'gradlew.bat') -Encoding ascii -Value "@echo off`r`nexit /b 0"
foreach ($app in $releaseApps) {
    $stale = Join-Path $fakeRoot "$($app.Module)\build\outputs\apk\release"
    New-Item -ItemType Directory -Path $stale -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $Directory "DormPanel-$($app.Device)-$releaseVersion.apk") -Destination (Join-Path $stale "$($app.Module)-release.apk")
}
$buildLog = & (Get-Process -Id $PID).Path -NoProfile -File (Join-Path $fakeTools 'build-release.ps1') -SkipTests -Keystore $testKey -KeyAlias negative-fixture -StorePassword fixture-only -KeyPassword fixture-only -BuildToolsDirectory (Split-Path $tools.Signer) 2>&1
if ($LASTEXITCODE -eq 0 -or ($buildLog -join "`n") -notmatch 'Missing or empty release APK') { throw 'No-op Gradle did not fail at missing artifact gate.' }
if (Test-Path -LiteralPath (Join-Path $fakeRoot "artifacts\release\$releaseVersion")) { throw 'Failed build exposed final candidates.' }
$count++
Write-Host 'PASS rejects stale artifacts after successful no-op Gradle'
Write-Host "$count native artifact rejection checks passed. Disposable fixtures remain ignored at $fixture"
