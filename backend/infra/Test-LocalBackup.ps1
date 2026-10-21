[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupPath
)

$ErrorActionPreference = 'Stop'
$BackupPath = [IO.Path]::GetFullPath($BackupPath)
$manifestPath = Join-Path $BackupPath 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Backup manifest not found: $manifestPath"
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -Depth 30
if ($manifest.overallStatus -ne 'success') {
    throw "Backup is not complete (manifest status: $($manifest.overallStatus))."
}
if (-not $manifest.files -or @($manifest.files).Count -eq 0) {
    throw 'Backup manifest contains no file checksums.'
}

$rootPrefix = $BackupPath.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$verified = 0
foreach ($entry in $manifest.files) {
    $filePath = [IO.Path]::GetFullPath((Join-Path $BackupPath ([string]$entry.path)))
    if (-not $filePath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Manifest path escapes the backup directory: $($entry.path)"
    }
    if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
        throw "Backup file is missing: $($entry.path)"
    }
    $file = Get-Item -LiteralPath $filePath
    if ([long]$file.Length -ne [long]$entry.bytes) {
        throw "Backup file size differs from the manifest: $($entry.path)"
    }
    $actualHash = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash
    if ($actualHash -ne [string]$entry.sha256) {
        throw "Backup file checksum differs from the manifest: $($entry.path)"
    }
    $verified++
}

Write-Host "Verified $verified files from backup '$BackupPath'."
