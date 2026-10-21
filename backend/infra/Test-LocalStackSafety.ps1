$ErrorActionPreference = 'Stop'
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('xiaohashu-stack-safety-' + [guid]::NewGuid().ToString('N'))
$fixtureInfra = Join-Path $fixtureRoot 'infra'
New-Item -ItemType Directory -Path (Join-Path $fixtureInfra '.runtime') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Start-Local.ps1') -Destination $fixtureInfra
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Backup-Local.ps1') -Destination $fixtureInfra
[IO.File]::WriteAllText((Join-Path $fixtureInfra 'Prepare-Local.ps1'), "throw 'PREPARE_REACHED'")
[IO.File]::WriteAllText((Join-Path $fixtureInfra '.runtime/compose.env'), '# Isolated fixture; contains no credentials.')
$deploymentRoot = Join-Path $fixtureRoot 'deployment-a'
[IO.File]::WriteAllText((Join-Path $fixtureInfra '.runtime/data-root.txt'), $deploymentRoot)

function Assert-Throws([scriptblock]$Action, [string]$ExpectedText) {
    try { & $Action | Out-Null } catch {
        if ($_.Exception.Message -notlike "*$ExpectedText*") {
            throw "Expected '$ExpectedText', got: $($_.Exception.Message)"
        }
        return
    }
    throw "Expected rejection containing '$ExpectedText'."
}

$script:failures = @()
function Invoke-FixtureCheck([string]$Name, [scriptblock]$Action) {
    try {
        & $Action
        Write-Output "PASS $Name"
    } catch {
        $script:failures += "$Name : $($_.Exception.Message)"
        Write-Output "FAIL $Name : $($_.Exception.Message)"
    }
}

# Docker is replaced only at the external command boundary. These fixtures must
# never reach the Docker engine or the repository's real runtime configuration.
$global:XiaohashuSafetyDockerFixture = 'StoppedProject'
$global:XiaohashuSafetyComposeFixture = @{
    name = 'xiaohashu'
    services = @{
        minio = @{ volumes = @(@{ type = 'bind'; source = (Join-Path $deploymentRoot 'minio/data'); target = '/data' }) }
        cassandra = @{ volumes = @(@{ type = 'bind'; source = (Join-Path $deploymentRoot 'cassandra5/data'); target = '/var/lib/cassandra' }) }
        redis = @{ volumes = @(@{ type = 'bind'; source = (Join-Path $deploymentRoot 'redis/data'); target = '/data' }) }
    }
}
function docker {
    param([Parameter(ValueFromRemainingArguments = $true)][object[]]$DockerArguments)
    $global:LASTEXITCODE = 0
    $arguments = @($DockerArguments | ForEach-Object { [string]$_ })
    if ($global:XiaohashuSafetyDockerFixture -eq 'StoppedProject') {
        if ($arguments[0] -eq 'ps') {
            if ($arguments -contains '-a') { return 'stopped-xiaohashu-container' }
            return
        }
        if ($arguments[0] -eq 'inspect') {
            return '[{"Config":{"Labels":{"com.docker.compose.project":"xiaohashu"}},"State":{"Running":false}}]'
        }
    } elseif ($global:XiaohashuSafetyDockerFixture -eq 'Backup') {
        if ($arguments -contains 'config') { return ($global:XiaohashuSafetyComposeFixture | ConvertTo-Json -Depth 20) }
        if ($arguments -contains 'ps') {
            if ($arguments -contains '-a') { return 'stopped-zookeeper-fixture' }
            return
        }
    }
    throw ('Unexpected Docker call in fixture: ' + ($arguments -join ' '))
}

Invoke-FixtureCheck 'Fresh rejects a stopped existing Compose project before preparation' {
    Assert-Throws { & (Join-Path $fixtureInfra 'Start-Local.ps1') -SkipBuild -Mode Fresh -DataRoot (Join-Path $fixtureRoot 'fresh-root') } 'Fresh mode refuses'
}

$global:XiaohashuSafetyDockerFixture = 'Backup'
Invoke-FixtureCheck 'Backup rejects an explicit root from another deployment before copying' {
    $backupDestination = Join-Path $fixtureRoot 'backup-output'
    Assert-Throws { & (Join-Path $fixtureInfra 'Backup-Local.ps1') -DataRoot (Join-Path $fixtureRoot 'deployment-b') -DestinationRoot $backupDestination } 'data root does not match'
    if (Test-Path -LiteralPath $backupDestination) { throw 'A rejected backup created its destination.' }
}

Write-Output "Fixture files retained at: $fixtureRoot"
if ($script:failures.Count -gt 0) { throw ($script:failures -join "`n") }
