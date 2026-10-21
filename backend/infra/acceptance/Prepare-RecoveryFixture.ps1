[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^accept-[0-9a-f]{12}$')]
    [string]$RunId,
    [Parameter(Mandatory = $true)]
    [ValidateSet('Comment', 'Canal')]
    [string]$Stage,
    [string]$SourceMySqlContainer = 'xiaohashu-mysql-1'
)

$ErrorActionPreference = 'Stop'
$env:MSYS_NO_PATHCONV = '1'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runDirectory = Join-Path $PSScriptRoot "../.runtime/acceptance/$RunId"
$manifestPath = Join-Path $runDirectory 'manifest.json'
$composeFile = Join-Path $PSScriptRoot "compose.$($Stage.ToLowerInvariant()).yaml"
$projectName = "xiaohashu-$RunId"

function Invoke-DockerChecked {
    param([string[]]$Arguments)
    $result = @(& docker @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed (exit $LASTEXITCODE): $($result -join "`n")" }
    return $result
}

function Invoke-ComposeChecked {
    param([string[]]$Arguments)
    $composeArgs = @('compose', '--env-file', (Join-Path $runDirectory 'compose.env'), '-f', $composeFile, '--project-name', $projectName) + $Arguments
    return Invoke-DockerChecked -Arguments $composeArgs
}

function Write-Utf8 {
    param([string]$Path, [string]$Text)
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}

function Save-Manifest {
    $tempPath = "$manifestPath.tmp"
    Write-Utf8 -Path $tempPath -Text ((ConvertTo-Json -InputObject $manifest -Depth 16) + [Environment]::NewLine)
    Move-Item -LiteralPath $tempPath -Destination $manifestPath -Force
}

function New-RandomHex {
    param([ValidateRange(16, 64)][int]$Bytes = 32)
    $buffer = [byte[]]::new($Bytes)
    [Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
    return [Convert]::ToHexString($buffer).ToLowerInvariant()
}

function New-RandomDigits {
    param([ValidateRange(1, 18)][int]$Length)
    $digits = [Text.StringBuilder]::new($Length)
    while ($digits.Length -lt $Length) {
        $buffer = [byte[]]::new($Length - $digits.Length)
        [Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
        foreach ($byte in $buffer) {
            [void]$digits.Append([char]([int][char]'0' + ($byte % 10)))
            if ($digits.Length -eq $Length) { break }
        }
    }
    return $digits.ToString()
}

function Get-ContainerSnapshots {
    param([string[]]$Ids)
    if ($Ids.Count -eq 0) { return @() }
    $raw = Invoke-DockerChecked -Arguments (@('inspect') + $Ids)
    $items = ($raw -join "`n") | ConvertFrom-Json -Depth 30
    return @($items | ForEach-Object {
        [pscustomobject]@{
            id = $_.Id
            name = ([string]$_.Name).TrimStart('/')
            startedAt = $_.State.StartedAt
            state = $_.State.Status
            health = if ($_.State.Health) { $_.State.Health.Status } else { 'none' }
        }
    })
}

function Get-AllContainerSnapshots {
    $ids = @(Invoke-DockerChecked -Arguments @('ps', '--all', '--quiet') | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    return Get-ContainerSnapshots -Ids $ids
}

function Get-UsedMemoryMiB {
    $lines = Invoke-DockerChecked -Arguments @('stats', '--no-stream', '--format', '{{.MemUsage}}')
    $sum = 0.0
    foreach ($line in $lines) {
        if ($line -notmatch '^\s*([0-9]+(?:\.[0-9]+)?)(B|kB|MB|GB|KiB|MiB|GiB)\s*/') { continue }
        $value = [double]$Matches[1]
        $sum += switch ($Matches[2]) {
            'GiB' { $value * 1024 }
            'GB' { $value * 1000 }
            'MiB' { $value }
            'MB' { $value / 1.048576 }
            'KiB' { $value / 1024 }
            'kB' { $value / 1024 }
            default { $value / 1MB }
        }
    }
    return [math]::Round($sum, 0)
}

function Update-ManifestResources {
    $ids = @(Invoke-DockerChecked -Arguments @('ps', '--all', '--quiet', '--filter', "label=codex.acceptance.run=$RunId") |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    $manifest.containers = @(Get-ContainerSnapshots -Ids $ids | ForEach-Object {
        $detail = (Invoke-DockerChecked -Arguments @('inspect', $_.id) -join "`n") | ConvertFrom-Json -Depth 30
        [pscustomobject]@{
            service = $detail[0].Config.Labels.'com.docker.compose.service'
            id = $_.id
            name = $_.name
            image = $detail[0].Config.Image
            status = 'created'
            state = $_.state
            health = $_.health
            startedAt = $_.startedAt
        }
    })

    $networkIds = @(Invoke-DockerChecked -Arguments @('network', 'ls', '--quiet', '--filter', "label=codex.acceptance.run=$RunId"))
    $manifest.networks = @()
    foreach ($id in $networkIds) {
        if ([string]::IsNullOrWhiteSpace([string]$id)) { continue }
        $detail = (Invoke-DockerChecked -Arguments @('network', 'inspect', $id) -join "`n") | ConvertFrom-Json -Depth 20
        $manifest.networks += [pscustomobject]@{ id = $detail[0].Id; name = $detail[0].Name; status = 'created' }
    }

    $volumeNames = @(Invoke-DockerChecked -Arguments @('volume', 'ls', '--quiet', '--filter', "label=codex.acceptance.run=$RunId"))
    $manifest.volumes = @()
    foreach ($name in $volumeNames) {
        if ([string]::IsNullOrWhiteSpace([string]$name)) { continue }
        $detail = (Invoke-DockerChecked -Arguments @('volume', 'inspect', $name) -join "`n") | ConvertFrom-Json -Depth 20
        $manifest.volumes += [pscustomobject]@{ name = $detail[0].Name; status = 'created' }
    }
}

function Invoke-FixtureSql {
    param([string]$ContainerId, [string]$Sql)
    $encoded = [Convert]::ToBase64String([Text.UTF8Encoding]::new($false).GetBytes($Sql))
    $command = 'printf ''%s'' ''' + $encoded + ''' | base64 -d | MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root --database=xiaohashu --batch --raw --skip-column-names'
    return Invoke-DockerChecked -Arguments @('exec', $ContainerId, 'sh', '-ec', $command)
}

function Export-SourceSchema {
    $databases = if ($Stage -eq 'Comment') { 'xiaohashu leaf' } else { 'xiaohashu' }
    $command = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump --user=root --no-data --skip-comments --skip-dump-date --set-gtid-purged=OFF --routines --triggers --databases ' + $databases
    $dump = Invoke-DockerChecked -Arguments @('exec', $SourceMySqlContainer, 'sh', '-ec', $command)
    $schema = ($dump -join "`n") -replace '(?i)\sAUTO_INCREMENT=\d+', ''
    $path = Join-Path $runDirectory 'schema-only.sql'
    Write-Utf8 -Path $path -Text ($schema + [Environment]::NewLine)
    return $path
}

function Initialize-MySql {
    param([string]$ContainerId, [string]$SchemaPath, [string]$CanalPassword)
    $null = Invoke-DockerChecked -Arguments @('cp', $SchemaPath, "$ContainerId`:/tmp/acceptance-schema.sql")
    $import = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root < /tmp/acceptance-schema.sql'
    $null = Invoke-DockerChecked -Arguments @('exec', $ContainerId, 'sh', '-ec', $import)
    if ($Stage -eq 'Comment') {
        $null = Invoke-FixtureSql -ContainerId $ContainerId -Sql @'
INSERT INTO leaf.leaf_alloc (biz_tag, max_id, step, description)
VALUES ('leaf-segment-comment-id', 1, 2000, 'isolated recovery acceptance');
'@
    } else {
        $grant = @"
CREATE USER 'canal'@'%' IDENTIFIED BY '$CanalPassword';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
"@
        $null = Invoke-FixtureSql -ContainerId $ContainerId -Sql $grant
    }
}

function Initialize-Cassandra {
    param([string]$ContainerId)
    $null = Invoke-DockerChecked -Arguments @('exec', $ContainerId, 'cqlsh', '-e',
        "CREATE KEYSPACE IF NOT EXISTS xiaohashu WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};")
    $null = Invoke-DockerChecked -Arguments @('exec', $ContainerId, 'cqlsh', '-e',
        'CREATE TABLE IF NOT EXISTS xiaohashu.note_content (id uuid PRIMARY KEY, content text, is_deleted boolean);')
    $cqlPath = Join-Path $projectRoot 'database/23.1-comment-content.cql'
    $null = Invoke-DockerChecked -Arguments @('cp', $cqlPath, "$ContainerId`:/tmp/comment-schema.cql")
    $null = Invoke-DockerChecked -Arguments @('exec', $ContainerId, 'cqlsh', '-f', '/tmp/comment-schema.cql')
}

function Seed-IsolatedBusinessRows {
    param([string]$ContainerId)
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
    $null = Invoke-FixtureSql -ContainerId $ContainerId -Sql $sql
    $manifest.userId = [string]$userId
    $manifest.noteId = [string]$noteId
}

function Assert-SharedContainersUnchanged {
    $after = Get-AllContainerSnapshots
    $afterById = @{}
    foreach ($item in $after) { $afterById[$item.id] = $item }
    $changed = @($manifest.sharedContainersBefore | Where-Object {
        $current = $afterById[$_.id]
        $null -eq $current -or $current.startedAt -ne $_.startedAt -or $current.state -ne $_.state
    })
    $manifest.sharedContainersAfter = $after
    $manifest.sharedContainersUnchanged = ($changed.Count -eq 0)
    return ,$changed
}

if (Test-Path -LiteralPath $runDirectory) { throw "Run directory already exists; refusing to overwrite: $runDirectory" }
if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) { throw "Stage Compose file is missing: $composeFile" }
$null = Invoke-DockerChecked -Arguments @('image', 'inspect', 'mysql:8.0.27')
$stageImages = if ($Stage -eq 'Comment') {
    @('redis:8', 'nacos/nacos-server:v2.2.3', 'zookeeper:3.5.6', 'apache/rocketmq:5.3.1', 'cassandra:5.0.4',
      'xiaohashu/distributed-id-generator:local', 'xiaohashu/user:local', 'xiaohashu/count:local', 'xiaohashu/kv:local', 'xiaohashu/comment:local')
} else {
    @('redis:8', 'nacos/nacos-server:v2.2.3', 'elasticsearch:7.3.0', 'canal/canal-server:v1.1.7', 'xiaohashu/search:local')
}
foreach ($image in $stageImages) { $null = Invoke-DockerChecked -Arguments @('image', 'inspect', $image) }

$existingContainers = @(Invoke-DockerChecked -Arguments @('ps', '--all', '--quiet', '--filter', "label=codex.acceptance.run=$RunId"))
$existingNetworks = @(Invoke-DockerChecked -Arguments @('network', 'ls', '--quiet', '--filter', "label=codex.acceptance.run=$RunId"))
$existingVolumes = @(Invoke-DockerChecked -Arguments @('volume', 'ls', '--quiet', '--filter', "label=codex.acceptance.run=$RunId"))
if ($existingContainers.Count -gt 0 -or $existingNetworks.Count -gt 0 -or $existingVolumes.Count -gt 0) {
    throw "Resources labelled with RunId '$RunId' already exist; inspect them individually before reusing this RunId."
}
$source = (Invoke-DockerChecked -Arguments @('inspect', $SourceMySqlContainer) -join "`n") | ConvertFrom-Json -Depth 30
if ($source[0].State.Status -ne 'running') { throw "Source schema container '$SourceMySqlContainer' is not running." }

$dockerMemory = Invoke-DockerChecked -Arguments @('info', '--format', '{{.MemTotal}}')
$totalMemoryMiB = [math]::Round(([double]$dockerMemory / 1MB), 0)
$usedBeforeMiB = Get-UsedMemoryMiB
$freeBeforeMiB = [math]::Round($totalMemoryMiB - $usedBeforeMiB, 0)
$minimumFreeMiB = 2000
if ($freeBeforeMiB -lt $minimumFreeMiB) {
    throw "Resource gate stopped $Stage fixture creation: Docker has $freeBeforeMiB MiB free; at least $minimumFreeMiB MiB is required. No fixture resource was created."
}

New-Item -ItemType Directory -Path $runDirectory | Out-Null
$mysqlPassword = New-RandomHex
$redisPassword = New-RandomHex
$canalPassword = New-RandomHex
$runtimePath = $runDirectory.Replace('\', '/')
$composeEnvironment = @"
COMPOSE_PROJECT_NAME=$projectName
RUN_ID=$RunId
COMMENT_IMAGE=xiaohashu/comment:local
MYSQL_ROOT_PASSWORD=$mysqlPassword
REDIS_PASSWORD=$redisPassword
CANAL_DB_USERNAME=canal
CANAL_DB_PASSWORD=$canalPassword
APP_CONFIG_FILE=$runtimePath/application-docker.yml
DICTIONARY_FILE=$runtimePath/mydict_hot_update.dic
LEAF_CONFIG_FILE=$runtimePath/leaf.properties
BROKER_CONFIG_FILE=$runtimePath/broker.conf
"@
Write-Utf8 -Path (Join-Path $runDirectory 'compose.env') -Text $composeEnvironment

$applicationConfig = @'
spring:
  datasource:
    url: jdbc:mysql://mysql:3306/xiaohashu?useUnicode=true&characterEncoding=utf-8&autoReconnect=true&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: ${MYSQL_ROOT_PASSWORD}
    druid:
      initial-size: 1
      min-idle: 1
      max-active: 8
      connection-properties: config.decrypt=false
      filter:
        config:
          enabled: false
  data:
    redis:
      host: redis
      port: 6379
      password: ${REDIS_PASSWORD}
      timeout: 5s
      connect-timeout: 5s
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
  cassandra:
    keyspace-name: xiaohashu
    contact-points: cassandra
    port: 9042
    local-datacenter: datacenter1
  cloud:
    nacos:
      discovery:
        server-addr: nacos:8848
rocketmq:
  name-server: rocketmq-namesrv:9876
elasticsearch:
  address: elasticsearch:9200
  hotUpdateExtDict: /app/dictionary/mydict_hot_update.dic
canal:
  address: canal:11111
  subscribe: xiaohashu.t_note,xiaohashu.t_user,xiaohashu.t_note_count,xiaohashu.t_user_count
'@
Write-Utf8 -Path (Join-Path $runDirectory 'application-docker.yml') -Text $applicationConfig
Write-Utf8 -Path (Join-Path $runDirectory 'mydict_hot_update.dic') -Text ''

$leafConfig = @"
leaf.name=com.sankuai.leaf.opensource.test
leaf.segment.enable=true
leaf.jdbc.url=jdbc:mysql://mysql:3306/leaf?useUnicode=true&characterEncoding=utf-8&autoReconnect=true&useSSL=false&serverTimezone=Asia/Shanghai
leaf.jdbc.username=root
leaf.jdbc.password=$mysqlPassword
leaf.snowflake.enable=true
leaf.snowflake.zk.address=zookeeper:2181
leaf.snowflake.port=2222
"@
Write-Utf8 -Path (Join-Path $runDirectory 'leaf.properties') -Text $leafConfig
$brokerConfig = [IO.File]::ReadAllText((Join-Path $projectRoot 'infra/docker/broker.conf'))
if ($Stage -eq 'Comment') {
    $brokerConfig = $brokerConfig -replace '(?m)^brokerIP1\s*=.*$', 'brokerIP1 = rocketmq-broker'
    $brokerConfig += "`nmessageDelayLevel = 1s 2s 3s 5s 10s 20s`nmaxReconsumeTimes = 3`n"
}
Write-Utf8 -Path (Join-Path $runDirectory 'broker.conf') -Text $brokerConfig

$serviceNames = if ($Stage -eq 'Comment') {
    @('mysql', 'redis', 'nacos', 'zookeeper', 'rocketmq-namesrv', 'rocketmq-broker', 'cassandra', 'distributed-id-generator', 'user', 'count', 'kv', 'comment')
} else { @('mysql', 'redis', 'nacos', 'elasticsearch', 'canal', 'search') }
$volumeNames = if ($Stage -eq 'Comment') {
    @('mysql', 'redis', 'zookeeper-data', 'zookeeper-datalog', 'zookeeper-logs', 'rocketmq-store', 'cassandra')
} else { @('mysql', 'redis', 'elasticsearch', 'canal') }
$manifest = [pscustomobject]@{
    schemaVersion = 1
    runId = $RunId
    stage = $Stage
    projectName = $projectName
    userId = $null
    noteId = $null
    commentBaseUrl = $null
    searchBaseUrl = $null
    containers = @($serviceNames | ForEach-Object { [pscustomobject]@{ service = $_; id = $null; name = $null; status = 'planned' } })
    networks = @([pscustomobject]@{ id = $null; name = $projectName; status = 'planned' })
    volumes = @($volumeNames | ForEach-Object { [pscustomobject]@{ name = "$projectName-$_"; status = 'planned' } })
    testEvents = @()
    resourceGate = [pscustomobject]@{ totalMiB = $totalMemoryMiB; usedBeforeMiB = $usedBeforeMiB; freeBeforeMiB = $freeBeforeMiB; minimumFreeMiB = $minimumFreeMiB }
    sharedContainersBefore = Get-AllContainerSnapshots
    sharedContainersAfter = @()
    sharedContainersUnchanged = $null
}
Save-Manifest
$schemaPath = Export-SourceSchema

try {
    $baseServices = if ($Stage -eq 'Comment') {
        @('mysql', 'redis', 'nacos', 'zookeeper', 'rocketmq-namesrv', 'rocketmq-broker', 'cassandra')
    } else { @('mysql', 'redis', 'nacos', 'elasticsearch') }
    $null = Invoke-ComposeChecked -Arguments (@('up', '--detach', '--wait', '--wait-timeout', '300') + $baseServices)
    Update-ManifestResources
    Save-Manifest

    $mysqlId = ($manifest.containers | Where-Object service -eq 'mysql' | Select-Object -First 1).id
    if ([string]::IsNullOrWhiteSpace([string]$mysqlId)) { throw 'Isolated MySQL did not start.' }
    Initialize-MySql -ContainerId $mysqlId -SchemaPath $schemaPath -CanalPassword $canalPassword

    if ($Stage -eq 'Comment') {
        $cassandraId = ($manifest.containers | Where-Object service -eq 'cassandra' | Select-Object -First 1).id
        Initialize-Cassandra -ContainerId $cassandraId
        Seed-IsolatedBusinessRows -ContainerId $mysqlId | Out-Null
        $appServices = @('distributed-id-generator', 'user', 'count', 'kv', 'comment')
        $freeBeforeAppsMiB = [math]::Round($totalMemoryMiB - (Get-UsedMemoryMiB), 0)
        if ($freeBeforeAppsMiB -lt 2000) { throw "Resource gate paused app startup: only $freeBeforeAppsMiB MiB remains; at least 2000 MiB is required. Existing fixture resources are recorded in the manifest." }
        $null = Invoke-ComposeChecked -Arguments (@('up', '--detach', '--wait', '--wait-timeout', '300') + $appServices)
    } else {
        $freeBeforeAppsMiB = [math]::Round($totalMemoryMiB - (Get-UsedMemoryMiB), 0)
        if ($freeBeforeAppsMiB -lt 2000) { throw "Resource gate paused app startup: only $freeBeforeAppsMiB MiB remains; at least 2000 MiB is required. Existing fixture resources are recorded in the manifest." }
        $null = Invoke-ComposeChecked -Arguments @('up', '--detach', '--wait', '--wait-timeout', '300', 'canal', 'search')
        Seed-IsolatedBusinessRows -ContainerId $mysqlId | Out-Null
    }

    Update-ManifestResources
    $apiService = if ($Stage -eq 'Comment') { 'comment' } else { 'search' }
    $container = $manifest.containers | Where-Object service -eq $apiService | Select-Object -First 1
    $detail = (Invoke-DockerChecked -Arguments @('inspect', $container.id) -join "`n") | ConvertFrom-Json -Depth 30
    $containerPort = if ($Stage -eq 'Comment') { '8093/tcp' } else { '8092/tcp' }
    $binding = $detail[0].NetworkSettings.Ports.$containerPort | Select-Object -First 1
    if ($null -eq $binding -or $binding.HostIp -ne '127.0.0.1') { throw "$apiService endpoint did not bind exclusively to loopback." }
    if ($Stage -eq 'Comment') { $manifest.commentBaseUrl = "http://127.0.0.1:$($binding.HostPort)" }
    else { $manifest.searchBaseUrl = "http://127.0.0.1:$($binding.HostPort)" }

    $changed = Assert-SharedContainersUnchanged
    $manifest.resourceGate | Add-Member -NotePropertyName usedAfterMiB -NotePropertyValue (Get-UsedMemoryMiB)
    $manifest.resourceGate | Add-Member -NotePropertyName freeAfterMiB -NotePropertyValue ([math]::Round($totalMemoryMiB - $manifest.resourceGate.usedAfterMiB, 0))
    Update-ManifestResources
    Save-Manifest
    if ($changed.Count -gt 0) { throw 'A pre-existing container changed state while preparing the isolated fixture; see the manifest baseline comparison.' }

    $null = & (Join-Path $PSScriptRoot 'Test-RecoveryFixtureHarness.ps1') -RunId $RunId -Stage $Stage
    [pscustomobject]@{
        runId = $RunId
        stage = $Stage
        result = 'prepared'
        endpoint = if ($Stage -eq 'Comment') { $manifest.commentBaseUrl } else { $manifest.searchBaseUrl }
        containerCount = @($manifest.containers | Where-Object status -eq 'created').Count
        volumeCount = @($manifest.volumes | Where-Object status -eq 'created').Count
        usedMemoryMiB = $manifest.resourceGate.usedAfterMiB
        freeMemoryMiB = $manifest.resourceGate.freeAfterMiB
    }
} catch {
    try {
        Update-ManifestResources
        $changed = Assert-SharedContainersUnchanged
        Save-Manifest
    } catch { }
    throw
}
