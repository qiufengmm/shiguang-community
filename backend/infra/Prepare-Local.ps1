[CmdletBinding()]
param(
    [ValidateSet('ReuseExisting', 'Fresh')]
    [string]$Mode = 'ReuseExisting',
    [string]$DataRoot = $env:XIAOHASHU_DATA_ROOT,
    [string]$SeedConfigRoot,
    [switch]$Refresh
)

$ErrorActionPreference = 'Stop'
$defaultDataRoot = 'F:\docker'
if ([string]::IsNullOrWhiteSpace($DataRoot)) { $DataRoot = $defaultDataRoot }
$DataRoot = [IO.Path]::GetFullPath($DataRoot)
$runtimeRoot = Join-Path $PSScriptRoot '.runtime'
. (Join-Path $PSScriptRoot 'Resolve-ComposeVolumeNames.ps1')

function Get-ExistingComposeVolumeNames {
    $composeText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'compose.yaml'))
    $projectMatch = [regex]::Match($composeText, '(?m)^name:\s*([A-Za-z0-9_.-]+)\s*$')
    if (-not $projectMatch.Success) {
        throw 'ReuseExisting requires an explicit Compose project name in infra/compose.yaml to safely identify existing data volumes.'
    }
    $projectName = $projectMatch.Groups[1].Value
    $inspections = @()
    foreach ($serviceName in @('mysql', 'zookeeper', 'xxl-job-admin')) {
        $containerIds = @(& docker 'ps' '-a' '-q' '--filter' "label=com.docker.compose.project=$projectName" '--filter' "label=com.docker.compose.service=$serviceName")
        if ($LASTEXITCODE -ne 0) {
            throw "Could not inspect Compose containers for project '$projectName'. Ensure Docker is available; no replacement volume will be created."
        }
        foreach ($containerId in $containerIds) {
            if ([string]::IsNullOrWhiteSpace([string]$containerId)) { continue }
            $inspectJson = & docker 'inspect' '--type' 'container' ([string]$containerId) 2>$null
            if ($LASTEXITCODE -ne 0) { throw "Could not inspect existing container '$containerId'; refusing to guess its data volumes." }
            $inspections += @($inspectJson -join "`n" | ConvertFrom-Json -Depth 30)
        }
    }
    return Resolve-XiaohashuComposeVolumeNames -Inspections $inspections -ProjectName $projectName
}

function Assert-CachedVolumeNamesMatch([string]$ComposeEnvPath, [hashtable]$ExpectedVolumes) {
    $cachedComposeEnv = [IO.File]::ReadAllText($ComposeEnvPath)
    foreach ($key in $ExpectedVolumes.Keys) {
        $pattern = '(?m)^' + [regex]::Escape([string]$key) + "='([^']*)'\s*$"
        $match = [regex]::Match($cachedComposeEnv, $pattern)
        if (-not $match.Success -or $match.Groups[1].Value -cne [string]$ExpectedVolumes[$key]) {
            throw "infra/.runtime/compose.env has a stale $key. ReuseExisting found '$($ExpectedVolumes[$key])' from the existing Compose container mount. Use Fresh only with a new data root, or follow infra/RESTORE.md; no replacement volume will be created."
        }
    }
}

