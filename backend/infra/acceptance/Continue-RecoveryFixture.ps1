[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^accept-[0-9a-f]{12}$')]
    [string]$RunId,
    [Parameter(Mandatory = $true)]
    [ValidateSet('Comment')]
    [string]$Stage
)

$ErrorActionPreference = 'Stop'
$env:MSYS_NO_PATHCONV = '1'
$runDirectory = Join-Path $PSScriptRoot "../.runtime/acceptance/$RunId"
$manifestPath = Join-Path $runDirectory 'manifest.json'
$envFile = Join-Path $runDirectory 'compose.env'
$composeFile = Join-Path $PSScriptRoot 'compose.comment.yaml'
$projectName = "xiaohashu-$RunId"

function Invoke-DockerChecked {
    param([string[]]$Arguments)
    $result = @(& docker @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed (exit $LASTEXITCODE): $($result -join "`n")" }
    return $result
}

function Invoke-ComposeChecked {
    param([string[]]$Arguments)
    Invoke-DockerChecked -Arguments (@('compose', '--env-file', $envFile, '-f', $composeFile, '--project-name', $projectName) + $Arguments)
}

function New-RandomDigits {
    param([ValidateRange(1, 18)][int]$Length)
    $builder = [Text.StringBuilder]::new($Length)
    while ($builder.Length -lt $Length) {
        $bytes = [byte[]]::new($Length - $builder.Length)
        [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        foreach ($byte in $bytes) {
            [void]$builder.Append([char]([int][char]'0' + ($byte % 10)))
            if ($builder.Length -eq $Length) { break }
        }
    }
    $builder.ToString()
}

function Get-UsedMemoryMiB {
    $lines = Invoke-DockerChecked -Arguments @('stats', '--no-stream', '--format', '{{.MemUsage}}')
    $sum = 0.0
    foreach ($line in $lines) {
        if ($line -notmatch '^\s*([0-9]+(?:\.[0-9]+)?)(B|kB|MB|GB|KiB|MiB|GiB)\s*/') { continue }
        $value = [double]$Matches[1]
        $unit = $Matches[2]
        if ($unit -eq 'GiB') { $sum += $value * 1024 }
        elseif ($unit -eq 'GB') { $sum += $value * 1000 }
        elseif ($unit -eq 'MiB') { $sum += $value }
        elseif ($unit -eq 'MB') { $sum += $value / 1.048576 }
        elseif ($unit -eq 'KiB' -or $unit -eq 'kB') { $sum += $value / 1024 }
        else { $sum += $value / 1048576 }
    }
    [math]::Round($sum, 0)
}

function Save-Manifest {
    $tempPath = "$manifestPath.tmp"
    [IO.File]::WriteAllText($tempPath, ((ConvertTo-Json -InputObject $manifest -Depth 20) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $tempPath -Destination $manifestPath -Force
}

function Get-Details {
    param([string]$Id)
    $raw = Invoke-DockerChecked -Arguments @('inspect', $Id)
    ($raw -join "`n") | ConvertFrom-Json -Depth 30
}

function Update-ManifestResources {
    $ids = @(Invoke-DockerChecked -Arguments @('ps', '--all', '--quiet', '--filter', "label=codex.acceptance.run=$RunId"))
    $resources = @()
    foreach ($id in $ids) {
        if ([string]::IsNullOrWhiteSpace([string]$id)) { continue }
        $detail = Get-Details -Id ([string]$id)
        if ($detail[0].Config.Labels.'codex.acceptance.run' -ne $RunId) { throw "Container '$id' has unexpected acceptance labels." }
        $resources += [pscustomobject]@{
            service = $detail[0].Config.Labels.'com.docker.compose.service'
            id = $detail[0].Id
            name = $detail[0].Name.TrimStart('/')
            image = $detail[0].Config.Image
            status = 'created'
            state = $detail[0].State.Status
            health = if ($detail[0].State.Health) { $detail[0].State.Health.Status } else { 'none' }
            startedAt = $detail[0].State.StartedAt
        }
    }
    $manifest.containers = @($resources | Sort-Object service)
    $networkIds = @(Invoke-DockerChecked -Arguments @('network', 'ls', '--quiet', '--filter', "label=codex.acceptance.run=$RunId"))
    if ($networkIds.Count -eq 1) {
        $network = (Invoke-DockerChecked -Arguments @('network', 'inspect', [string]$networkIds[0]) -join "`n" | ConvertFrom-Json -Depth 20)[0]
        $manifest.networks = @([pscustomobject]@{ id = $network.Id; name = $network.Name; status = 'created' })
    }
    $volumeNames = @(Invoke-DockerChecked -Arguments @('volume', 'ls', '--quiet', '--filter', "label=codex.acceptance.run=$RunId"))
    $manifest.volumes = @($volumeNames | ForEach-Object { [pscustomobject]@{ name = [string]$_; status = 'created' } })
}

function Get-FreeMemoryMiB {
    $totalMemoryMiB = [math]::Round(([double](Invoke-DockerChecked -Arguments @('info', '--format', '{{.MemTotal}}')) / 1MB), 0)
    return [math]::Round($totalMemoryMiB - (Get-UsedMemoryMiB), 0)
}

function Start-FixtureService {
    param([Parameter(Mandatory = $true)][string]$Service)

    $container = $manifest.containers | Where-Object service -eq $Service | Select-Object -First 1
    if ($null -eq $container -or $container.status -ne 'created' -or [string]::IsNullOrWhiteSpace([string]$container.id)) {
        throw "Manifest does not contain a created container ID for '$Service'."
    }

    $details = Get-Details -Id $container.id
    $labels = $details[0].Config.Labels
    if ($labels.'codex.acceptance.run' -ne $RunId -or $labels.'codex.acceptance.project' -ne 'xiaohashu' -or
        $labels.'com.docker.compose.project' -ne $projectName) {
        throw "Refusing to start '$Service': its manifest container ID does not match the isolated project labels."
    }

    if ($details[0].State.Status -in @('created', 'exited')) {
        $freeMemoryMiB = Get-FreeMemoryMiB
        if ($freeMemoryMiB -lt 2000) {
            throw "Resource gate paused '$Service' startup: Docker has $freeMemoryMiB MiB free; at least 2000 MiB is required."
        }
        $null = Invoke-DockerChecked -Arguments @('start', [string]$container.id)
    } elseif ($details[0].State.Status -ne 'running') {
        throw "Refusing to start '$Service' from state '$($details[0].State.Status)'; inspect the manifest and container first."
    }

    $deadline = [DateTime]::UtcNow.AddSeconds(300)
    while ([DateTime]::UtcNow -lt $deadline) {
        $details = Get-Details -Id $container.id
        $state = [string]$details[0].State.Status
        $health = if ($details[0].State.Health) { [string]$details[0].State.Health.Status } else { 'none' }
        if ($state -eq 'running' -and ($health -eq 'healthy' -or $health -eq 'none')) {
            Update-ManifestResources
            Save-Manifest
            return
        }
        if ($state -in @('exited', 'dead') -or $health -eq 'unhealthy') {
            throw "Isolated service '$Service' failed to become healthy (state=$state, health=$health)."
        }
        Start-Sleep -Seconds 5
    }
    throw "Timed out waiting for isolated service '$Service' to become healthy."
}

function Get-SharedSnapshot {
    $ids = @(Invoke-DockerChecked -Arguments @('ps', '--all', '--quiet') | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    $result = @()
    foreach ($id in $ids) {
        $details = Get-Details -Id ([string]$id)
        if ($details[0].Config.Labels.'codex.acceptance.run' -eq $RunId) { continue }
        $result += [pscustomobject]@{
            id = $details[0].Id
            name = $details[0].Name.TrimStart('/')
            startedAt = $details[0].State.StartedAt
            state = $details[0].State.Status
            health = if ($details[0].State.Health) { $details[0].State.Health.Status } else { 'none' }
        }
    }
    return $result
}

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or -not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw "No resumable runtime manifest and Compose environment exist for RunId '$RunId'."
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -Depth 20
if ($manifest.schemaVersion -ne 1 -or $manifest.runId -ne $RunId -or $manifest.stage -ne $Stage -or $manifest.projectName -ne $projectName) {
    throw 'Recovery fixture identity does not match the requested RunId and stage.'
}
$sharedBefore = @($manifest.sharedContainersBefore)
$sharedCurrent = @(Get-SharedSnapshot)
foreach ($previous in $sharedBefore) {
    $current = $sharedCurrent | Where-Object id -eq $previous.id | Select-Object -First 1
    if ($null -eq $current -or $current.startedAt -ne $previous.startedAt -or $current.state -ne $previous.state) {
        throw "Shared container '$($previous.name)' changed state since fixture preparation; refusing to continue."
    }
}

$null = & (Join-Path $PSScriptRoot 'Test-RecoveryFixtureHarness.ps1') -RunId $RunId -Stage $Stage
foreach ($service in @('mysql', 'redis', 'nacos', 'zookeeper', 'rocketmq-namesrv', 'rocketmq-broker', 'cassandra')) {
    Start-FixtureService -Service $service
}

if ([string]::IsNullOrWhiteSpace([string]$manifest.userId) -or [string]::IsNullOrWhiteSpace([string]$manifest.noteId)) {
    $userId = [long](New-RandomDigits -Length 18)
    $noteId = [long](New-RandomDigits -Length 18)
    $xiaohashuId = New-RandomDigits -Length 15
    $syntheticPhone = '000' + (New-RandomDigits -Length 8)
    $nickname = "acc-$($RunId.Substring(7))"
    $sql = @"
INSERT INTO xiaohashu.t_user (id, xiaohashu_id, nickname, phone, status, is_deleted)
VALUES ($userId, '$xiaohashuId', '$nickname', '$syntheticPhone', 0, b'0');
INSERT INTO xiaohashu.t_user_count (user_id) VALUES ($userId);
INSERT INTO xiaohashu.t_note (id, title, is_content_empty, creator_id, type, visible, status)
VALUES ($noteId, 'acceptance-$RunId', b'1', $userId, 0, 0, 0);
INSERT INTO xiaohashu.t_note_count (note_id) VALUES ($noteId);
"@
    $encoded = [Convert]::ToBase64String([Text.UTF8Encoding]::new($false).GetBytes($sql))
    $command = 'printf ''%s'' ''' + $encoded + ''' | base64 -d | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root --database=xiaohashu --batch --raw --skip-column-names'
    $mysql = $manifest.containers | Where-Object service -eq 'mysql' | Select-Object -First 1
    $null = Invoke-DockerChecked -Arguments @('exec', $mysql.id, 'sh', '-ec', $command)
    $manifest.userId = [string]$userId
    $manifest.noteId = [string]$noteId
    Save-Manifest
}

$cassandra = $manifest.containers | Where-Object service -eq 'cassandra' | Select-Object -First 1
$null = Invoke-DockerChecked -Arguments @('exec', $cassandra.id, 'cqlsh', '-e',
    'CREATE TABLE IF NOT EXISTS xiaohashu.note_content (id uuid PRIMARY KEY, content text, is_deleted boolean);')

$usedBeforeAppsMiB = Get-UsedMemoryMiB
$totalMemoryMiB = [math]::Round(([double](Invoke-DockerChecked -Arguments @('info', '--format', '{{.MemTotal}}')) / 1MB), 0)
$freeBeforeAppsMiB = [math]::Round($totalMemoryMiB - $usedBeforeAppsMiB, 0)
if ($freeBeforeAppsMiB -lt 2000) { throw "Resource gate paused app startup: only $freeBeforeAppsMiB MiB remains; at least 2000 MiB is required. Existing fixture resources are recorded in the manifest." }
$null = Invoke-ComposeChecked -Arguments @('config', '--quiet')
foreach ($service in @('distributed-id-generator', 'user', 'count', 'kv', 'comment')) {
    Start-FixtureService -Service $service
}
$comment = $manifest.containers | Where-Object service -eq 'comment' | Select-Object -First 1
$detail = Get-Details -Id $comment.id
$binding = $detail[0].NetworkSettings.Ports.'8093/tcp' | Select-Object -First 1
if ($null -eq $binding -or $binding.HostIp -ne '127.0.0.1') { throw 'Comment fixture endpoint did not bind exclusively to loopback.' }
$manifest.commentBaseUrl = "http://127.0.0.1:$($binding.HostPort)"

$sharedAfter = @(Get-SharedSnapshot)
$changes = @()
foreach ($previous in $sharedBefore) {
    $current = $sharedAfter | Where-Object id -eq $previous.id | Select-Object -First 1
    if ($null -eq $current -or $current.startedAt -ne $previous.startedAt -or $current.state -ne $previous.state) { $changes += $previous.name }
}
$manifest.sharedContainersAfter = $sharedAfter
$manifest.sharedContainersUnchanged = ($changes.Count -eq 0)
$usedAfterMiB = Get-UsedMemoryMiB
if ($manifest.resourceGate.PSObject.Properties.Name -contains 'usedAfterMiB') { $manifest.resourceGate.usedAfterMiB = $usedAfterMiB }
else { $manifest.resourceGate | Add-Member -NotePropertyName usedAfterMiB -NotePropertyValue $usedAfterMiB }
$freeAfterMiB = [math]::Round($totalMemoryMiB - $usedAfterMiB, 0)
if ($manifest.resourceGate.PSObject.Properties.Name -contains 'freeAfterMiB') { $manifest.resourceGate.freeAfterMiB = $freeAfterMiB }
else { $manifest.resourceGate | Add-Member -NotePropertyName freeAfterMiB -NotePropertyValue $freeAfterMiB }
Save-Manifest
if ($changes.Count -gt 0) { throw "Shared containers changed state: $($changes -join ', ')" }
$null = & (Join-Path $PSScriptRoot 'Test-RecoveryFixtureHarness.ps1') -RunId $RunId -Stage $Stage
[pscustomobject]@{
    runId = $RunId
    stage = $Stage
    result = 'prepared'
    endpoint = $manifest.commentBaseUrl
    containerCount = @($manifest.containers | Where-Object status -eq 'created').Count
    volumeCount = @($manifest.volumes | Where-Object status -eq 'created').Count
    freeMemoryMiB = $freeAfterMiB
    sharedContainersUnchanged = $manifest.sharedContainersUnchanged
}
