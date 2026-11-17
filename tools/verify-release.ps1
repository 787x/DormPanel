param(
    [string]$Directory,
    [string]$BuildToolsDirectory,
    [switch]$AndroidOnly
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-validation.ps1')
if (-not $Directory) { $Directory = Join-Path $PSScriptRoot "..\artifacts\release\$releaseCandidate" }
Assert-ReleaseDirectory $Directory (Get-AndroidReleaseTools $BuildToolsDirectory)
if (-not $AndroidOnly) { Assert-HaReleaseArchive (Join-Path $Directory "DormPanel-HA-$haReleaseVersion.zip") (Join-Path $PSScriptRoot '..\homeassistant\custom_components\dormpanel')
}
Write-Host 'Both signed release artifacts verified. Device acceptance is recorded separately.'
