[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$WithTools,
    [ValidateSet('ReuseExisting', 'Fresh')]
    [string]$Mode = 'ReuseExisting',
    [string]$DataRoot = $env:XIAOHASHU_DATA_ROOT,
    [string]$SeedConfigRoot
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($DataRoot)) { $DataRoot = 'F:\docker' }
$DataRoot = [IO.Path]::GetFullPath($DataRoot)
Push-Location $projectRoot
try {
    if ($Mode -eq 'Fresh') {
        $composeYamlPath = Join-Path $PSScriptRoot 'compose.yaml'
        $composeProjectName = 'xiaohashu'
        if (Test-Path -LiteralPath $composeYamlPath) {
            $projectMatch = [regex]::Match([IO.File]::ReadAllText($composeYamlPath), '(?m)^name:\s*([A-Za-z0-9_.-]+)\s*$')
            if ($projectMatch.Success) { $composeProjectName = $projectMatch.Groups[1].Value }
        }
        $currentProjectContainers = @(& docker 'ps' '-a' '-q' '--filter' "label=com.docker.compose.project=$composeProjectName")
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Docker containers before fresh setup.' }
        if ($currentProjectContainers.Count -gt 0) {
            $currentContainerDetails = @(& docker 'inspect' @currentProjectContainers | ConvertFrom-Json)
            if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Docker project ownership before fresh setup.' }
            if (@($currentContainerDetails | Where-Object { $_.Config.Labels.'com.docker.compose.project' -eq $composeProjectName }).Count -gt 0) {
                throw "Fresh mode refuses to replace an existing $composeProjectName Compose project, including stopped containers. Use a new isolated checkout and project name."
            }
        }
    }
    if (-not $SkipBuild) {
        & (Join-Path $PSScriptRoot 'Build-Images.ps1')
        & (Join-Path $PSScriptRoot 'Build-Frontend.ps1')
    }
    $prepareParameters = @{
        Mode = $Mode
        DataRoot = $DataRoot
    }
    if ($SeedConfigRoot) { $prepareParameters.SeedConfigRoot = $SeedConfigRoot }
    & (Join-Path $PSScriptRoot 'Prepare-Local.ps1') @prepareParameters
    if ($LASTEXITCODE -ne 0) { throw 'Local configuration preparation failed.' }
    $composeArgs = @('compose', '--env-file', 'infra/.runtime/compose.env', '-f', 'infra/compose.yaml', '-f', 'infra/compose.app.yaml', '--profile', 'frp-local-test')
    if ($WithTools) { $composeArgs += @('--profile', 'tools') }
    $renderedCompose = & docker @composeArgs 'config' '--format' 'json'
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration validation failed.' }
    $composeConfig = ($renderedCompose -join "`n") | ConvertFrom-Json -Depth 100

    $plannedBindSources = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $plannedVolumeNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($serviceProperty in $composeConfig.services.PSObject.Properties) {
        $serviceName = $serviceProperty.Name
        foreach ($mount in @($serviceProperty.Value.volumes)) {
            if ($mount.type -eq 'bind') {
                $mountSource = [string]$mount.source
                if (-not [IO.Path]::IsPathRooted($mountSource)) {
                    $mountSource = Join-Path $projectRoot $mountSource
                }
                $mountSource = [IO.Path]::GetFullPath($mountSource)
                if (-not (Test-Path -LiteralPath $mountSource)) {
                    throw "Required bind source is missing for service '$serviceName': $mountSource. Prepare or restore it explicitly; Compose will not create empty data paths."
                }
                [void]$plannedBindSources.Add($mountSource)
            } elseif ($mount.type -eq 'volume') {
                $volume = $composeConfig.volumes.PSObject.Properties[$mount.source]
                if ($volume -and $volume.Value.name) { [void]$plannedVolumeNames.Add([string]$volume.Value.name) }
            }
        }
        foreach ($port in @($serviceProperty.Value.ports)) {
            if (-not $port) { continue }
            $expectedBind = if ($serviceName -eq 'frontend') { [string]$composeConfig.services.frontend.ports[0].host_ip } else { '127.0.0.1' }
            if ([string]$port.host_ip -ne $expectedBind) {
                throw "Unsafe published port binding on '$serviceName': $($port.host_ip):$($port.published). Only the frontend UI may bind outside loopback."
            }
        }
    }

    # Refuse to share a live bind path or external volume with a container
    # owned by another Compose project; never stop another application's data service.
    $runningIds = @(& docker 'ps' '-q')
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect currently running Docker containers.' }
    if ($runningIds.Count -gt 0) {
        $runningContainers = @(& docker 'inspect' @runningIds | ConvertFrom-Json)
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Docker mount ownership.' }
        foreach ($container in $runningContainers) {
            $containerName = ([string]$container.Name).TrimStart('/')
            $composeProject = [string]$container.Config.Labels.'com.docker.compose.project'
            if ($composeProject -eq 'xiaohashu') { continue }
            foreach ($mount in $container.Mounts) {
                if ($mount.Type -eq 'bind' -and $mount.Source) {
                    $sourcePath = [IO.Path]::GetFullPath([string]$mount.Source)
                    if ($plannedBindSources.Contains($sourcePath)) {
                        throw "Container '$containerName' from another project is using required Xiaohashu data path '$sourcePath'. Stop it manually only after verifying ownership; this script will not stop it."
                    }
                } elseif ($mount.Type -eq 'volume' -and $mount.Name -and $plannedVolumeNames.Contains([string]$mount.Name)) {
                    throw "Container '$containerName' from another project is using required Xiaohashu volume '$($mount.Name)'. Stop it manually only after verifying ownership; this script will not stop it."
                }
            }
        }
    }

    if ($Mode -eq 'Fresh') {
        foreach ($volumeName in $plannedVolumeNames) {
            & docker 'volume' 'inspect' $volumeName 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) {
                throw "Fresh mode refuses to reuse an existing Docker volume '$volumeName'. Choose unused volume names in the local environment before retrying."
            }
            & docker 'volume' 'create' $volumeName | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Could not create fresh external volume '$volumeName'." }
        }
    }

    & docker @composeArgs 'up' '-d' '--wait' '--wait-timeout' '600'
    if ($LASTEXITCODE -ne 0) { throw 'Compose startup failed; inspect the affected service logs.' }
    & docker @composeArgs 'ps'
    Write-Host "Frontend: http://localhost:$($composeConfig.services.frontend.ports[0].published) ; Gateway: http://localhost:8000 ; local FRP forwarding: http://localhost:18000"
}
finally { Pop-Location }
