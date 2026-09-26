param(
    [string]$Keystore = $env:DORMPANEL_RELEASE_KEYSTORE,
    [string]$KeyAlias = $env:DORMPANEL_RELEASE_KEY_ALIAS,
    [string]$StorePassword = $env:DORMPANEL_RELEASE_STORE_PASSWORD,
    [string]$KeyPassword = $env:DORMPANEL_RELEASE_KEY_PASSWORD,
    [switch]$SkipTests,
    [switch]$AllowUnsigned
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repositoryRoot

function Fail([string]$Message) {
    Write-Error $Message
    exit 1
}

function Invoke-Checked {
    param([string]$FilePath, [string[]]$Arguments, [string]$Label)
    Write-Host ">> $Label"
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        Fail "$Label failed with exit code $LASTEXITCODE"
    }
}

# --- Resolve signing inputs (values are never printed) -----------------------
if (-not $Keystore) { $Keystore = Join-Path $env:USERPROFILE '.dormpanel\dormpanel-release.jks' }
if (-not $KeyAlias) { $KeyAlias = 'androiddebugkey' }
if (-not $StorePassword) { $StorePassword = $env:DORMPANEL_RELEASE_STORE_PASSWORD }
if (-not $KeyPassword) { $KeyPassword = $env:DORMPANEL_RELEASE_KEY_PASSWORD }

$signingReady = $true
if (-not (Test-Path -LiteralPath $Keystore)) {
    Write-Warning "Release keystore not found at '$Keystore'."
    $signingReady = $false
}
if (-not $StorePassword -or -not $KeyPassword) {
    Write-Warning 'DORMPANEL_RELEASE_STORE_PASSWORD and/or DORMPANEL_RELEASE_KEY_PASSWORD are not set.'
    Write-Warning 'Refusing to print or invent signing passwords. Export them, or place them in a local gradle.properties that is not committed.'
    $signingReady = $false
}

if (-not $signingReady) {
    if ($AllowUnsigned) {
        Write-Warning 'Continuing with an unsigned/debug-signed build because -AllowUnsigned was set. This is NOT an official release.'
    } else {
        Fail 'Official release builds require signing inputs. Set DORMPANEL_RELEASE_* or pass -AllowUnsigned for a non-release experiment.'
    }
}

# Prefer gradle property files when present so passwords stay out of the shell history.
$gradleProps = @()
if ($signingReady) {
    $gradleProps += "-Pdormpanel.release.keystore=$Keystore"
    $gradleProps += "-Pdormpanel.release.keyAlias=$KeyAlias"
    $env:DORMPANEL_RELEASE_KEYSTORE = $Keystore
    $env:DORMPANEL_RELEASE_KEY_ALIAS = $KeyAlias
    $env:DORMPANEL_RELEASE_STORE_PASSWORD = $StorePassword
    $env:DORMPANEL_RELEASE_KEY_PASSWORD = $KeyPassword
}

# --- Build ------------------------------------------------------------------
if (-not $SkipTests) {
    Invoke-Checked -FilePath '.\gradlew.bat' -Arguments @('testDebugUnitTest') -Label 'Unit tests'
}

Invoke-Checked -FilePath '.\gradlew.bat' -Arguments (@('assembleRelease') + $gradleProps) -Label 'assembleRelease'

$apk = Get-ChildItem -Path 'app\build\outputs\apk\release' -Filter '*.apk' -File |
    Where-Object { $_.Name -notlike '*androidTest*' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $apk) { Fail 'Release APK was not produced.' }

$apksigner = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') -Directory |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path $_ } |
    Select-Object -First 1
if (-not $apksigner) { $apksigner = Get-Command apksigner -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source }
if (-not $apksigner) { Fail 'apksigner was not found. Install Android build-tools.' }

$aapt2 = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') -Directory |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName 'aapt2.exe' } |
    Where-Object { Test-Path $_ } |
    Select-Object -First 1
if (-not $aapt2) {
    $aapt2 = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'aapt2.bat' } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1
}

Write-Host ">> Verifying APK signature: $($apk.FullName)"
$sigOutputFile = Join-Path $env:TEMP ("dormpanel-apksigner-" + [guid]::NewGuid().ToString('n') + ".txt")
$sigErrFile = $sigOutputFile + ".err"
$apksignerArgs = @('verify', '--print-certs', $apk.FullName)
$process = Start-Process -FilePath $apksigner -ArgumentList $apksignerArgs -NoNewWindow -Wait -PassThru -RedirectStandardOutput $sigOutputFile -RedirectStandardError $sigErrFile
$sigOutput = ''
if (Test-Path -LiteralPath $sigOutputFile) { $sigOutput += (Get-Content -LiteralPath $sigOutputFile -Raw) }
if (Test-Path -LiteralPath $sigErrFile) { $sigOutput += (Get-Content -LiteralPath $sigErrFile -Raw) }
Remove-Item -LiteralPath $sigOutputFile, $sigErrFile -Force -ErrorAction SilentlyContinue
if ($process.ExitCode -ne 0) { Fail ("APK signature verification failed.`n" + $sigOutput) }
$sigOutput | Write-Host

