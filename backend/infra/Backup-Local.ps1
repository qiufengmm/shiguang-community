[CmdletBinding()]
param(
    [string]$DestinationRoot,
    [string]$DataRoot = $env:XIAOHASHU_DATA_ROOT
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($DataRoot)) {
    $rootMarker = Join-Path $PSScriptRoot '.runtime/data-root.txt'
    $DataRoot = if (Test-Path -LiteralPath $rootMarker) { (Get-Content -LiteralPath $rootMarker -Raw).Trim() } else { 'F:\docker' }
}
$DataRoot = [IO.Path]::GetFullPath($DataRoot)
if ([string]::IsNullOrWhiteSpace($DestinationRoot)) { $DestinationRoot = Join-Path $DataRoot 'backups' }
$DestinationRoot = [IO.Path]::GetFullPath($DestinationRoot)
$composeEnvPath = Join-Path $PSScriptRoot '.runtime/compose.env'
if (-not (Test-Path -LiteralPath $composeEnvPath -PathType Leaf)) { throw 'infra/.runtime/compose.env is required. Run Prepare-Local.ps1 first.' }

$composeArgs = @('compose', '--env-file', 'infra/.runtime/compose.env', '-f', 'infra/compose.yaml', '-f', 'infra/compose.app.yaml')
$composeJson = & docker @composeArgs 'config' '--format' 'json'
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration could not be rendered.' }
$composeConfig = ($composeJson -join "`n") | ConvertFrom-Json -Depth 100

# A backup must use the same root recorded by preparation and mounted by this
# Compose configuration. Reject mismatches before creating any backup files.
$rootMarkerPath = Join-Path $PSScriptRoot '.runtime/data-root.txt'
if (-not (Test-Path -LiteralPath $rootMarkerPath -PathType Leaf)) {
    throw 'infra/.runtime/data-root.txt is required to verify the active data root.'
}
$preparedDataRoot = [IO.Path]::GetFullPath((Get-Content -LiteralPath $rootMarkerPath -Raw).Trim())
if (-not [string]::Equals($preparedDataRoot, $DataRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Backup data root does not match the prepared deployment. Prepared '$preparedDataRoot', requested '$DataRoot'."
}

$projectPrefix = $projectRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$dataPrefix = $DataRoot.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$dataRootMountCount = 0
foreach ($serviceProperty in $composeConfig.services.PSObject.Properties) {
    foreach ($mount in @($serviceProperty.Value.volumes)) {
        if ($mount.type -ne 'bind' -or [string]::IsNullOrWhiteSpace([string]$mount.source)) { continue }
        $mountSource = [string]$mount.source
        if (-not [IO.Path]::IsPathRooted($mountSource)) { $mountSource = Join-Path $projectRoot $mountSource }
        $mountSource = [IO.Path]::GetFullPath($mountSource)
        $isProjectSource = $mountSource.StartsWith($projectPrefix, [StringComparison]::OrdinalIgnoreCase)
        $isDataRootSource = $mountSource.StartsWith($dataPrefix, [StringComparison]::OrdinalIgnoreCase)
        if ($isDataRootSource) { $dataRootMountCount++ }
        if (-not $isProjectSource -and -not $isDataRootSource) {
            throw "Backup data root does not match the rendered Compose bind mounts. Unexpected source '$mountSource'."
        }
    }
}
if ($dataRootMountCount -eq 0) {
    throw 'Backup data root does not match the rendered Compose bind mounts; no host data paths were found under the requested root.'
}
$null = New-Item -ItemType Directory -Path $DestinationRoot -Force

# ZooKeeper's bind and named-volume files, plus MinIO's object store, are copied
# only after their writers have been gracefully stopped. This script never
# pauses or stops services.
$runningSnapshotServices = @(& docker @composeArgs 'ps' '--status' 'running' '-q' 'zookeeper' 'distributed-id-generator' 'minio')
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect ZooKeeper/ID-generator/MinIO state.' }
if ($runningSnapshotServices.Count -gt 0) {
    throw 'For storage consistency, manually stop application writers, distributed-id-generator, zookeeper, and minio, then rerun this script. No service was stopped by the script.'
}
$runningApplicationWriters = @(& docker @composeArgs 'ps' '--status' 'running' '-q' 'auth' 'gateway' 'oss' 'user' 'kv' 'note' 'user-relation' 'count' 'data-align' 'search' 'comment' 'frontend' 'canal' 'nacos' 'xxl-job-admin')
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect application writer state.' }
if ($runningApplicationWriters.Count -gt 0) {
    throw 'Application writers, Canal, Nacos, or XXL-JOB are still running. Drain queued work, stop them manually, and rerun the backup. No service was stopped by the script.'
}
$zkContainerId = [string](& docker @composeArgs 'ps' '-a' '-q' 'zookeeper')
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($zkContainerId)) {
    throw 'The Compose-managed ZooKeeper container was not found; its data cannot be included in a complete backup.'
}

