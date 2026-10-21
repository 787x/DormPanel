param(
    [string]$Directory = (Join-Path $PSScriptRoot '..\artifacts\release\0.1.2'),
    [string]$BuildToolsDirectory
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-validation.ps1')
Assert-ReleaseDirectory $Directory (Get-AndroidReleaseTools $BuildToolsDirectory)
Write-Host 'Both signed release artifacts verified. Device acceptance is recorded separately.'
