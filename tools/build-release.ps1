param(
    [string]$Keystore = $env:DORMPANEL_RELEASE_KEYSTORE,
    [string]$KeyAlias = $env:DORMPANEL_RELEASE_KEY_ALIAS,
    [string]$StorePassword = $env:DORMPANEL_RELEASE_STORE_PASSWORD,
    [string]$KeyPassword = $env:DORMPANEL_RELEASE_KEY_PASSWORD,
    [string]$BuildToolsDirectory,
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'release-validation.ps1')

function Invoke-Gradle([string[]]$Tasks) {
    & (Join-Path $repositoryRoot 'gradlew.bat') @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE): $($Tasks -join ' ')" }
}

# Existing approved keypair only; never invent passwords or use a debug fallback.
if (-not $Keystore) { $Keystore = Join-Path $env:USERPROFILE '.dormpanel\dormpanel-release.jks' }
if (-not $KeyAlias) { $KeyAlias = 'androiddebugkey' }
if (-not (Test-Path -LiteralPath $Keystore -PathType Leaf) -or -not $StorePassword -or -not $KeyPassword) {
    throw 'Official RC build requires a keystore and both DORMPANEL_RELEASE_STORE_PASSWORD / DORMPANEL_RELEASE_KEY_PASSWORD. No unsigned or debug fallback.'
}
$Keystore = (Resolve-Path -LiteralPath $Keystore).Path
$tools = Get-AndroidReleaseTools $BuildToolsDirectory
$envNames = @('DORMPANEL_RELEASE_KEYSTORE', 'DORMPANEL_RELEASE_KEY_ALIAS', 'DORMPANEL_RELEASE_STORE_PASSWORD', 'DORMPANEL_RELEASE_KEY_PASSWORD')
$oldEnvironment = @{}
foreach ($name in $envNames) { $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $repositoryRoot
try {
    $env:DORMPANEL_RELEASE_KEYSTORE = $Keystore
    $env:DORMPANEL_RELEASE_KEY_ALIAS = $KeyAlias
    $env:DORMPANEL_RELEASE_STORE_PASSWORD = $StorePassword
    $env:DORMPANEL_RELEASE_KEY_PASSWORD = $KeyPassword
    # Existing Gradle properties take precedence; final signer gates catch mismatches.
    if (-not $SkipTests) {
        Invoke-Gradle @(':app:testDebugUnitTest', ':schedule-core:testDebugUnitTest', ':phone-app:testDebugUnitTest')
    }
    # Remove only checked repository APK outputs; never select a stale artifact.
    foreach ($app in $releaseApps) {
        $output = [IO.Path]::GetFullPath((Join-Path $repositoryRoot "$($app.Module)\build\outputs\apk\release"))
        if (-not $output.StartsWith($repositoryRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'APK output path escaped repository.'
        }
        if (Test-Path -LiteralPath $output) { Remove-Item -LiteralPath $output -Recurse -Force }
    }
    Invoke-Gradle @(':app:assembleRelease', ':phone-app:assembleRelease')
    $outDir = Join-Path $repositoryRoot "artifacts\release\$releaseVersion"
    $staging = Join-Path $repositoryRoot ("artifacts\release\.staging-" + [guid]::NewGuid().ToString('n'))
    New-Item -ItemType Directory -Path $staging | Out-Null
    foreach ($app in $releaseApps) {
        # Exact AGP output, never newest-file or wildcard selection.
        $source = Join-Path $repositoryRoot "$($app.Module)\build\outputs\apk\release\$($app.Module)-release.apk"
        Assert-ReleaseApk $source $app $tools
        $apk = Join-Path $staging "DormPanel-$($app.Device)-$releaseVersion.apk"
        Copy-Item -LiteralPath $source -Destination $apk
        $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        Set-Content -LiteralPath "$apk.sha256" -Encoding ascii -Value "$hash  $([IO.Path]::GetFileName($apk))"
    }
    Assert-ReleaseDirectory $staging $tools

    # HA has its own version lifecycle. Never label it with Android's RC version.
    $haVersion = '0.1.1'
    $haRoot = Join-Path $repositoryRoot 'homeassistant\custom_components\dormpanel'
    $manifest = Get-Content -LiteralPath (Join-Path $haRoot 'manifest.json') -Raw | ConvertFrom-Json
    if ($manifest.version -cne $haVersion) { throw "Unexpected HA manifest version (expected $haVersion)." }
    $haZip = Join-Path $staging "DormPanel-HA-$haVersion.zip"
    $files = @(Get-ChildItem -LiteralPath $haRoot -Recurse -File | Where-Object {
        $_.FullName -notmatch '\\(__pycache__|\.storage|transfer[^\\]*)\\' -and
        $_.Name -notmatch 'credential|secret|token' -and
        $_.Extension -in '.py', '.json', '.js', '.md', '.txt', '.css', '.html'
    } | Sort-Object FullName)
    if ($files.Count -eq 0) { throw 'No HA source files.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::Open($haZip, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in $files) {
            $entry = 'custom_components/dormpanel/' + $file.FullName.Substring($haRoot.Length).TrimStart('\', '/').Replace('\', '/')
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $entry) | Out-Null
        }
    } finally { $zip.Dispose() }
    # All staging gates pass before copying candidates into the final directory.
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    Get-ChildItem -LiteralPath $staging -File | Copy-Item -Destination $outDir -Force
    Assert-ReleaseDirectory $outDir $tools
    Write-Host "HA archive (actual integration version $haVersion): $outDir\DormPanel-HA-$haVersion.zip"
    Write-Host "Local RC candidates: $outDir. No tag or Release created; physical acceptance pending."
} finally {
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process') }
    Pop-Location
}
