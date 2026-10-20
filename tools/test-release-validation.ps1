# Deterministic guard tests; native output is mocked here, not cryptographic proof.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-validation.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('DormPanel release gates ' + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$apk = Join-Path $fixture 'fixture.apk'
Set-Content -LiteralPath $apk -Value 'guard fixture' -Encoding ascii
$tools = @{ Signer = 'mock-signer'; Aapt = 'mock-aapt' }
$script:case = ''
$script:currentApp = $null
$script:count = 0
function Invoke-AndroidReleaseTool([string]$Tool, [string[]]$ToolArguments) {
    if ($script:case -eq 'native failure') { throw 'Native tool failed' }
    if ($Tool -eq 'mock-signer') {
        if ($script:case -eq 'unsigned') { throw 'Does not verify' }
        if ($script:case -eq 'wrong signer') { return ('Signer #1 certificate SHA-256 digest: ' + ('0' * 64)) }
        $line = "V2 Signer #1 certificate SHA-256 digest: $expectedSigner"
        if ($script:case -eq 'multiple signers') { return "$line`n$line" }
        if ($script:case -eq 'no signer') { return 'Verifies' }
        return $line
    }
    $app = $script:currentApp
    $badging = "package: name='$($app.Package)' versionCode='$($app.Code)' versionName='$releaseVersion'`nsdkVersion:'28'`ntargetSdkVersion:'37'`n"
    switch ($script:case) {
        'modern minSdk label' { $badging = $badging.Replace("sdkVersion:'28'", "minSdkVersion:'28'") }
        'wrong package' { $badging = $badging.Replace($app.Package, 'other.package') }
        'wrong code' { $badging = $badging.Replace("versionCode='$($app.Code)'", "versionCode='999'") }
        'wrong name' { $badging = $badging.Replace($releaseVersion, '0.1.1') }
        'debuggable' { $badging += 'application-debuggable' }
        'wrong minSdk' { $badging = $badging.Replace("sdkVersion:'28'", "sdkVersion:'29'") }
        'wrong targetSdk' { $badging = $badging.Replace("targetSdkVersion:'37'", "targetSdkVersion:'36'") }
        'missing SDK' { $badging = $badging.Replace("sdkVersion:'28'", '') }
        'missing package' { $badging = '' }
    }
    return $badging
}
function Expect-Rejected([string]$Label, [scriptblock]$Action) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw "Guard incorrectly accepted: $Label" }
    $script:count++
    Write-Host "PASS rejects $Label"
}
try {
    foreach ($app in $releaseApps) {
        $script:currentApp = $app
        $script:case = 'valid'
        Assert-ReleaseApk $apk $app $tools
        $script:count++
        $script:case = 'modern minSdk label'
        Assert-ReleaseApk $apk $app $tools
        $script:count++
        foreach ($badCase in @('native failure', 'unsigned', 'wrong signer', 'multiple signers', 'no signer', 'wrong package', 'wrong code', 'wrong name', 'debuggable', 'wrong minSdk', 'wrong targetSdk', 'missing SDK', 'missing package')) {
            $script:case = $badCase
            Expect-Rejected "$($app.Device) $badCase" { Assert-ReleaseApk $apk $app $tools }
        }
    }
    Expect-Rejected 'missing APK' { Assert-ReleaseApk "$apk.absent" $releaseApps[0] $tools }
    Expect-Rejected 'missing tool' { Get-AndroidReleaseTools $fixture }
    Expect-Rejected 'missing sidecar' { Assert-ReleaseSidecar $apk }
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$apk.sha256" -Encoding ascii -Value "$hash  fixture.apk"
    Assert-ReleaseSidecar $apk
    $script:count++
    Set-Content -LiteralPath "$apk.sha256" -Encoding ascii -Value "$hash  other.apk"
    Expect-Rejected 'wrong sidecar filename' { Assert-ReleaseSidecar $apk }
    Set-Content -LiteralPath "$apk.sha256" -Encoding ascii -Value "$hash  fixture.apk"
    Add-Content -LiteralPath $apk -Value 'changed bytes'
    Expect-Rejected 'changed APK bytes' { Assert-ReleaseSidecar $apk }
    Set-Content -LiteralPath $apk -Value '' -NoNewline
    Expect-Rejected 'empty APK' { Assert-ReleaseApk $apk $releaseApps[0] $tools }
    Write-Host "$script:count release guard checks passed (mock Android output)."
} finally {
    # Only files created above; no recursive removal or computed tree deletion.
    Remove-Item -LiteralPath $apk, "$apk.sha256" -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $fixture -Force
}