$staticPaths = @(
    'infra/compose.yaml', 'infra/compose.app.yaml', 'infra/Prepare-Local.ps1',
    'infra/Start-Local.ps1', 'infra/Backup-Local.ps1', 'infra/docker',
    'infra/frontend/nginx.conf', 'infra/rocketmq/broker-compose.conf',
    'infra/frp/frps.toml', 'infra/frp/frpc.toml',
    'redis/modules', 'nacos/conf', 'cassandra5/config', 'zookeeper/conf',
    'minio/data',
    'rocketmq/namesrv/bin/runserver.sh', 'rocketmq/broker/bin/runbroker.sh',
    'elasticsearch/config', 'elasticsearch/plugins', 'kibana/config'
)
foreach ($relativePath in $staticPaths) {
    $sourcePath = if ($relativePath.StartsWith('infra/')) {
        Join-Path $projectRoot $relativePath
    } else {
        Join-Path $DataRoot $relativePath
    }
    if (-not (Test-Path -LiteralPath $sourcePath)) { throw "Required backup source is missing: $sourcePath" }
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$backupPath = Join-Path $DestinationRoot "xiaohashu-$timestamp"
if (Test-Path -LiteralPath $backupPath) { throw "Backup destination already exists: $backupPath" }
New-Item -ItemType Directory -Path $backupPath | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$manifestPath = Join-Path $backupPath 'manifest.json'
$manifest = [ordered]@{
    formatVersion = 1
    createdAt = (Get-Date).ToString('o')
    overallStatus = 'running'
    projectRoot = $projectRoot
    gitCommit = (& git -C $projectRoot rev-parse HEAD 2>$null | Select-Object -First 1)
    composeProject = [string]$composeConfig.name
    sourceDataRoot = $DataRoot
    images = [ordered]@{}
    products = [ordered]@{
        mysql = @{ status = 'pending'; databases = @('xiaohashu', 'nacos_config', 'xxl_job', 'leaf') }
        cassandra = @{ status = 'pending'; keyspace = 'xiaohashu' }
        minio = @{ status = 'pending'; bucket = 'xiaohashu-local'; objectVersionsIncluded = $false }
        zookeeper = @{ status = 'pending'; requiresStoppedWriters = $true; leafSnowflakeStateIncluded = $false }
        deploymentConfig = @{ status = 'pending'; includesRuntimeSecrets = $true }
        elasticsearch = @{ status = 'rebuildable'; detail = 'Index data is rebuildable from MySQL and Canal.' }
        redis = @{ status = 'rebuildable'; detail = 'Cache and relation accelerators are rebuildable from MySQL.' }
        rocketmq = @{ status = 'notIncluded'; detail = 'In-flight messages are not part of this per-store backup.' }
    }
    files = @()
    consistency = 'Each product has its own recovery point; this is not an atomic cross-store snapshot.'
}

function Invoke-DockerChecked([string[]]$Arguments, [string]$Action) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Action failed with exit code $LASTEXITCODE." }
}