if ($Mode -eq 'Fresh' -and (Test-Path -LiteralPath $runtimeRoot)) {
    throw 'Fresh mode requires a clean project checkout without infra/.runtime so existing credentials and Canal position are preserved.'
}
$freshDataDirectories = @(
    'redis/data', 'nacos/data', 'nacos/logs', 'minio/data', 'cassandra5/data',
    'zookeeper/data', 'rocketmq/namesrv/logs', 'rocketmq/broker/logs',
    'rocketmq/broker/store', 'elasticsearch/data', 'elasticsearch/logs', 'frp/frps', 'frp/frpc'
)
if ($Mode -eq 'Fresh') {
    if (Test-Path -LiteralPath $DataRoot) {
        throw "Fresh mode requires a new, non-existing data directory to protect existing files: $DataRoot"
    }
    if ([string]::IsNullOrWhiteSpace($SeedConfigRoot)) {
        throw 'Fresh mode requires -SeedConfigRoot pointing to a trusted copy of static service configuration files.'
    }
    $SeedConfigRoot = [IO.Path]::GetFullPath($SeedConfigRoot)
    if (-not (Test-Path -LiteralPath $SeedConfigRoot -PathType Container)) {
        throw "The static configuration source does not exist: $SeedConfigRoot"
    }
    if ([string]::Equals($DataRoot, $SeedConfigRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Fresh mode cannot use the source configuration directory as its destination.'
    }

    $seedConfigurationPaths = @(
        'redis/modules', 'nacos/conf', 'cassandra5/config', 'zookeeper/conf',
        'rocketmq/namesrv/bin', 'rocketmq/broker/bin', 'elasticsearch/config',
        'elasticsearch/plugins', 'kibana/config'
    )
    foreach ($relativePath in $seedConfigurationPaths) {
        $sourcePath = Join-Path $SeedConfigRoot $relativePath
        if (-not (Test-Path -LiteralPath $sourcePath)) {
            throw "Fresh mode is missing static service configuration: $sourcePath"
        }
    }
    foreach ($requiredSeedFile in @(
        'redis/modules/libredis-roaring.so',
        'nacos/conf/application.properties',
        'cassandra5/config/jvm-server.options'
    )) {
        $sourcePath = Join-Path $SeedConfigRoot $requiredSeedFile
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Fresh mode is missing a required static configuration file: $sourcePath"
        }
    }
    foreach ($requiredSecret in @('MYSQL_ROOT_PASSWORD', 'MINIO_ROOT_PASSWORD', 'CANAL_DB_USERNAME', 'CANAL_DB_PASSWORD', 'REDIS_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($requiredSecret))) {
            throw "Fresh mode requires $requiredSecret in the PowerShell environment. No data directory has been created."
        }
    }
    New-Item -ItemType Directory -Path $DataRoot | Out-Null
    foreach ($relativePath in $seedConfigurationPaths) {
        $sourcePath = Join-Path $SeedConfigRoot $relativePath
        $destinationPath = Join-Path $DataRoot $relativePath
        New-Item -ItemType Directory -Path (Split-Path -Parent $destinationPath) -Force | Out-Null
        Copy-Item -LiteralPath $sourcePath -Destination $destinationPath -Recurse
    }
    foreach ($relativePath in $freshDataDirectories) {
        New-Item -ItemType Directory -Path (Join-Path $DataRoot $relativePath) -Force | Out-Null
    }
}
$envPath = Join-Path $runtimeRoot 'compose.env'
$requiredFiles = @('compose.env', 'nacos.properties', 'redis.conf', 'jvm-server.options', 'leaf.properties', 'canal-example/instance.properties')
$runtimeComplete = @($requiredFiles | Where-Object { -not (Test-Path -LiteralPath (Join-Path $runtimeRoot $_)) }).Count -eq 0
$rootMarkerPath = Join-Path $runtimeRoot 'data-root.txt'
$runtimeMatchesRoot = $false
if (Test-Path -LiteralPath $rootMarkerPath) {
    $savedDataRoot = [IO.Path]::GetFullPath((Get-Content -LiteralPath $rootMarkerPath -Raw).Trim())
    $runtimeMatchesRoot = [string]::Equals($savedDataRoot, $DataRoot, [StringComparison]::OrdinalIgnoreCase)
}
$volumeDefaults = if ($Mode -eq 'ReuseExisting') {
    Get-ExistingComposeVolumeNames
} else {
    @{
        MYSQL_DATA_VOLUME_NAME = 'xiaohashu-mysql-data'
        ZOOKEEPER_DATALOG_VOLUME_NAME = 'xiaohashu-zookeeper-datalog'
        ZOOKEEPER_LOGS_VOLUME_NAME = 'xiaohashu-zookeeper-logs'
        XXL_JOB_LOGS_VOLUME_NAME = 'xiaohashu-xxl-job-admin-logs'
    }
}
if ($Mode -eq 'ReuseExisting') {
    foreach ($key in $volumeDefaults.Keys) {
        $requestedVolume = [Environment]::GetEnvironmentVariable([string]$key)
        if (-not [string]::IsNullOrWhiteSpace($requestedVolume) -and $requestedVolume -cne [string]$volumeDefaults[$key]) {
            throw "Environment override $key='$requestedVolume' does not match the existing Compose mount '$($volumeDefaults[$key])'. Use Fresh only with a new data root or follow infra/RESTORE.md; no replacement volume will be created."
        }
    }
}
if ($runtimeComplete -and $runtimeMatchesRoot -and -not $Refresh) {
    Assert-CachedVolumeNamesMatch -ComposeEnvPath $envPath -ExpectedVolumes $volumeDefaults
    Write-Host 'Using the existing ignored infra/.runtime/ deployment settings and verified existing volume names.'
    return
}

