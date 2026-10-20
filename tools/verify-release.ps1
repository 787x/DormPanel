param(
    [string]$Directory = (Join-Path $PSScriptRoot '..\artifacts\release\0.1.2-rc.1'),
    [string]$BuildToolsDirectory
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-validation.ps1')
Assert-ReleaseDirectory $Directory (Get-AndroidReleaseTools $BuildToolsDirectory)
Write-Host 'Both local RC candidates verified. Physical acceptance remains pending.'