function Copy-BackupSource([string]$RelativePath, [string]$DestinationRelativePath) {
    $sourcePath = if ($RelativePath.StartsWith('infra/')) {
        Join-Path $projectRoot $RelativePath
    } else {
        Join-Path $DataRoot $RelativePath
    }
    $destinationPath = Join-Path $backupPath $DestinationRelativePath
    $destinationParent = Split-Path -Parent $destinationPath
    if (-not (Test-Path -LiteralPath $destinationParent)) { New-Item -ItemType Directory -Path $destinationParent -Force | Out-Null }
    Copy-Item -LiteralPath $sourcePath -Destination $destinationPath -Recurse
}

function Invoke-ManifestSave {
    $manifest.files = @(
        Get-ChildItem -LiteralPath $backupPath -Recurse -File |
            Where-Object { $_.FullName -ne $manifestPath } |
            ForEach-Object {
                $relative = $_.FullName.Substring($backupPath.Length).TrimStart('\', '/')
                $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
                [ordered]@{ path = $relative; bytes = $_.Length; sha256 = $hash.Hash }
            }
    )
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 12), $utf8)
}

try {
    foreach ($serviceName in $composeConfig.services.PSObject.Properties.Name) {
        $manifest.images[$serviceName] = [string]$composeConfig.services.$serviceName.image
    }

    $mysqlId = [string](& docker @composeArgs 'ps' '-q' 'mysql')
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($mysqlId)) { throw 'Compose-managed MySQL is not running.' }
    New-Item -ItemType Directory -Path (Join-Path $backupPath 'mysql') | Out-Null
    $mysqlTemp = "/tmp/xiaohashu-$timestamp.sql"
    $mysqlShell = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --databases xiaohashu nacos_config xxl_job leaf --single-transaction --routines --events --triggers --hex-blob --column-statistics=0 --set-gtid-purged=OFF --result-file=' + $mysqlTemp
    Invoke-DockerChecked @('exec', $mysqlId, 'sh', '-ec', $mysqlShell) 'MySQL logical dump'
    Invoke-DockerChecked @('cp', "${mysqlId}:$mysqlTemp", (Join-Path $backupPath 'mysql/databases.sql')) 'Copy MySQL dump'
    & docker 'exec' $mysqlId 'rm' '-f' $mysqlTemp | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not remove the single temporary MySQL dump file from the container.' }
    $manifest.products.mysql.status = 'included'

    $cassandraId = [string](& docker @composeArgs 'ps' '-q' 'cassandra')
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($cassandraId)) { throw 'Compose-managed Cassandra is not running.' }
    New-Item -ItemType Directory -Path (Join-Path $backupPath 'cassandra') | Out-Null
    $snapshotTag = "xiaohashu_$($timestamp.Replace('-', '_'))"
    $cassandraArchive = "/tmp/$snapshotTag.tar.gz"
    $cassandraSchema = "/tmp/$snapshotTag-schema.cql"
    $cassandraTableMap = "/tmp/$snapshotTag-table-map.txt"
    $cassandraList = "/tmp/$snapshotTag-files.list"
    $cassandraShell = "set -eu; nodetool flush xiaohashu; nodetool snapshot --ttl 1d -t $snapshotTag xiaohashu; cd /var/lib/cassandra/data; find . -path '*/snapshots/$snapshotTag/*' -type f -print0 > $cassandraList; test -s $cassandraList; tar --null -T $cassandraList -czf $cassandraArchive; cqlsh -e 'DESCRIBE KEYSPACE xiaohashu' > $cassandraSchema; cqlsh -e `"SELECT table_name, id FROM system_schema.tables WHERE keyspace_name = 'xiaohashu'`" > $cassandraTableMap"
    Invoke-DockerChecked @('exec', $cassandraId, 'sh', '-ec', $cassandraShell) 'Cassandra flush, snapshot, schema export'
    Invoke-DockerChecked @('cp', "${cassandraId}:$cassandraArchive", (Join-Path $backupPath 'cassandra/snapshot.tar.gz')) 'Copy Cassandra snapshot'
    Invoke-DockerChecked @('cp', "${cassandraId}:$cassandraSchema", (Join-Path $backupPath 'cassandra/keyspace.cql')) 'Copy Cassandra schema'
    Invoke-DockerChecked @('cp', "${cassandraId}:$cassandraTableMap", (Join-Path $backupPath 'cassandra/table-map.txt')) 'Copy Cassandra table ID map'
    foreach ($tempPath in @($cassandraArchive, $cassandraSchema, $cassandraTableMap, $cassandraList)) {
        & docker 'exec' $cassandraId 'rm' '-f' $tempPath | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not remove the single temporary Cassandra file '$tempPath'." }
    }
    $manifest.products.cassandra.status = 'included'
    $manifest.products.cassandra.snapshotTag = $snapshotTag
    $manifest.products.cassandra.snapshotTtl = '1d'

    $minioBackupPath = Join-Path $backupPath 'minio'
    New-Item -ItemType Directory -Path $minioBackupPath | Out-Null
    Copy-Item -LiteralPath (Join-Path $DataRoot 'minio/data') -Destination $minioBackupPath -Recurse
    $manifest.products.minio.status = 'included'
    $manifest.products.minio.dataMode = 'stoppedStorageDirectoryCopy'
    $manifest.products.minio.objectVersionsIncluded = $true

    foreach ($pathPair in @(
        @('infra/compose.yaml', 'deployment/infra/compose.yaml'),
        @('infra/compose.app.yaml', 'deployment/infra/compose.app.yaml'),
        @('infra/Prepare-Local.ps1', 'deployment/infra/Prepare-Local.ps1'),
        @('infra/Start-Local.ps1', 'deployment/infra/Start-Local.ps1'),
        @('infra/Backup-Local.ps1', 'deployment/infra/Backup-Local.ps1'),
        @('infra/docker', 'deployment/infra/docker'),
        @('infra/frontend/nginx.conf', 'deployment/infra/frontend/nginx.conf'),
        @('infra/rocketmq/broker-compose.conf', 'deployment/infra/rocketmq/broker-compose.conf'),
        @('infra/frp/frps.toml', 'deployment/infra/frp/frps.toml'),
        @('infra/frp/frpc.toml', 'deployment/infra/frp/frpc.toml'),
        @('redis/conf', 'host-config/redis/conf'), @('redis/modules', 'host-config/redis/modules'),
        @('nacos/conf', 'host-config/nacos/conf'), @('cassandra5/config', 'host-config/cassandra5/config'),
        @('zookeeper/conf', 'host-config/zookeeper/conf'),
        @('rocketmq/namesrv/bin/runserver.sh', 'host-config/rocketmq/namesrv/bin/runserver.sh'),
        @('rocketmq/broker/bin/runbroker.sh', 'host-config/rocketmq/broker/bin/runbroker.sh'),
        @('elasticsearch/config', 'host-config/elasticsearch/config'),
        @('elasticsearch/plugins', 'host-config/elasticsearch/plugins'),
        @('kibana/config', 'host-config/kibana/config')
    )) {
        Copy-BackupSource $pathPair[0] $pathPair[1]
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '.runtime') -Destination (Join-Path $backupPath 'deployment/runtime') -Recurse
    $manifest.products.deploymentConfig.status = 'included'

    $zkBackup = Join-Path $backupPath 'zookeeper'
    New-Item -ItemType Directory -Path $zkBackup | Out-Null
    foreach ($containerPath in @('data', 'datalog', 'logs')) {
        $destinationPath = Join-Path $zkBackup $containerPath
        & docker 'cp' "${zkContainerId}:/$containerPath" $destinationPath
        if ($LASTEXITCODE -ne 0) { throw "Could not copy ZooKeeper /$containerPath from its stopped container." }
    }
    $manifest.products.zookeeper.status = 'included'
    $manifest.products.zookeeper.leafSnowflakeStateIncluded = $true

    $manifest.overallStatus = 'success'
    $manifest.completionNote = 'Per-product snapshots completed; no cross-store atomicity is implied. Review manifest checksums before restore.'
    Invoke-ManifestSave
    Write-Host "Backup completed: $backupPath"
}
catch {
    $manifest.overallStatus = 'failed'
    $manifest.failure = $_.Exception.Message
    Invoke-ManifestSave
    throw
}