function Read-ContainerEnvironment([string[]]$Names) {
    foreach ($containerName in $Names) {
        $inspection = & docker inspect $containerName 2>$null
        if ($LASTEXITCODE -ne 0) { continue }
        $containerInfo = $inspection | ConvertFrom-Json
        $settings = @{}
        foreach ($entry in $containerInfo[0].Config.Env) {
            $parts = $entry.Split('=', 2)
            if ($parts.Length -eq 2) { $settings[$parts[0]] = $parts[1] }
        }
        return $settings
    }
    return @{}
}

function New-LocalSecret {
    $bytes = New-Object byte[] 36
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

$mysqlSettings = @{}
$minioSettings = @{}
$canalSettings = @{}
$xxlSettings = @{}
$frpSettings = @{}
if ($Mode -eq 'ReuseExisting') {
    $mysqlSettings = Read-ContainerEnvironment @('mysql8.0', 'xiaohashu-mysql-1')
    $minioSettings = Read-ContainerEnvironment @('minio', 'xiaohashu-minio-1')
    $canalSettings = Read-ContainerEnvironment @('canal-server', 'xiaohashu-canal-1')
    $xxlSettings = Read-ContainerEnvironment @('xxl-job-admin', 'xiaohashu-xxl-job-admin-1')
    $frpSettings = Read-ContainerEnvironment @('xiaohashu-frps-1')
}
$redisConfigPath = Join-Path $DataRoot 'redis/conf/redis-roaring.conf'
if ($Mode -eq 'Fresh') {
    if ([string]::IsNullOrWhiteSpace($env:REDIS_PASSWORD)) { throw 'Fresh setup requires REDIS_PASSWORD in the PowerShell environment.' }
    $redisConfig = @"
port 6379
protected-mode yes
requirepass $($env:REDIS_PASSWORD)
dir /data
appendonly yes
loadmodule /etc/redis/modules/libredis-roaring.so
"@
    $redisPassword = $env:REDIS_PASSWORD
} else {
    $redisConfig = [IO.File]::ReadAllText($redisConfigPath)
    $redisPasswordMatch = [regex]::Match($redisConfig, '(?m)^requirepass\s+([^\r\n]+)')
    if (-not $redisPasswordMatch.Success) { throw 'The existing Redis requirepass setting was not found.' }
    $redisPassword = $redisPasswordMatch.Groups[1].Value.Trim().Trim('"')
}

$settings = [ordered]@{
    XIAOHASHU_DATA_ROOT = $DataRoot.Replace('\', '/')
    XIAOHASHU_UI_BIND_ADDR = if ($env:XIAOHASHU_UI_BIND_ADDR) { $env:XIAOHASHU_UI_BIND_ADDR } else { '0.0.0.0' }
    XIAOHASHU_UI_HOST_PORT = if ($env:XIAOHASHU_UI_HOST_PORT) { $env:XIAOHASHU_UI_HOST_PORT } else { '8088' }
    MYSQL_DATA_VOLUME_NAME = if ($env:MYSQL_DATA_VOLUME_NAME) { $env:MYSQL_DATA_VOLUME_NAME } else { $volumeDefaults.MYSQL_DATA_VOLUME_NAME }
    ZOOKEEPER_DATALOG_VOLUME_NAME = if ($env:ZOOKEEPER_DATALOG_VOLUME_NAME) { $env:ZOOKEEPER_DATALOG_VOLUME_NAME } else { $volumeDefaults.ZOOKEEPER_DATALOG_VOLUME_NAME }
    ZOOKEEPER_LOGS_VOLUME_NAME = if ($env:ZOOKEEPER_LOGS_VOLUME_NAME) { $env:ZOOKEEPER_LOGS_VOLUME_NAME } else { $volumeDefaults.ZOOKEEPER_LOGS_VOLUME_NAME }
    XXL_JOB_LOGS_VOLUME_NAME = if ($env:XXL_JOB_LOGS_VOLUME_NAME) { $env:XXL_JOB_LOGS_VOLUME_NAME } else { $volumeDefaults.XXL_JOB_LOGS_VOLUME_NAME }
    MYSQL_ROOT_PASSWORD = if ($Mode -eq 'Fresh') { $env:MYSQL_ROOT_PASSWORD } else { $mysqlSettings['MYSQL_ROOT_PASSWORD'] }
    MINIO_ROOT_USER = if ($Mode -eq 'Fresh') { $env:MINIO_ROOT_USER } else { $minioSettings['MINIO_ROOT_USER'] }
    MINIO_ROOT_PASSWORD = if ($Mode -eq 'Fresh') { $env:MINIO_ROOT_PASSWORD } else { $minioSettings['MINIO_ROOT_PASSWORD'] }
    CANAL_DB_USERNAME = if ($Mode -eq 'Fresh') { $env:CANAL_DB_USERNAME } else { $canalSettings['canal.instance.dbUsername'] }
    CANAL_DB_PASSWORD = if ($Mode -eq 'Fresh') { $env:CANAL_DB_PASSWORD } else { $canalSettings['canal.instance.dbPassword'] }
    XXL_JOB_MYSQL_PASSWORD = if ($Mode -eq 'Fresh') { $env:XXL_JOB_MYSQL_PASSWORD } else { $xxlSettings['SPRING_DATASOURCE_PASSWORD'] }
    REDIS_PASSWORD = $redisPassword
    FRP_TOKEN = $frpSettings['FRP_TOKEN']
    FRP_WEB_USER = 'admin'
    FRP_WEB_PASSWORD = $frpSettings['FRP_WEB_PASSWORD']
    FRP_SERVER_ADDR = if ($env:FRP_SERVER_ADDR) { $env:FRP_SERVER_ADDR } else { 'frps' }
    FRP_LOCAL_IP = if ($env:FRP_LOCAL_IP) { $env:FRP_LOCAL_IP } else { 'frontend' }
    FRP_LOCAL_PORT = if ($env:FRP_LOCAL_PORT) { $env:FRP_LOCAL_PORT } else { '80' }
    FRP_REMOTE_PORT = if ($env:FRP_REMOTE_PORT) { $env:FRP_REMOTE_PORT } else { '8000' }
    FRPS_HOST_BIND_ADDR = if ($env:FRPS_HOST_BIND_ADDR) { $env:FRPS_HOST_BIND_ADDR } else { '127.0.0.1' }
    FRPS_HOST_BIND_PORT = if ($env:FRPS_HOST_BIND_PORT) { $env:FRPS_HOST_BIND_PORT } else { '17000' }
    FRPS_HOST_WEB_PORT = if ($env:FRPS_HOST_WEB_PORT) { $env:FRPS_HOST_WEB_PORT } else { '17500' }
    FRPS_HOST_REMOTE_PORT = if ($env:FRPS_HOST_REMOTE_PORT) { $env:FRPS_HOST_REMOTE_PORT } else { '18000' }
}
if (-not $settings.MINIO_ROOT_USER -and $Mode -eq 'ReuseExisting') { $settings.MINIO_ROOT_USER = $minioSettings['MINIO_ACCESS_KEY'] }
if (-not $settings.MINIO_ROOT_USER -and $Mode -eq 'Fresh') { $settings.MINIO_ROOT_USER = 'minioadmin' }
if (-not $settings.MINIO_ROOT_PASSWORD -and $Mode -eq 'ReuseExisting') { $settings.MINIO_ROOT_PASSWORD = $minioSettings['MINIO_SECRET_KEY'] }
if (-not $settings.XXL_JOB_MYSQL_PASSWORD) { $settings.XXL_JOB_MYSQL_PASSWORD = $settings.MYSQL_ROOT_PASSWORD }
if ($Mode -eq 'Fresh') {
    $settings.FRP_TOKEN = if ($env:FRP_TOKEN) { $env:FRP_TOKEN } else { New-LocalSecret }
    $settings.FRP_WEB_PASSWORD = if ($env:FRP_WEB_PASSWORD) { $env:FRP_WEB_PASSWORD } else { New-LocalSecret }
} else {
    if (-not $settings.FRP_TOKEN) { $settings.FRP_TOKEN = New-LocalSecret }
    if (-not $settings.FRP_WEB_PASSWORD) { $settings.FRP_WEB_PASSWORD = New-LocalSecret }
}
foreach ($key in $settings.Keys) {
    if ([string]::IsNullOrWhiteSpace($settings[$key])) { throw "Missing existing infrastructure setting: $key. Set up the legacy container or provide its environment first." }
    if ($settings[$key] -match '[\r\n]') { throw "Unexpected multiline value for $key." }
}

New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$envLines = foreach ($key in $settings.Keys) {
    $literal = $settings[$key].Replace("'", "\'")
    "$key='$literal'"
}
$nacosProperties = [IO.File]::ReadAllText((Join-Path $DataRoot 'nacos/conf/application.properties'))
$nacosProperties = $nacosProperties -replace 'jdbc:mysql://[^/]+/nacos_config', 'jdbc:mysql://mysql:3306/nacos_config'
$nacosProperties = $nacosProperties -replace '(?m)^(db\.password(?:\.0)?|spring\.datasource\.password)\s*=.*$', ('$1=' + $settings.MYSQL_ROOT_PASSWORD)
[IO.File]::WriteAllText((Join-Path $runtimeRoot 'nacos.properties'), $nacosProperties, $utf8)
# This image's normal entrypoint automatically loads RedisBloom. Loading it
# again in the configuration makes Redis abort; keep only the Roaring line.
[IO.File]::WriteAllText((Join-Path $runtimeRoot 'redis.conf'), $redisConfig, $utf8)
$cassandraOptions = [IO.File]::ReadAllText((Join-Path $DataRoot 'cassandra5/config/jvm-server.options'))
$cassandraOptions = $cassandraOptions -replace '(?m)^-Xms[^\r\n]+', '-Xms512M'
$cassandraOptions = $cassandraOptions -replace '(?m)^-Xmx[^\r\n]+', '-Xmx512M'
$cassandraOptions += "`n-XX:MaxDirectMemorySize=256M`n-XX:ActiveProcessorCount=2`n"
[IO.File]::WriteAllText((Join-Path $runtimeRoot 'jvm-server.options'), $cassandraOptions, $utf8)
$leafProperties = @"
leaf.name=com.sankuai.leaf.opensource.test
leaf.segment.enable=true
leaf.jdbc.url=jdbc:mysql://mysql:3306/leaf?useUnicode=true&characterEncoding=utf-8&autoReconnect=true&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
leaf.jdbc.username=root
leaf.jdbc.password=$($settings.MYSQL_ROOT_PASSWORD)
leaf.snowflake.enable=true
leaf.snowflake.zk.address=zookeeper:2181
leaf.snowflake.port=2222
"@
[IO.File]::WriteAllText((Join-Path $runtimeRoot 'leaf.properties'), $leafProperties, $utf8)
$canalInstancePath = Join-Path $runtimeRoot 'canal-example'
if (-not (Test-Path -LiteralPath (Join-Path $canalInstancePath 'instance.properties'))) {
    if ($Mode -eq 'Fresh') {
        New-Item -ItemType Directory -Path $canalInstancePath -Force | Out-Null
        $canalInstance = @"
canal.instance.master.address=mysql:3306
canal.instance.dbUsername=$($settings.CANAL_DB_USERNAME)
canal.instance.dbPassword=$($settings.CANAL_DB_PASSWORD)
canal.instance.connectionCharset=UTF-8
canal.instance.tsdb.enable=true
canal.instance.gtidon=false
canal.instance.filter.regex=xiaohashu\\..*
"@
        [IO.File]::WriteAllText((Join-Path $canalInstancePath 'instance.properties'), $canalInstance, $utf8)
    } else {
        $canalCopied = $false
        foreach ($canalContainer in @('xiaohashu-canal-1', 'canal-server')) {
            & docker inspect $canalContainer --format '{{.Id}}' 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0) { continue }
            & docker cp "${canalContainer}:/home/admin/canal-server/conf/example" $canalInstancePath
            if ($LASTEXITCODE -ne 0) { throw 'Could not preserve the existing Canal instance configuration and position.' }
            $canalCopied = $true
            break
        }
        if (-not $canalCopied) { throw 'ReuseExisting mode could not find the existing Canal container and position. Use a preserved .runtime backup or explicit Fresh mode.' }
    }
}
# Write the completion marker only after every runtime configuration is ready.
[IO.File]::WriteAllLines($envPath, $envLines, $utf8)
[IO.File]::WriteAllText($rootMarkerPath, $DataRoot, $utf8)
foreach ($relativePath in @('frp/frps', 'frp/frpc')) {
    $logPath = Join-Path $DataRoot $relativePath
    if (-not (Test-Path -LiteralPath $logPath)) { New-Item -ItemType Directory -Path $logPath -Force | Out-Null }
}
Write-Host 'Prepared ignored machine-local deployment settings; no credentials were printed.'