$sha256Line = ($sigOutput -split "`r?`n") | Where-Object { $_ -match 'SHA-256 digest:' } | Select-Object -First 1
if (-not $sha256Line) { Fail 'Could not read the signer certificate SHA-256.' }
$signerSha256 = ($sha256Line -split 'SHA-256 digest:\s*', 2)[1].Trim()
Write-Host "Signer certificate SHA-256: $signerSha256"

# Expected public fingerprint of the approved 0.1.0 identity (Android Debug
# keypair promoted to the DormPanel release identity on this machine).
$expectedSigner = '32d68b5c6ad0bfd1b87aa0d54514ef71c33838e7643eb038488af7ca2add17f7'
if ($signingReady -and $signerSha256.ToLowerInvariant() -ne $expectedSigner) {
    Fail "Signer certificate SHA-256 '$signerSha256' does not match the expected release identity '$expectedSigner'."
}

# --- Package / version metadata ---------------------------------------------
if ($aapt2) {
    Write-Host '>> Reading APK metadata'
    $badgingFile = Join-Path $env:TEMP ("dormpanel-aapt2-" + [guid]::NewGuid().ToString('n') + ".txt")
    $badgingErr = $badgingFile + ".err"
    $badgingProcess = Start-Process -FilePath $aapt2 -ArgumentList @('dump', 'badging', $apk.FullName) -NoNewWindow -Wait -PassThru -RedirectStandardOutput $badgingFile -RedirectStandardError $badgingErr
    $badging = ''
    if (Test-Path -LiteralPath $badgingFile) { $badging += (Get-Content -LiteralPath $badgingFile -Raw) }
    if (Test-Path -LiteralPath $badgingErr) { $badging += (Get-Content -LiteralPath $badgingErr -Raw) }
    Remove-Item -LiteralPath $badgingFile, $badgingErr -Force -ErrorAction SilentlyContinue
    if ($badgingProcess.ExitCode -ne 0) { Fail ("aapt2 dump badging failed.`n" + $badging) }
    $badging | Write-Host
    if ($badging -notmatch "package: name='com\.dormpanel\.app'") {
        Fail 'applicationId is not com.dormpanel.app'
    }
    if ($badging -notmatch "versionCode='2'") {
        Fail "versionCode is not 2"
    }
    if ($badging -notmatch "versionName='0\.1\.0'") {
        Fail "versionName is not 0.1.0"
    }
    if ($badging -match 'application-debuggable') {
        Fail 'Release APK is debuggable'
    }
} else {
    Write-Warning 'aapt2 not found; skipping badging metadata checks.'
}

# --- Artifact names and sidecars --------------------------------------------
$outDir = Join-Path $repositoryRoot 'artifacts\release'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$apkName = 'DormPanel-0.1.0.apk'
$apkOut = Join-Path $outDir $apkName
Copy-Item $apk.FullName $apkOut -Force

$apkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apkOut).Hash.ToLowerInvariant()
$sidecar = "$apkOut.sha256"
# Standard `<hash>  <filename>` form so sha256sum -c works.
Set-Content -LiteralPath $sidecar -Value "$apkHash  $apkName" -Encoding ascii
Write-Host "APK SHA-256: $apkHash"
Write-Host "Sidecar: $sidecar"

# --- HA integration archive (deterministic file set) ------------------------
$haRoot = Join-Path $repositoryRoot 'homeassistant\custom_components\dormpanel'
if (-not (Test-Path -LiteralPath $haRoot)) { Fail 'HA integration source tree is missing.' }
$haZip = Join-Path $outDir 'DormPanel-HA-0.1.0.zip'
if (Test-Path -LiteralPath $haZip) { Remove-Item -LiteralPath $haZip -Force }

$includeNames = @('*.py', '*.json', '*.js', '*.md', '*.txt')
$files = Get-ChildItem -LiteralPath $haRoot -Recurse -File |
    Where-Object {
        $_.FullName -notmatch '\\__pycache__\\' -and
        $_.Extension -ne '.pyc' -and
        $_.Name -ne '.DS_Store' -and
        $_.FullName -notmatch '\\\.storage\\' -and
        $_.FullName -notmatch '\\transfer' -and
        $_.Name -notmatch 'credential|secret|token' -and
        $_.Extension -in '.py', '.json', '.js', '.md', '.txt', '.css', '.html'
    } |
    Sort-Object { $_.FullName.Substring($haRoot.Length).TrimStart('\','/') }

if ($files.Count -eq 0) { Fail 'No HA integration files selected for packaging.' }

Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
$zip = [System.IO.Compression.ZipFile]::Open($haZip, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($haRoot.Length).TrimStart('\', '/')
        # Archive path is always custom_components/dormpanel/...
        $entryName = 'custom_components/dormpanel/' + ($relative -replace '\\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $entryName,
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        Write-Host "  + $entryName"
    }
} finally {
    $zip.Dispose()
}
Write-Host "HA archive: $haZip"

Write-Host ''
Write-Host 'Release artifacts:'
Get-ChildItem -LiteralPath $outDir | ForEach-Object { Write-Host "  $($_.Name)" }
Write-Host ''
Write-Host 'Reminder: APK update (adb install -r) keeps app data. Uninstall/reinstall does not.'
Write-Host 'Do not commit signing material, keystore files, or passwords.'
