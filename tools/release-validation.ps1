# Shared by build-release.ps1 and verify-release.ps1. No signing secrets needed.
$releaseCandidate = 'android-v0.1.11'
$haReleaseVersion = '0.1.2'
$expectedSigner = '32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7'
$releaseApps = @(
    @{ Device = 'X08E'; Module = 'app'; Package = 'com.dormpanel.app'; Version = '0.1.11'; Code = '14'; Min = '28'; Target = '37' },
    @{ Device = 'Phone'; Module = 'phone-app'; Package = 'com.dormpanel.timetable'; Version = '0.1.10'; Code = '12'; Min = '28'; Target = '37' }
)

function Get-AndroidReleaseTools([string]$BuildToolsDirectory) {
    if (-not $BuildToolsDirectory) {
        $sdk = $env:ANDROID_SDK_ROOT
        if (-not $sdk) { $sdk = $env:ANDROID_HOME }
        if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
        $BuildToolsDirectory = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
            Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
            Sort-Object { [version]$_.Name } -Descending |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $BuildToolsDirectory) { throw 'Android build-tools not found.' }
    $result = @{ Signer = Join-Path $BuildToolsDirectory 'apksigner.bat'; Aapt = Join-Path $BuildToolsDirectory 'aapt2.exe' }
    foreach ($path in $result.Values) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required Android tool missing: $path" }
    }
    return $result
}

function Invoke-AndroidReleaseTool([string]$Tool, [string[]]$ToolArguments) {
    # Invocation operator preserves argument boundaries, including paths with spaces.
    $output = & $Tool @ToolArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Android tool failed ($LASTEXITCODE): $Tool`n$($output -join "`n")" }
    return ($output -join "`n")
}

function Assert-ReleaseApk([string]$Apk, [hashtable]$App, [hashtable]$Tools) {
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf) -or (Get-Item -LiteralPath $Apk).Length -eq 0) { throw "Missing or empty release APK: $Apk" }
    $signature = Invoke-AndroidReleaseTool $Tools.Signer @('verify', '--verbose', '--print-certs', '--min-sdk-version', $App.Min, $Apk)
    $digests = @([regex]::Matches($signature, '(?m)^.+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$'))
    if ($digests.Count -ne 1 -or $digests[0].Groups[1].Value.ToLowerInvariant() -cne $expectedSigner) { throw "APK must have exactly the approved signer: $Apk" }
    $badging = Invoke-AndroidReleaseTool $Tools.Aapt @('dump', 'badging', $Apk)
    $packages = @([regex]::Matches($badging, '(?m)^package: .+$'))
    if ($packages.Count -ne 1) { throw "Expected one APK package line: $Apk" }
    $expectedFields = @{ name = $App.Package; versionCode = $App.Code; versionName = $App.Version }
    foreach ($field in $expectedFields.Keys) {
        $match = [regex]::Match($packages[0].Value, "(?:^|\s)" + $field + "='([^']*)'")
        if (-not $match.Success -or $match.Groups[1].Value -cne $expectedFields[$field]) { throw "APK $field is not $($expectedFields[$field]): $Apk" }
    }
    foreach ($sdk in @(@('(?:minSdkVersion|sdkVersion)', $App.Min), @('targetSdkVersion', $App.Target))) {
        $match = [regex]::Match($badging, "(?m)^" + $sdk[0] + ":'([^']*)'\s*$")
        if (-not $match.Success -or $match.Groups[1].Value -cne $sdk[1]) { throw "Unexpected $($sdk[0]): $Apk" }
    }
    if ($badging -match 'application-debuggable') { throw "Release APK is debuggable: $Apk" }
    Write-Host "Verified $($App.Device): $($App.Package) / $($App.Code) / $($App.Version); SDK $($App.Min)/$($App.Target); non-debuggable; signer $expectedSigner"
}

function Assert-ReleaseSidecar([string]$Apk) {
    $sidecar = "$Apk.sha256"
    if (-not (Test-Path -LiteralPath $sidecar -PathType Leaf)) { throw "Missing SHA-256 sidecar: $sidecar" }
    $hash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
    $expected = "$hash  $([IO.Path]::GetFileName($Apk))"
    if ((Get-Content -LiteralPath $sidecar -Raw).TrimEnd("`r", "`n") -cne $expected) { throw "SHA-256 sidecar does not match final APK hash and filename: $Apk" }
    Write-Host "$expected ($((Get-Item -LiteralPath $Apk).Length) bytes)"
}

function Assert-ReleaseDirectory([string]$Directory, [hashtable]$Tools) {
    foreach ($app in $releaseApps) {
        $apk = Join-Path $Directory "DormPanel-$($app.Device)-$($app.Version).apk"
        Assert-ReleaseApk $apk $app $Tools
        Assert-ReleaseSidecar $apk
    }
}

# Verify the archive's exact entry set and bytes, including nested frontend files.
function Assert-HaReleaseArchive([string]$Archive, [string]$SourceRoot) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
    $manifest = Get-Content -LiteralPath (Join-Path $SourceRoot 'manifest.json') -Raw | ConvertFrom-Json
    if ($manifest.version -cne $haReleaseVersion) { throw 'Unexpected HA source version.' }
    $expected = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach ($file in Get-ChildItem -LiteralPath $SourceRoot -Recurse -File) {
        if ($file.FullName -match '\\(__pycache__|\.storage|transfer[^\\]*)\\') { continue }
        $entry = 'custom_components/dormpanel/' + $file.FullName.Substring($SourceRoot.Length).TrimStart('\', '/').Replace('\', '/')
        $expected[$entry] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        if ($zip.Entries.Count -ne $expected.Count) { throw 'HA archive entry count mismatch.' }
        $seen = [Collections.Generic.Dictionary[string,bool]]::new([StringComparer]::Ordinal)
        foreach ($entry in $zip.Entries) {
            if (-not $expected.ContainsKey($entry.FullName) -or $seen.ContainsKey($entry.FullName)) { throw "Unexpected/duplicate HA entry: $($entry.FullName)" }
            $seen[$entry.FullName] = $true
            $stream = $entry.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $digest = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
            finally { $sha.Dispose(); $stream.Dispose() }
            if ($digest -cne $expected[$entry.FullName]) { throw "HA archive source mismatch: $($entry.FullName)" }
        }
    } finally { $zip.Dispose() }
    Assert-ReleaseSidecar $Archive
    Write-Host "Verified HA $haReleaseVersion archive against $($expected.Count) source files."
}
