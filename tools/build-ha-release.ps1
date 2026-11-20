param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-validation.ps1')

$outDir = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outDir) { throw "Candidate directory already exists: $outDir" }
$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '../homeassistant/custom_components/dormpanel')).Path
$staging = Join-Path (Split-Path -Parent $outDir) ('.ha-staging-' + [guid]::NewGuid().ToString('n'))
[void][IO.Directory]::CreateDirectory($staging)
$archive = Join-Path $staging "DormPanel-HA-$haReleaseVersion.zip"
New-HaReleaseArchive $archive $sourceRoot
Copy-Item -LiteralPath "$archive.sha256" -Destination (Join-Path $staging 'SHA256SUMS.txt')
[IO.Directory]::Move($staging, $outDir)
Write-Host "Verified HA release archive: $outDir"
