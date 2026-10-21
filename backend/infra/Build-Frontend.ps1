[CmdletBinding()]
param(
    [string]$FrontendPath = '../frontend',
    [string]$ImageTag = 'xiaohashu/frontend:local',
    [switch]$SkipNpmBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([IO.Path]::IsPathRooted($FrontendPath)) {
    $frontendRoot = (Resolve-Path -LiteralPath $FrontendPath).Path
}
else {
    $frontendRoot = (Resolve-Path -LiteralPath (Join-Path $projectRoot $FrontendPath)).Path
}

if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'package.json'))) {
    throw "The Vue frontend package.json was not found in $frontendRoot."
}

Push-Location $frontendRoot
try {
    if (-not $SkipNpmBuild) {
        if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'node_modules'))) {
            throw "Install the frontend dependencies with npm ci in $frontendRoot first."
        }
        & npm 'run' 'build'
        if ($LASTEXITCODE -ne 0) {
            throw "Frontend build failed with exit code $LASTEXITCODE."
        }
    }

    if (-not (Test-Path -LiteralPath (Join-Path $frontendRoot 'dist/index.html'))) {
        throw 'The frontend dist/index.html is missing; run the frontend build first.'
    }

    $dockerfilePath = Join-Path $PSScriptRoot 'frontend/Dockerfile'
    $buildContext = Join-Path $PSScriptRoot 'frontend'
    & docker 'build' '--build-context' "frontend=$frontendRoot" '-f' $dockerfilePath '-t' $ImageTag $buildContext
    if ($LASTEXITCODE -ne 0) {
        throw "Docker build failed for $ImageTag with exit code $LASTEXITCODE."
    }

    Write-Host "Built $ImageTag. Start it through the application Compose stack on port 8088."
}
finally {
    Pop-Location
}
