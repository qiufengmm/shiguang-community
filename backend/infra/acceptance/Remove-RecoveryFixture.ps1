[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^accept-[0-9a-f]{12}$')]
    [string]$RunId
)

$ErrorActionPreference = 'Stop'
$env:MSYS_NO_PATHCONV = '1'
$runDirectory = Join-Path $PSScriptRoot "../.runtime/acceptance/$RunId"
$manifestPath = Join-Path $runDirectory 'manifest.json'

function Invoke-DockerChecked {
    param([string[]]$Arguments)
    $result = @(& docker @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed (exit $LASTEXITCODE)."
    }
    return $result
}

function Save-Manifest {
    $tempPath = "$manifestPath.tmp"
    [IO.File]::WriteAllText($tempPath, ((ConvertTo-Json -InputObject $manifest -Depth 30) + [Environment]::NewLine),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $tempPath -Destination $manifestPath -Force
}

function Get-ContainerDetails {
    param([string]$Id)
    $raw = Invoke-DockerChecked -Arguments @('inspect', $Id)
    ($raw -join "`n") | ConvertFrom-Json -Depth 30
}

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Runtime manifest not found for '$RunId'."
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -Depth 30
if (
    $manifest.schemaVersion -ne 1 -or
    $manifest.runId -ne $RunId -or
    $manifest.projectName -ne "xiaohashu-$RunId"
) {
    throw 'Manifest identity does not match the requested acceptance run.'
}

$recordedIds = @($manifest.containers | Where-Object { $_.id } | ForEach-Object { [string]$_.id })
$labeledIds = @(Invoke-DockerChecked -Arguments @('ps', '--all', '--quiet', '--filter', "label=codex.acceptance.run=$RunId") |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | ForEach-Object { [string]$_ })
$unrecorded = @($labeledIds | Where-Object { $_ -notin $recordedIds })
if ($unrecorded.Count -gt 0) {
    throw 'Found run-labeled containers absent from the manifest; inspect them individually before cleanup.'
}

foreach ($container in @($manifest.containers | Where-Object { $_.status -eq 'created' -and $_.id })) {
    $id = [string]$container.id
    $current = Get-ContainerDetails -Id $id
    if (
        $current[0].Config.Labels.'codex.acceptance.run' -ne $RunId -or
        $current[0].Config.Labels.'codex.acceptance.project' -ne 'xiaohashu'
    ) {
        throw "Container '$id' labels do not match this manifest; refusing cleanup."
    }
    if ($PSCmdlet.ShouldProcess("container $id", 'stop and remove')) {
        if ($current[0].State.Status -eq 'running') {
            $null = Invoke-DockerChecked -Arguments @('stop', '--time', '20', $id)
        }
        $null = Invoke-DockerChecked -Arguments @('rm', $id)
        $container.status = 'removed'
        $container.state = 'removed'
        $container.removedAt = [DateTime]::UtcNow.ToString('o')
        Save-Manifest
    }
}

foreach ($volume in @($manifest.volumes | Where-Object { $_.status -eq 'created' -and $_.name })) {
    $name = [string]$volume.name
    $details = Invoke-DockerChecked -Arguments @('volume', 'inspect', $name)
    $item = ($details -join "`n") | ConvertFrom-Json -Depth 20
    if (
        $item[0].Labels.'codex.acceptance.run' -ne $RunId -or
        $item[0].Labels.'codex.acceptance.project' -ne 'xiaohashu'
    ) {
        throw "Volume '$name' labels do not match this manifest; refusing cleanup."
    }
    if ($PSCmdlet.ShouldProcess("volume $name", 'remove')) {
        $null = Invoke-DockerChecked -Arguments @('volume', 'rm', $name)
        $volume.status = 'removed'
        $volume.removedAt = [DateTime]::UtcNow.ToString('o')
        Save-Manifest
    }
}

foreach ($network in @($manifest.networks | Where-Object { $_.status -eq 'created' -and $_.id })) {
    $id = [string]$network.id
    $details = Invoke-DockerChecked -Arguments @('network', 'inspect', $id)
    $item = ($details -join "`n") | ConvertFrom-Json -Depth 20
    if (
        $item[0].Labels.'codex.acceptance.run' -ne $RunId -or
        $item[0].Labels.'codex.acceptance.project' -ne 'xiaohashu'
    ) {
        throw "Network '$id' labels do not match this manifest; refusing cleanup."
    }
    if ($PSCmdlet.ShouldProcess("network $id", 'remove')) {
        $null = Invoke-DockerChecked -Arguments @('network', 'rm', $id)
        $network.status = 'removed'
        $network.removedAt = [DateTime]::UtcNow.ToString('o')
        Save-Manifest
    }
}

[pscustomobject]@{
    runId = $RunId
    containersRemoved = @($manifest.containers | Where-Object status -eq 'removed').Count
    volumesRemoved = @($manifest.volumes | Where-Object status -eq 'removed').Count
    networksRemoved = @($manifest.networks | Where-Object status -eq 'removed').Count
    runtimeEvidenceRetained = Test-Path -LiteralPath $runDirectory
}
