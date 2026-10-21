[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^accept-[0-9a-f]{12}$')]
    [string]$RunId,

    [Parameter(Mandatory = $true)]
    [ValidateSet('Comment', 'Canal')]
    [string]$Stage
)

$ErrorActionPreference = 'Stop'
$env:MSYS_NO_PATHCONV = '1'
$manifestPath = Join-Path $PSScriptRoot "../.runtime/acceptance/$RunId/manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Recovery fixture manifest does not exist: $manifestPath"
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or $manifest.runId -ne $RunId -or
    $manifest.projectName -ne "xiaohashu-$RunId") {
    throw 'Recovery fixture manifest identity or schema version is invalid.'
}
if ([string]::IsNullOrWhiteSpace([string]$manifest.userId) -or
    [string]::IsNullOrWhiteSpace([string]$manifest.noteId)) {
    throw 'Recovery fixture manifest is missing its isolated test user or note ID.'
}
if ($Stage -eq 'Comment' -and $manifest.commentBaseUrl -notmatch '^http://127\.0\.0\.1:\d+$') {
    throw 'Comment fixture endpoint must bind to a loopback-only dynamic host port.'
}
if ($Stage -eq 'Canal' -and $manifest.searchBaseUrl -notmatch '^http://127\.0\.0\.1:\d+$') {
    throw 'Canal fixture endpoint must bind to a loopback-only dynamic host port.'
}

$checkedContainers = 0
foreach ($container in $manifest.containers) {
    if ($container.status -ne 'created' -or [string]::IsNullOrWhiteSpace([string]$container.id)) {
        throw "Manifest resource '$($container.service)' is not recorded as created."
    }
    $inspect = @(& docker inspect $container.id 2>$null)
    if ($LASTEXITCODE -ne 0) { throw "Manifest container ID is unavailable: $($container.service)" }
    $details = ($inspect -join "`n") | ConvertFrom-Json
    $labels = $details[0].Config.Labels
    if ($labels.'codex.acceptance.run' -ne $RunId -or
        $labels.'codex.acceptance.project' -ne 'xiaohashu') {
        throw "Container '$($container.service)' does not carry the expected acceptance labels."
    }
    $composeProject = [string]$labels.'com.docker.compose.project'
    if ($container.service -eq 'probe') {
        if (-not [string]::IsNullOrWhiteSpace($composeProject) -and $composeProject -ne $manifest.projectName) {
            throw "Probe container '$($container.service)' belongs to a different Compose project."
        }
    } elseif ($composeProject -ne $manifest.projectName) {
        throw "Container '$($container.service)' belongs to a different Compose project."
    }
    $checkedContainers++
}
foreach ($network in $manifest.networks) {
    $inspect = @(& docker network inspect $network.id 2>$null)
    if ($LASTEXITCODE -ne 0) { throw "Manifest network is unavailable: $($network.name)" }
    $details = ($inspect -join "`n") | ConvertFrom-Json
    if ($details[0].Labels.'codex.acceptance.run' -ne $RunId -or
        $details[0].Labels.'codex.acceptance.project' -ne 'xiaohashu') {
        throw "Network '$($network.name)' does not carry the expected acceptance labels."
    }
}
foreach ($volume in $manifest.volumes) {
    $inspect = @(& docker volume inspect $volume.name 2>$null)
    if ($LASTEXITCODE -ne 0) { throw "Manifest volume is unavailable: $($volume.name)" }
    $details = ($inspect -join "`n") | ConvertFrom-Json
    if ($details[0].Labels.'codex.acceptance.run' -ne $RunId -or
        $details[0].Labels.'codex.acceptance.project' -ne 'xiaohashu') {
        throw "Volume '$($volume.name)' does not carry the expected acceptance labels."
    }
}
[pscustomobject]@{
    runId = $RunId
    stage = $Stage
    result = 'passed'
    checkedContainers = $checkedContainers
    checkedNetworks = @($manifest.networks).Count
    checkedVolumes = @($manifest.volumes).Count
    endpoint = if ($Stage -eq 'Comment') { $manifest.commentBaseUrl } else { $manifest.searchBaseUrl }
}
