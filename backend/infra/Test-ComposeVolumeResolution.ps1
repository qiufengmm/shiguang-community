$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Resolve-ComposeVolumeNames.ps1')

function Assert-Equal([string]$Expected, [string]$Actual, [string]$Name) {
    if ($Expected -cne $Actual) { throw "$Name expected '$Expected' but got '$Actual'." }
}

function Assert-Throws([scriptblock]$Action, [string]$ExpectedText, [string]$Name) {
    try { & $Action } catch {
        if ($_.Exception.Message -notlike "*$ExpectedText*") { throw "$Name threw an unexpected error: $($_.Exception.Message)" }
        return
    }
    throw "$Name did not throw."
}

$fixture = @'
[
  {"Config":{"Labels":{"com.docker.compose.project":"xiaohashu","com.docker.compose.service":"mysql"}},"Mounts":[{"Type":"volume","Name":"mysql-volume-fixture","Destination":"/var/lib/mysql"}]},
  {"Config":{"Labels":{"com.docker.compose.project":"xiaohashu","com.docker.compose.service":"zookeeper"}},"Mounts":[{"Type":"volume","Name":"zk-datalog-fixture","Destination":"/datalog"},{"Type":"volume","Name":"zk-logs-fixture","Destination":"/logs"}]},
  {"Config":{"Labels":{"com.docker.compose.project":"xiaohashu","com.docker.compose.service":"xxl-job-admin"}},"Mounts":[{"Type":"volume","Name":"xxl-logs-fixture","Destination":"/data/applogs"}]},
  {"Config":{"Labels":{"com.docker.compose.project":"another-project","com.docker.compose.service":"mysql"}},"Mounts":[{"Type":"volume","Name":"foreign-mysql-volume","Destination":"/var/lib/mysql"}]},
  {"Config":{"Labels":{"com.docker.compose.project":"xiaohashu","com.docker.compose.service":"mysql"}},"Mounts":[{"Type":"bind","Name":"","Destination":"/var/lib/mysql"}]}
]
'@
$inspections = @($fixture | ConvertFrom-Json -Depth 10)
$resolved = Resolve-XiaohashuComposeVolumeNames -Inspections $inspections -ProjectName 'xiaohashu'
Assert-Equal 'mysql-volume-fixture' $resolved.MYSQL_DATA_VOLUME_NAME 'MySQL volume'
Assert-Equal 'zk-datalog-fixture' $resolved.ZOOKEEPER_DATALOG_VOLUME_NAME 'ZooKeeper datalog volume'
Assert-Equal 'zk-logs-fixture' $resolved.ZOOKEEPER_LOGS_VOLUME_NAME 'ZooKeeper logs volume'
Assert-Equal 'xxl-logs-fixture' $resolved.XXL_JOB_LOGS_VOLUME_NAME 'XXL-JOB logs volume'
Write-Output 'PASS Compose-labeled named volume extraction and decoy filtering.'

$missing = @($inspections | Where-Object { -not ($_.Config.Labels.'com.docker.compose.service' -eq 'zookeeper') })
Assert-Throws { Resolve-XiaohashuComposeVolumeNames -Inspections $missing -ProjectName 'xiaohashu' } 'Fresh' 'Missing existing volume'
Write-Output 'PASS missing named volume requires Fresh/restore guidance.'

$ambiguous = @($inspections) + @((@'
{"Config":{"Labels":{"com.docker.compose.project":"xiaohashu","com.docker.compose.service":"mysql"}},"Mounts":[{"Type":"volume","Name":"second-mysql-volume","Destination":"/var/lib/mysql"}]}
'@ | ConvertFrom-Json -Depth 10))
Assert-Throws { Resolve-XiaohashuComposeVolumeNames -Inspections $ambiguous -ProjectName 'xiaohashu' } 'ambiguous' 'Ambiguous existing volumes'
Write-Output 'PASS ambiguous named volumes are rejected.'
