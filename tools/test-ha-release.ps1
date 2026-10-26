param([string]$Directory = (Join-Path $PSScriptRoot '..\artifacts\release\sync-v3-rc1'))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-validation.ps1')
$sourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\homeassistant\custom_components\dormpanel')).Path
$source = Join-Path $Directory "DormPanel-HA-$haReleaseVersion.zip"
Assert-HaReleaseArchive $source $sourceRoot
$fixture = Join-Path $PSScriptRoot ('..\artifacts\release-audit\ha-negative-' + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Path $fixture -Force | Out-Null
$count = 1
foreach ($case in @('content', 'extra', 'duplicate', 'case-sensitive path', 'sidecar')) {
    $path = Join-Path $fixture ($case + '.zip')
    $inputZip = [IO.Compression.ZipFile]::OpenRead($source)
    $outputZip = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $inputZip.Entries) {
            $name = $entry.FullName
            if ($case -eq 'case-sensitive path' -and $name.EndsWith('/frontend/panel.js')) { $name = $name.Replace('/panel.js', '/Panel.js') }
            $copy = $outputZip.CreateEntry($name)
            $reader = $entry.Open(); $writer = $copy.Open()
            try {
                $reader.CopyTo($writer)
                if ($case -eq 'content' -and $name.EndsWith('/frontend/panel.js')) { $writer.WriteByte(0) }
            } finally { $reader.Dispose(); $writer.Dispose() }
        }
        if ($case -eq 'extra') { [void]$outputZip.CreateEntry('unexpected.txt') }
        if ($case -eq 'duplicate') { [void]$outputZip.CreateEntry($inputZip.Entries[0].FullName) }
    } finally { $inputZip.Dispose(); $outputZip.Dispose() }
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($case -eq 'sidecar') { $hash = '0' * 64 }
    Set-Content -LiteralPath "$path.sha256" -Encoding ascii -Value "$hash  $([IO.Path]::GetFileName($path))"
    $rejected = $false
    try { Assert-HaReleaseArchive $path $sourceRoot } catch { $rejected = $true }
    if (-not $rejected) { throw "HA archive gate incorrectly accepted $case" }
    $count++; Write-Host "PASS rejects HA $case"
}
Write-Host "$count HA archive checks passed using real ZIP bytes; private fixtures: $fixture"

