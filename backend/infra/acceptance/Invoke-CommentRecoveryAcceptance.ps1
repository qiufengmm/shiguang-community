[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^accept-[0-9a-f]{12}$')]
    [string]$RunId,
    [ValidateSet('A', 'B', 'C', 'D', 'E', 'F', 'All')]
    [string]$Scenario = 'All'
)

$ErrorActionPreference = 'Stop'
$env:MSYS_NO_PATHCONV = '1'
$env:JAVA_HOME = 'D:\java\jdk-17.0.18+8'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runDirectory = Join-Path $PSScriptRoot "../.runtime/acceptance/$RunId"
$manifestPath = Join-Path $runDirectory 'manifest.json'
$reportPath = Join-Path $runDirectory 'comment-recovery.json'
$moduleDirectory = Join-Path $repoRoot 'xiaohashu-comment\xiaohashu-comment-biz'
$probeClass = 'com.quanxiaoha.xiaohashu.comment.biz.CommentRecoveryProbe'
$manifest = $null
$report = $null
$mysqlId = $null
$redisId = $null
$cassandraId = $null
$brokerId = $null
$commentId = $null
$kvId = $null
$probeId = $null
$script:probeReady = $false
$script:activeScenario = $Scenario
$script:sampleEvents = @()

function Invoke-DockerChecked {
    param([string[]]$Arguments)
    $output = @(& docker @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Docker command failed with exit code $exitCode."
    }
    return ,$output
}

function Save-JsonAtomically {
    param([string]$Path, [object]$Value)
    $tempPath = "$Path.tmp"
    [IO.File]::WriteAllText($tempPath, ((ConvertTo-Json -InputObject $Value -Depth 40) + [Environment]::NewLine),
        [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $tempPath -Destination $Path -Force
}

function Save-Manifest {
    Save-JsonAtomically -Path $manifestPath -Value $manifest
}

function Save-Report {
    Save-JsonAtomically -Path $reportPath -Value $report
}

function Get-ContainerEntry {
    param([string]$Service)
    $entry = $manifest.containers | Where-Object service -eq $Service | Select-Object -First 1
    if ($null -eq $entry -or [string]::IsNullOrWhiteSpace([string]$entry.id)) {
        throw "Manifest has no created container for service '$Service'."
    }
    return $entry
}

function Get-ContainerDetails {
    param([string]$Id)
    $output = Invoke-DockerChecked -Arguments @('inspect', $Id)
    $details = ($output -join "`n") | ConvertFrom-Json -Depth 40
    return ,$details
}

function Assert-RunContainer {
    param([string]$Service)
    $entry = Get-ContainerEntry -Service $Service
    $details = Get-ContainerDetails -Id ([string]$entry.id)
    $labels = $details[0].Config.Labels
    $ownershipValid = $labels.'codex.acceptance.run' -eq $RunId -and $labels.'codex.acceptance.project' -eq 'xiaohashu'
    if ($Service -ne 'probe') { $ownershipValid = $ownershipValid -and $labels.'com.docker.compose.project' -eq $manifest.projectName }
    if (-not $ownershipValid) {
        throw "Container ownership check failed for '$Service'."
    }
    if (-not $details[0].NetworkSettings.Networks.PSObject.Properties.Name.Contains($manifest.projectName)) {
        throw "Container '$Service' is not attached to the isolated project network."
    }
    return $details[0]
}

function Invoke-FixtureSql {
    param([string]$Sql)
    $encoded = [Convert]::ToBase64String([Text.UTF8Encoding]::new($false).GetBytes($Sql))
    $command = "printf '%s' '$encoded' | base64 -d | MYSQL_PWD=`"`$MYSQL_ROOT_PASSWORD`" mysql --protocol=socket --user=root --database=xiaohashu --batch --raw --skip-column-names"
    $output = Invoke-DockerChecked -Arguments @('exec', $mysqlId, 'sh', '-ec', $command)
    return (@($output | ForEach-Object { [string]$_ }) -join "`n").TrimEnd()
}

function Invoke-FixtureCql {
    param([string]$Cql)
    $output = Invoke-DockerChecked -Arguments @('exec', $cassandraId, 'cqlsh', '--request-timeout=20', '-e', $Cql)
    return @($output | ForEach-Object { [string]$_ })
}

function Get-Sha256 {
    param([string]$Text)
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text)
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Get-MySqlScalar {
    param([string]$Sql)
    $value = Invoke-FixtureSql -Sql $Sql
    if ([string]::IsNullOrWhiteSpace($value)) { return $null }
    return ($value -split "`n" | Select-Object -First 1).Trim()
}

function Get-NoteCommentTotal {
    return [long](Get-MySqlScalar -Sql "SELECT COALESCE(comment_total,0) FROM t_note_count WHERE note_id=$($manifest.noteId)")
}

function Get-OutboxPublishByHash {
    param([string]$ContentHash)
    $sql = "SELECT event_key, JSON_UNQUOTE(JSON_EXTRACT(payload,'`$.commentId')), JSON_UNQUOTE(JSON_EXTRACT(payload,'`$.contentUuid')), SHA2(JSON_UNQUOTE(JSON_EXTRACT(payload,'`$.content')),256), status, attempt_count, JSON_UNQUOTE(JSON_EXTRACT(payload,'`$.createTime')) FROM t_comment_outbox WHERE topic='PublishCommentTopic' AND SHA2(JSON_UNQUOTE(JSON_EXTRACT(payload,'`$.content')),256)='$ContentHash' ORDER BY id DESC LIMIT 1"
    $raw = Invoke-FixtureSql -Sql $sql
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    $fields = ($raw -split "`t", 7)
    if ($fields.Count -lt 7) { throw 'Comment outbox query returned an incomplete event record.' }
    return [pscustomobject]@{
        eventKey = $fields[0]
        commentId = [string]$fields[1]
        contentUuid = [string]$fields[2]
        contentSha256 = [string]$fields[3]
        outboxStatus = [string]$fields[4]
        outboxAttemptCount = [int]$fields[5]
        createTime = [string]$fields[6]
    }
}

function Get-OutboxPayload {
    param([string]$EventKey)
    if ($EventKey -notmatch '^comment-(publish|delete):[0-9]+$') {
        throw 'Refusing to query an event key outside this acceptance format.'
    }
    return Invoke-FixtureSql -Sql "SELECT payload FROM t_comment_outbox WHERE event_key='$EventKey' LIMIT 1"
}

function Get-InboxState {
    param([string]$EventKey)
    if ($EventKey -notmatch '^comment-(publish|delete):[0-9]+$') {
        throw 'Refusing to query an event key outside this acceptance format.'
    }
    $raw = Invoke-FixtureSql -Sql "SELECT status,attempt_count FROM t_comment_inbox WHERE event_key='$EventKey' LIMIT 1"
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    $fields = ($raw -split "`t", 2)
    return [pscustomobject]@{ status = [string]$fields[0]; attemptCount = [int]$fields[1] }
}

function Get-CommentRows {
    param([string[]]$CommentIds)
    if ($CommentIds.Count -eq 0) { return 0 }
    foreach ($id in $CommentIds) { if ($id -notmatch '^[0-9]{1,19}$') { throw 'Invalid comment ID in acceptance state.' } }
    $list = $CommentIds -join ','
    return [int](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM t_comment WHERE id IN ($list)")
}

function Get-TombstoneState {
    param([string]$CommentId)
    if ($CommentId -notmatch '^[0-9]{1,19}$') { throw 'Invalid comment ID in acceptance state.' }
    $raw = Invoke-FixtureSql -Sql "SELECT content_cleanup_status FROM t_comment_tombstone WHERE comment_id=$CommentId LIMIT 1"
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    return $raw.Trim()
}

function Get-CassandraContentHash {
    param([string]$NoteId, [string]$CreateTime, [string]$ContentUuid)
    if ($NoteId -notmatch '^[0-9]{1,19}$' -or $ContentUuid -notmatch '^[0-9a-fA-F-]{36}$') {
        throw 'Invalid Cassandra key in acceptance state.'
    }
    $month = ([DateTime]::Parse($CreateTime)).ToString('yyyy-MM')
    $result = Invoke-FixtureCql -Cql "SELECT content FROM xiaohashu.comment_content WHERE note_id=$NoteId AND year_month='$month' AND content_id=$ContentUuid"
    $lines = @($result | ForEach-Object { ([string]$_).Trim() })
    $separator = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^-{3,}$') { $separator = $i; break }
    }
    if ($separator -lt 0) { return $null }
    for ($row = $separator + 1; $row -lt $lines.Count; $row++) {
        $value = $lines[$row].Trim()
        if ([string]::IsNullOrWhiteSpace($value)) { continue }
        if ($value -match '^\(0 rows\)$') { return $null }
        if ($value -match '^\(\d+ rows?\)$') { return $null }
        return Get-Sha256 -Text $value
    }
    return $null
}

function Get-ResponseItemCount {
    param([object]$Data)
    if ($null -eq $Data) { return 0 }
    if ($Data -is [Array] -or $Data -is [System.Collections.IList]) { return $Data.Count }
    return 1
}

function Invoke-CommentList {
    $headers = @{ userId = [string]$manifest.userId }
    $body = @{ noteId = [string]$manifest.noteId; pageNo = 1 } | ConvertTo-Json -Compress
    return Invoke-RestMethod -Method Post -Uri "$($manifest.commentBaseUrl)/comment/list" -Headers $headers `
        -ContentType 'application/json' -Body $body -TimeoutSec 30
}

function Invoke-ChildCommentList {
    param([string]$ParentCommentId)
    $headers = @{ userId = [string]$manifest.userId }
    $body = @{ parentCommentId = [string]$ParentCommentId; pageNo = 1 } | ConvertTo-Json -Compress
    return Invoke-RestMethod -Method Post -Uri "$($manifest.commentBaseUrl)/comment/child/list" -Headers $headers `
        -ContentType 'application/json' -Body $body -TimeoutSec 30
}

function Invoke-PublishComment {
    param([string]$Content, [string]$ReplyCommentId)
    $headers = @{ userId = [string]$manifest.userId }
    $body = @{ noteId = [string]$manifest.noteId; content = $Content }
    if ($ReplyCommentId) { $body.replyCommentId = [string]$ReplyCommentId }
    $json = $body | ConvertTo-Json -Compress
    try {
        $response = Invoke-RestMethod -Method Post -Uri "$($manifest.commentBaseUrl)/comment/publish" `
            -Headers $headers -ContentType 'application/json' -Body $json -TimeoutSec 30
    } catch {
        throw 'Isolated comment publish request failed.'
    }
    if (-not $response.success) { throw 'Isolated comment publish request was rejected.' }
    $hash = Get-Sha256 -Text $Content
    $deadline = (Get-Date).AddSeconds(60)
    do {
        $event = Get-OutboxPublishByHash -ContentHash $hash
        if ($null -ne $event) { return $event }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw 'Successful publish request did not produce a durable outbox event.'
}

function Wait-ForInboxApplied {
    param([string[]]$EventKeys, [int]$TimeoutSeconds = 300, [int]$ExpectedCommentRows = -1)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $applied = 0
        foreach ($key in $EventKeys) {
            $state = Get-InboxState -EventKey $key
            if ($null -ne $state -and $state.status -eq 'APPLIED') { $applied++ }
        }
        $rowCount = if ($ExpectedCommentRows -ge 0) { Get-CommentRows -CommentIds @($EventKeys | ForEach-Object { ($_ -split ':')[-1] }) } else { -1 }
        if ($applied -eq $EventKeys.Count -and ($ExpectedCommentRows -lt 0 -or $rowCount -eq $ExpectedCommentRows)) {
            return [pscustomobject]@{ appliedCount = $applied; commentRows = $rowCount }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'Comment Inbox events did not converge before the scenario timeout.'
}

function Wait-ForDeleted {
    param([string[]]$CommentIds, [long]$ExpectedNoteCount, [int]$TimeoutSeconds = 300)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $rows = Get-CommentRows -CommentIds $CommentIds
        $count = Get-NoteCommentTotal
        $cleanupComplete = $true
        foreach ($id in $CommentIds) {
            if ((Get-TombstoneState -CommentId $id) -ne 'CLEANED') { $cleanupComplete = $false; break }
        }
        if ($rows -eq 0 -and $count -eq $ExpectedNoteCount -and $cleanupComplete) { return $true }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'Comment delete did not converge across MySQL, count and Cassandra before timeout.'
}

function Invoke-DeleteComment {
    param([string]$CommentId)
    $headers = @{ userId = [string]$manifest.userId }
    $body = @{ commentId = [string]$CommentId } | ConvertTo-Json -Compress
    try {
        $response = Invoke-RestMethod -Method Post -Uri "$($manifest.commentBaseUrl)/comment/delete" `
            -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 30
    } catch {
        throw 'Isolated comment delete request failed.'
    }
    if (-not $response.success) { throw 'Isolated comment delete request was rejected.' }
}

function Add-EventEvidence {
    param([string]$ScenarioName, [object]$Event, [string]$Operation)
    $item = [pscustomobject]@{
        scenario = $ScenarioName
        operation = $Operation
        eventKey = $Event.eventKey
        commentId = [string]$Event.commentId
        contentUuid = [string]$Event.contentUuid
        createTime = [string]$Event.createTime
        contentSha256 = [string]$Event.contentSha256
        outboxStatus = [string]$Event.outboxStatus
        outboxAttemptCount = [int]$Event.outboxAttemptCount
        recordedAt = [DateTime]::UtcNow.ToString('o')
    }
    $manifest.testEvents = @($manifest.testEvents) + @($item)
    Save-Manifest
    return $item
}

function Get-CurrentTimeToken {
    return [DateTime]::Now.ToString('yyyy-MM-dd HH:mm:ss')
}

function New-RandomDigits {
    param([ValidateRange(1, 18)][int]$Length)
    $builder = [Text.StringBuilder]::new($Length)
    while ($builder.Length -lt $Length) {
        $bytes = [byte[]]::new($Length - $builder.Length)
        [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        foreach ($byte in $bytes) {
            $digit = [int]($byte % 10)
            if ($builder.Length -eq 0 -and $digit -eq 0) { continue }
            [void]$builder.Append([char]([int][char]'0' + $digit))
            if ($builder.Length -eq $Length) { break }
        }
    }
    return $builder.ToString()
}

function New-DirectPublishEvent {
    param([string]$CommentId, [string]$Content)
    $uuid = [guid]::NewGuid().ToString()
    $time = Get-CurrentTimeToken
    $payload = [ordered]@{
        commentId = [string]$CommentId
        noteId = [string]$manifest.noteId
        content = $Content
        contentUuid = $uuid
        imageUrl = ''
        replyCommentId = $null
        createTime = $time
        creatorId = [string]$manifest.userId
    } | ConvertTo-Json -Compress
    return [pscustomobject]@{
        eventKey = "comment-publish:$CommentId"
        commentId = [string]$CommentId
        contentUuid = $uuid
        contentSha256 = Get-Sha256 -Text $Content
        createTime = $time
        payload = $payload
        outboxStatus = 'probe'
        outboxAttemptCount = 0
    }
}

function New-DirectDeleteEvent {
    param([object]$PublishEvent)
    $payload = [ordered]@{
        id = [string]$PublishEvent.commentId
        noteId = [string]$manifest.noteId
        userId = [string]$manifest.userId
        contentUuid = [string]$PublishEvent.contentUuid
        isContentEmpty = $false
        imageUrl = ''
        level = 1
        replyTotal = 0
        likeTotal = 0
        childCommentTotal = 0
        heat = 0.0
        firstReplyCommentId = 0
        parentId = [string]$manifest.noteId
        replyCommentId = 0
        replyUserId = 0
        isTop = $false
        createTime = [string]$PublishEvent.createTime
        updateTime = [string]$PublishEvent.createTime
    } | ConvertTo-Json -Compress
    return [pscustomobject]@{
        eventKey = "comment-delete:$($PublishEvent.commentId)"
        commentId = [string]$PublishEvent.commentId
        contentUuid = [string]$PublishEvent.contentUuid
        contentSha256 = [string]$PublishEvent.contentSha256
        createTime = [string]$PublishEvent.createTime
        payload = $payload
        outboxStatus = 'probe'
        outboxAttemptCount = 0
    }
}

function Get-CassandraPartitionCount {
    param([string]$Month)
    if ($Month -notmatch '^\d{4}-\d{2}$') { throw 'Invalid Cassandra acceptance partition month.' }
    $result = Invoke-FixtureCql -Cql "SELECT COUNT(*) FROM xiaohashu.comment_content WHERE note_id=$($manifest.noteId) AND year_month='$Month'"
    $line = @($result | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -match '^\d+$' } | Select-Object -Last 1)
    if ($line.Count -eq 0) { throw 'Cassandra count query returned no row count.' }
    return [long]$line[0]
}

function Wait-ForDirectEventApplied {
    param([string]$EventKey, [int]$TimeoutSeconds = 300)
    if ($EventKey -notmatch '^comment-(publish|delete):[0-9]+$') { throw 'Invalid direct event key.' }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $state = Get-InboxState -EventKey $EventKey
        if ($null -ne $state -and $state.status -eq 'APPLIED') { return $state }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'Direct comment event did not become APPLIED.'
}

function Set-RunningScenario {
    param([string]$Name, [object]$Result)
    $record = [pscustomobject]@{ name = $Name; status = 'running'; result = $Result }
    $report.scenarios = @($report.scenarios | Where-Object name -ne $Name) + @($record)
    $report.updatedAt = [DateTime]::UtcNow.ToString('o')
    Save-Report
}

function Set-ServiceStateInManifest {
    param([string]$Service)
    $entry = Get-ContainerEntry -Service $Service
    $details = Get-ContainerDetails -Id ([string]$entry.id)
    $entry.state = $details[0].State.Status
    $entry.health = if ($details[0].State.Health) { $details[0].State.Health.Status } else { 'none' }
    $entry.startedAt = $details[0].State.StartedAt
    if ($Service -eq 'comment' -and $details[0].State.Status -eq 'running') {
        $binding = $details[0].NetworkSettings.Ports.'8093/tcp' | Select-Object -First 1
        if ($null -eq $binding -or $binding.HostIp -ne '127.0.0.1') {
            throw 'Comment acceptance endpoint is not bound exclusively to loopback.'
        }
        $manifest.commentBaseUrl = "http://127.0.0.1:$($binding.HostPort)"
    }
    Save-Manifest
    return $details[0]
}

function Stop-FixtureService {
    param([string]$Service)
    $details = Assert-RunContainer -Service $Service
    if ($details.State.Status -eq 'running') {
        $entry = Get-ContainerEntry -Service $Service
        $null = Invoke-DockerChecked -Arguments @('stop', '--time', '20', [string]$entry.id)
        $null = Set-ServiceStateInManifest -Service $Service
    }
}

function Start-FixtureService {
    param([string]$Service, [int]$TimeoutSeconds = 240, [switch]$RequireHealthy)
    $details = Assert-RunContainer -Service $Service
    $entry = Get-ContainerEntry -Service $Service
    if ($details.State.Status -ne 'running') {
        $null = Invoke-DockerChecked -Arguments @('start', [string]$entry.id)
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $details = Set-ServiceStateInManifest -Service $Service
        $health = if ($details.State.Health) { $details.State.Health.Status } else { 'none' }
        if ($details.State.Status -eq 'running' -and (-not $RequireHealthy -or $health -eq 'healthy')) { return $details }
        if ($details.State.Status -eq 'exited') { throw "Isolated service '$Service' exited during recovery." }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    if ($RequireHealthy) { throw "Isolated service '$Service' did not become healthy in time." }
    return $details
}

function Restart-FixtureService {
    param([string]$Service, [int]$TimeoutSeconds = 240, [switch]$RequireHealthy)
    $details = Assert-RunContainer -Service $Service
    $entry = Get-ContainerEntry -Service $Service
    $null = Invoke-DockerChecked -Arguments @('restart', '--time', '20', [string]$entry.id)
    return Start-FixtureService -Service $Service -TimeoutSeconds $TimeoutSeconds -RequireHealthy:$RequireHealthy
}

function Ensure-ProbeReady {
    if ($script:probeReady) { return }
    $probeEntry = $manifest.containers | Where-Object service -eq 'probe' | Select-Object -First 1
    if ($null -eq $probeEntry -or $probeEntry.status -ne 'created') {
        throw 'No active probe container is recorded in this run manifest.'
    }
    $details = Get-ContainerDetails -Id ([string]$probeEntry.id)
    $networkNames = @($details[0].NetworkSettings.Networks.PSObject.Properties.Name)
    if ($details[0].Config.Labels.'codex.acceptance.run' -ne $RunId -or
        $manifest.projectName -notin $networkNames) {
        throw 'Probe container does not belong to the isolated acceptance network.'
    }
    if ($details[0].State.Status -ne 'running') {
        $null = Invoke-DockerChecked -Arguments @('start', [string]$probeEntry.id)
    }
    $probeId = [string]$probeEntry.id
    $null = Invoke-DockerChecked -Arguments @('exec', $probeId, 'mkdir', '-p', '/probe/classes', '/probe/libs')
    $null = Invoke-DockerChecked -Arguments @('cp', (Join-Path $moduleDirectory 'target\test-classes\.'), "${probeId}:/probe/classes/")
    $null = Invoke-DockerChecked -Arguments @('cp', (Join-Path $moduleDirectory 'target\recovery-probe-libs\.'), "${probeId}:/probe/libs/")
    $null = Invoke-DockerChecked -Arguments @('cp', $manifestPath, "${probeId}:/probe/manifest.json")
    $script:probeReady = $true
}

function Invoke-Probe {
    param(
        [string]$Operation,
        [string]$EventKey,
        [string]$Topic,
        [string]$ConsumerGroup,
        [string]$Payload
    )
    Ensure-ProbeReady
    $arguments = @('exec', $probeId, 'java', '-Xms16m', '-Xmx96m', '-cp',
        '/probe/classes:/probe/libs/*', $probeClass, '--manifest', '/probe/manifest.json',
        '--operation', $Operation, '--event-key', $EventKey)
    if ($Operation -eq 'queue-status' -or $Operation -eq 'consume-dlq') {
        $arguments += @('--consumer-group', $ConsumerGroup)
        $raw = Invoke-DockerChecked -Arguments $arguments
    } else {
        if ([string]::IsNullOrWhiteSpace($Topic) -or [string]::IsNullOrWhiteSpace($Payload)) {
            throw 'Probe publish requires a topic and payload.'
        }
        $arguments = @('exec', '-i', $probeId, 'java', '-Xms16m', '-Xmx96m', '-cp',
            '/probe/classes:/probe/libs/*', $probeClass, '--manifest', '/probe/manifest.json',
            '--operation', $Operation, '--event-key', $EventKey, '--topic', $Topic,
            '--payload-stdin', 'true')
        $payloadBase64 = [Convert]::ToBase64String([Text.UTF8Encoding]::new($false).GetBytes($Payload))
        $raw = @($payloadBase64 | & docker @arguments 2>&1)
        if ($LASTEXITCODE -ne 0) {
            $detail = (@($raw | ForEach-Object { [string]$_ }) -join ' ').Trim()
            if ($detail.Length -gt 300) { $detail = $detail.Substring(0, 300) }
            throw "Probe operation '$Operation' failed: $detail"
        }
    }
    $line = @($raw | ForEach-Object { [string]$_ } | Where-Object { $_ -match '^operation=' } | Select-Object -Last 1)
    if ($line.Count -eq 0) { throw "Probe operation '$Operation' did not return a result." }
    return [string]$line[0]
}

function Get-ProbeField {
    param([string]$Result, [string]$Name)
    $match = [regex]::Match($Result, "(?:^|\s)$([regex]::Escape($Name))=([^\s]+)")
    if (-not $match.Success) { throw "Probe output did not include '$Name'." }
    return $match.Groups[1].Value
}

function Assert-EventApplied {
    param([object]$Event)
    $deadline = (Get-Date).AddSeconds(300)
    do {
        $state = Get-InboxState -EventKey $Event.eventKey
        if ($null -ne $state -and $state.status -eq 'APPLIED') { return $state }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'Comment Inbox event did not become APPLIED.'
}

function Assert-PublishedBody {
    param([object]$Event)
    $state = Get-InboxState -EventKey $Event.eventKey
    if ($null -eq $state -or $state.status -ne 'APPLIED') { throw 'Published event is not APPLIED.' }
    $row = Invoke-FixtureSql -Sql "SELECT content_uuid,DATE_FORMAT(create_time,'%Y-%m-%d %H:%i:%s') FROM t_comment WHERE id=$($Event.commentId) AND note_id=$($manifest.noteId) LIMIT 1"
    if ([string]::IsNullOrWhiteSpace($row)) { throw 'APPLIED event has no MySQL comment row.' }
    $fields = $row -split "`t", 2
    if ($fields[0] -ne $Event.contentUuid) { throw 'MySQL comment UUID does not match the outbox event.' }
    $cassandraHash = Get-CassandraContentHash -NoteId ([string]$manifest.noteId) -CreateTime $fields[1] -ContentUuid $fields[0]
    if ($cassandraHash -ne $Event.contentSha256) { throw 'Cassandra comment body does not match the original body hash.' }
    return [pscustomobject]@{ mysqlRowPresent = $true; inboxStatus = $state.status; inboxAttemptCount = $state.attemptCount; cassandraBodySha256 = $cassandraHash }
}

function Get-ScenarioContent {
    param([string]$Name, [int]$Index)
    return "$RunId-$Name-$Index-$([guid]::NewGuid().ToString('N'))"
}

function Invoke-ScenarioA {
    $startCount = Get-NoteCommentTotal
    $rootContent = Get-ScenarioContent -Name 'baseline-root' -Index 1
    $root = Invoke-PublishComment -Content $rootContent -ReplyCommentId ''
    Add-EventEvidence -ScenarioName 'A' -Event $root -Operation 'publish-root' | Out-Null
    $null = Wait-ForInboxApplied -EventKeys @($root.eventKey) -ExpectedCommentRows 1
    $null = Assert-PublishedBody -Event $root
    $replyContent = Get-ScenarioContent -Name 'baseline-reply' -Index 1
    $reply = Invoke-PublishComment -Content $replyContent -ReplyCommentId $root.commentId
    Add-EventEvidence -ScenarioName 'A' -Event $reply -Operation 'publish-reply' | Out-Null
    $null = Wait-ForInboxApplied -EventKeys @($reply.eventKey) -ExpectedCommentRows 1
    if ((Get-CommentRows -CommentIds @($root.commentId, $reply.commentId)) -ne 2) {
        throw 'Root and reply did not both persist as separate MySQL comments.'
    }

    $rootPage = Invoke-CommentList
    $rootIds = @($rootPage.data | ForEach-Object { [string]$_.commentId })
    $childPage = Invoke-ChildCommentList -ParentCommentId $root.commentId
    $childIds = @($childPage.data | ForEach-Object { [string]$_.commentId })
    $rootBodyHash = Get-Sha256 -Text (($rootContent))
    $replyBodyHash = Get-Sha256 -Text (($replyContent))
    if ($rootIds -notcontains $root.commentId -or $childIds -notcontains $reply.commentId) {
        throw 'Baseline comment or first reply is absent from the real list APIs.'
    }
    if ((Get-Sha256 -Text ([string]($rootPage.data | Where-Object { [string]$_.commentId -eq $root.commentId } | Select-Object -First 1).content)) -ne $rootBodyHash) {
        throw 'Root comment list body hash does not match the published body.'
    }
    if ((Get-Sha256 -Text ([string]($childPage.data | Where-Object { [string]$_.commentId -eq $reply.commentId } | Select-Object -First 1).content)) -ne $replyBodyHash) {
        throw 'Child comment list body hash does not match the published body.'
    }
    $null = Assert-PublishedBody -Event $root
    $null = Assert-PublishedBody -Event $reply

    Invoke-DeleteComment -CommentId $root.commentId
    $null = Wait-ForInboxApplied -EventKeys @("comment-delete:$($root.commentId)")
    Wait-ForDeleted -CommentIds @($root.commentId, $reply.commentId) -ExpectedNoteCount $startCount | Out-Null
    $remaining = Invoke-CommentList
    if ((Get-ResponseItemCount -Data $remaining.data) -ne 0 -or [long]$remaining.totalCount -ne $startCount) {
        throw 'Deleted baseline comment remains in the real list API.'
    }
    return [pscustomobject]@{
        status = 'passed'
        initialCount = $startCount
        rootCommentId = [string]$root.commentId
        replyCommentId = [string]$reply.commentId
        rootContentSha256 = $rootBodyHash
        replyContentSha256 = $replyBodyHash
        rootListContains = $true
        childListContains = $true
        pageOffsetRegression = 'first child returned at page one'
        deletedCommentRows = 0
        tombstonesCleaned = 2
        finalNoteCommentTotal = Get-NoteCommentTotal
        cassandraBodiesRemaining = 0
        completedAt = [DateTime]::UtcNow.ToString('o')
    }
}

function Invoke-ScenarioB {
    $startCount = Get-NoteCommentTotal
    Stop-FixtureService -Service 'rocketmq-broker'
    $events = @()
    for ($i = 1; $i -le 10; $i++) {
        $content = Get-ScenarioContent -Name 'broker-outage' -Index $i
        $event = Invoke-PublishComment -Content $content -ReplyCommentId ''
        $events += $event
        Add-EventEvidence -ScenarioName 'B' -Event $event -Operation 'publish-during-broker-outage' | Out-Null
    }
    $downSince = [DateTime]::UtcNow
    Start-Sleep -Seconds 120
    $beforeRestart = @($events | ForEach-Object { Get-OutboxPublishByHash -ContentHash $_.contentSha256 })
    $notRecoverable = @($beforeRestart | Where-Object { $_.outboxStatus -notin @('PENDING', 'SENDING') })
    if ($notRecoverable.Count -gt 0) { throw 'Outbox was marked SENT while the isolated broker was stopped.' }
    $null = Restart-FixtureService -Service 'comment' -TimeoutSeconds 300 -RequireHealthy
    $null = Start-FixtureService -Service 'rocketmq-broker' -TimeoutSeconds 300 -RequireHealthy
    $null = Wait-ForInboxApplied -EventKeys @($events | ForEach-Object { $_.eventKey }) -TimeoutSeconds 300 -ExpectedCommentRows 10
    $bodyResults = @($events | ForEach-Object { Assert-PublishedBody -Event $_ })
    $counterAfter = Get-NoteCommentTotal
    if ($counterAfter -ne ($startCount + 10)) { throw 'Broker recovery duplicated or lost comment count changes.' }
    $page = Invoke-CommentList
    $apiIds = @($page.data | ForEach-Object { [string]$_.commentId })
    foreach ($event in $events) { if ($apiIds -notcontains $event.commentId) { throw 'Recovered comment is absent from the list API.' } }
    foreach ($event in $events) { Invoke-DeleteComment -CommentId $event.commentId }
    $deleteKeys = @($events | ForEach-Object { "comment-delete:$($_.commentId)" })
    $null = Wait-ForInboxApplied -EventKeys $deleteKeys -TimeoutSeconds 300
    Wait-ForDeleted -CommentIds @($events | ForEach-Object { $_.commentId }) -ExpectedNoteCount $startCount -TimeoutSeconds 300 | Out-Null
    return [pscustomobject]@{
        status = 'passed'
        eventCount = 10
        brokerOutageSeconds = [math]::Round(([DateTime]::UtcNow - $downSince).TotalSeconds, 0)
        outboxBeforeRestore = @($beforeRestart | Group-Object outboxStatus | ForEach-Object { [pscustomobject]@{ status = $_.Name; count = $_.Count } })
        commentRestartedWhileBrokerUnavailable = $true
        allInboxApplied = $true
        allBodiesMatched = ($bodyResults.Count -eq 10)
        counterDelta = ($counterAfter - $startCount)
        apiListContainsAll = $true
        cleanupComplete = $true
        completedAt = [DateTime]::UtcNow.ToString('o')
    }
}

function Invoke-ScenarioC {
    $startCount = Get-NoteCommentTotal
    Stop-FixtureService -Service 'cassandra'
    $events = @()
    for ($i = 1; $i -le 10; $i++) {
        $content = Get-ScenarioContent -Name 'cassandra-outage' -Index $i
        $event = Invoke-PublishComment -Content $content -ReplyCommentId ''
        $events += $event
        Add-EventEvidence -ScenarioName 'C' -Event $event -Operation 'publish-during-cassandra-outage' | Out-Null
    }
    $eventKeys = @($events | ForEach-Object { $_.eventKey })
    $inboxDeadline = (Get-Date).AddSeconds(30)
    do {
        $present = 0
        foreach ($key in $eventKeys) { if ($null -ne (Get-InboxState -EventKey $key)) { $present++ } }
        if ($present -eq 10) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $inboxDeadline)
    if ($present -ne 10) { throw 'Broker did not persist all Cassandra-outage events to Inbox.' }

    $samples = @()
    $holdStart = [DateTime]::UtcNow
    $holdUntil = $holdStart.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $holdUntil) {
        $states = @($eventKeys | ForEach-Object { Get-InboxState -EventKey $_ })
        $applied = @($states | Where-Object { $_.status -eq 'APPLIED' }).Count
        $maxAttempt = if ($states.Count -gt 0) { ($states | Measure-Object attemptCount -Maximum).Maximum } else { 0 }
        $rows = Get-CommentRows -CommentIds @($events | ForEach-Object { $_.commentId })
        $samples += [pscustomobject]@{ elapsedSeconds = [math]::Round(([DateTime]::UtcNow - $holdStart).TotalSeconds, 0); inboxApplied = $applied; maxAttemptCount = [int]$maxAttempt; commentRows = $rows }
        if ($applied -ne 0 -or $rows -ne 0) { throw 'A Cassandra outage event was incorrectly applied without a durable body.' }
        Start-Sleep -Seconds ([math]::Min(5, [math]::Max(1, [int]($holdUntil - [DateTime]::UtcNow).TotalSeconds)))
    }

    $restartDuringOutage = [ordered]@{}
    foreach ($service in @('comment', 'kv')) {
        try {
            $details = Restart-FixtureService -Service $service -TimeoutSeconds 120
            $restartDuringOutage[$service] = [pscustomobject]@{ state = $details.State.Status; health = if ($details.State.Health) { $details.State.Health.Status } else { 'none' } }
        } catch {
            $restartDuringOutage[$service] = [pscustomobject]@{ state = 'restart-failed'; health = $_.Exception.GetType().Name }
        }
    }
    $restartDuringOutageAt = [DateTime]::UtcNow.ToString('o')
    $null = Start-FixtureService -Service 'cassandra' -TimeoutSeconds 300 -RequireHealthy
    foreach ($service in @('kv', 'comment')) {
        $details = Get-ContainerDetails -Id ([string](Get-ContainerEntry -Service $service).id)
        if ($details[0].State.Status -ne 'running') {
            $null = Start-FixtureService -Service $service -TimeoutSeconds 300 -RequireHealthy
        } elseif ($details[0].State.Health -and $details[0].State.Health.Status -ne 'healthy') {
            $null = Restart-FixtureService -Service $service -TimeoutSeconds 300 -RequireHealthy
        }
    }
    $null = Wait-ForInboxApplied -EventKeys $eventKeys -TimeoutSeconds 300 -ExpectedCommentRows 10
    $bodyResults = @($events | ForEach-Object { Assert-PublishedBody -Event $_ })
    $counterAfter = Get-NoteCommentTotal
    if ($counterAfter -ne ($startCount + 10)) { throw 'Cassandra recovery caused a duplicate or missing comment count.' }
    $page = Invoke-CommentList
    $apiIds = @($page.data | ForEach-Object { [string]$_.commentId })
    foreach ($event in $events) { if ($apiIds -notcontains $event.commentId) { throw 'Recovered comment is absent from the list API.' } }
    foreach ($event in $events) { Invoke-DeleteComment -CommentId $event.commentId }
    $null = Wait-ForInboxApplied -EventKeys @($events | ForEach-Object { "comment-delete:$($_.commentId)" }) -TimeoutSeconds 300
    Wait-ForDeleted -CommentIds @($events | ForEach-Object { $_.commentId }) -ExpectedNoteCount $startCount -TimeoutSeconds 300 | Out-Null
    return [pscustomobject]@{
        status = 'passed'
        eventCount = 10
        cassandraOutageSeconds = [math]::Round(([DateTime]::UtcNow - $holdStart).TotalSeconds, 0)
        samples = $samples
        commentAndKvRestartedDuringOutage = $restartDuringOutage
        servicesRestartedAt = $restartDuringOutageAt
        allInboxAppliedAfterRecovery = $true
        allBodiesMatched = ($bodyResults.Count -eq 10)
        counterDelta = ($counterAfter - $startCount)
        apiListContainsAll = $true
        cleanupComplete = $true
        completedAt = [DateTime]::UtcNow.ToString('o')
    }
}

function Invoke-ScenarioD {
    $startCount = Get-NoteCommentTotal
    $content = Get-ScenarioContent -Name 'duplicate-replay' -Index 1
    $event = Invoke-PublishComment -Content $content -ReplyCommentId ''
    Add-EventEvidence -ScenarioName 'D' -Event $event -Operation 'publish-original' | Out-Null
    $null = Wait-ForInboxApplied -EventKeys @($event.eventKey) -ExpectedCommentRows 1
    $null = Assert-PublishedBody -Event $event
    $publishPayload = Get-OutboxPayload -EventKey $event.eventKey

    Invoke-DeleteComment -CommentId $event.commentId
    $deleteKey = "comment-delete:$($event.commentId)"
    $null = Wait-ForInboxApplied -EventKeys @($deleteKey)
    Wait-ForDeleted -CommentIds @($event.commentId) -ExpectedNoteCount $startCount | Out-Null
    $deletePayload = Get-OutboxPayload -EventKey $deleteKey

    $consumerGroup = 'xiaohashu_group_PublishCommentTopic'
    $publishSends = @()
    $deleteSends = @()
    for ($i = 1; $i -le 3; $i++) {
        $publishSends += Invoke-Probe -Operation 'replay' -EventKey $event.eventKey `
            -Topic 'PublishCommentTopic' -ConsumerGroup '' -Payload $publishPayload
        $deleteSends += Invoke-Probe -Operation 'replay' -EventKey $deleteKey `
            -Topic 'DeleteCommentTopic' -ConsumerGroup '' -Payload $deletePayload
    }
    foreach ($send in @($publishSends) + @($deleteSends)) {
        if ((Get-ProbeField -Result $send -Name 'sendStatus') -ne 'SEND_OK') { throw 'Duplicate replay was not accepted by the isolated broker.' }
    }
    Start-Sleep -Seconds 10
    $publishState = Get-InboxState -EventKey $event.eventKey
    $deleteState = Get-InboxState -EventKey $deleteKey
    $rows = Get-CommentRows -CommentIds @($event.commentId)
    $count = Get-NoteCommentTotal
    $bodyHash = Get-CassandraContentHash -NoteId ([string]$manifest.noteId) -CreateTime $event.createTime -ContentUuid $event.contentUuid
    $page = Invoke-CommentList
    $pageIds = @($page.data | ForEach-Object { [string]$_.commentId })
    if ($publishState.status -ne 'APPLIED' -or $deleteState.status -ne 'APPLIED' -or $rows -ne 0 -or
        $count -ne $startCount -or $null -ne $bodyHash -or $pageIds -contains $event.commentId) {
        throw "Duplicate replay assertion failed: publishStatus=$($publishState.status), deleteStatus=$($deleteState.status), rows=$rows, noteTotal=$count, startCount=$startCount, bodyPresent=$($null -ne $bodyHash), apiListContains=$($pageIds -contains [string]$event.commentId)."
    }
    $tombstone = Get-TombstoneState -CommentId $event.commentId
    if ($tombstone -ne 'CLEANED') { throw 'Duplicate replay did not preserve a completed delete tombstone.' }
    return [pscustomobject]@{
        status = 'passed'
        eventKey = $event.eventKey
        publishReplays = 3
        deleteReplays = 3
        publishInboxStatus = $publishState.status
        deleteInboxStatus = $deleteState.status
        commentRows = $rows
        noteCommentTotal = $count
        bodyPresent = ($null -ne $bodyHash)
        apiListContainsComment = ($pageIds -contains $event.commentId)
        tombstoneStatus = $tombstone
        allSendsAccepted = $true
        completedAt = [DateTime]::UtcNow.ToString('o')
    }
}

function Invoke-ScenarioE {
    $startCount = Get-NoteCommentTotal
    $commentId = New-RandomDigits -Length 18
    while ([long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM t_comment WHERE id=$commentId") -gt 0 -or
        [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM t_comment_tombstone WHERE comment_id=$commentId") -gt 0) {
        $commentId = New-RandomDigits -Length 18
    }
    $content = Get-ScenarioContent -Name 'delete-before-publish' -Index 1
    $publishEvent = New-DirectPublishEvent -CommentId $commentId -Content $content
    $deleteEvent = New-DirectDeleteEvent -PublishEvent $publishEvent
    $expectedSeedCount = $startCount + 1
    $seeded = Get-MySqlScalar -Sql "UPDATE t_note_count SET comment_total=$expectedSeedCount WHERE note_id=$($manifest.noteId); SELECT ROW_COUNT()"
    if ([long]($seeded -split "`n" | Select-Object -Last 1) -ne 1 -or (Get-NoteCommentTotal) -ne $expectedSeedCount) {
        throw 'Could not seed the isolated note count for deletion-before-publish.'
    }
    Add-EventEvidence -ScenarioName 'E' -Event $publishEvent -Operation 'prepare-late-publish' | Out-Null
    Add-EventEvidence -ScenarioName 'E' -Event $deleteEvent -Operation 'delete-first' | Out-Null

    $deleteSend = Invoke-Probe -Operation 'replay' -EventKey $deleteEvent.eventKey `
        -Topic 'DeleteCommentTopic' -ConsumerGroup '' -Payload $deleteEvent.payload
    if ((Get-ProbeField -Result $deleteSend -Name 'sendStatus') -ne 'SEND_OK') { throw 'Delete-first event was not accepted by RocketMQ.' }
    $null = Wait-ForDirectEventApplied -EventKey $deleteEvent.eventKey
    $null = Wait-ForDeleted -CommentIds @($commentId) -ExpectedNoteCount $startCount

    $publishSend = Invoke-Probe -Operation 'replay' -EventKey $publishEvent.eventKey `
        -Topic 'PublishCommentTopic' -ConsumerGroup '' -Payload $publishEvent.payload
    if ((Get-ProbeField -Result $publishSend -Name 'sendStatus') -ne 'SEND_OK') { throw 'Late publish event was not accepted by RocketMQ.' }
    $null = Wait-ForDirectEventApplied -EventKey $publishEvent.eventKey
    $duplicateDeleteSends = @()
    for ($i = 1; $i -le 2; $i++) {
        $duplicateDeleteSends += Invoke-Probe -Operation 'replay' -EventKey $deleteEvent.eventKey `
            -Topic 'DeleteCommentTopic' -ConsumerGroup '' -Payload $deleteEvent.payload
    }
    Start-Sleep -Seconds 8
    $rows = Get-CommentRows -CommentIds @($commentId)
    $count = Get-NoteCommentTotal
    $tombstone = Get-TombstoneState -CommentId $commentId
    $bodyHash = Get-CassandraContentHash -NoteId ([string]$manifest.noteId) -CreateTime $publishEvent.createTime -ContentUuid $publishEvent.contentUuid
    $page = Invoke-CommentList
    $pageIds = @($page.data | ForEach-Object { [string]$_.commentId })
    $publishState = Get-InboxState -EventKey $publishEvent.eventKey
    $deleteState = Get-InboxState -EventKey $deleteEvent.eventKey
    if ($rows -ne 0 -or $count -ne $startCount -or $tombstone -ne 'CLEANED' -or $null -ne $bodyHash -or
        $pageIds -contains $commentId -or $publishState.status -ne 'APPLIED' -or $deleteState.status -ne 'APPLIED') {
        throw 'Delete-before-publish resurrected the comment, body, list entry or counter.'
    }
    foreach ($send in $duplicateDeleteSends) {
        if ((Get-ProbeField -Result $send -Name 'sendStatus') -ne 'SEND_OK') { throw 'Duplicate delete was not accepted by RocketMQ.' }
    }
    return [pscustomobject]@{
        status = 'passed'
        commentId = [string]$commentId
        deleteAppliedBeforePublish = $true
        duplicateDeletes = 2
        publishInboxStatus = $publishState.status
        deleteInboxStatus = $deleteState.status
        commentRows = $rows
        noteCommentTotal = $count
        seededCountRestored = ($count -eq $startCount)
        tombstoneStatus = $tombstone
        bodyPresent = ($null -ne $bodyHash)
        apiListContainsComment = ($pageIds -contains $commentId)
        contentSha256 = $publishEvent.contentSha256
        completedAt = [DateTime]::UtcNow.ToString('o')
    }
}

function Invoke-ScenarioF {
    $startCount = Get-NoteCommentTotal
    $commentId = New-RandomDigits -Length 18
    while ([long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM t_comment WHERE id=$commentId") -gt 0 -or
        [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM t_comment_tombstone WHERE comment_id=$commentId") -gt 0) {
        $commentId = New-RandomDigits -Length 18
    }
    $content = Get-ScenarioContent -Name 'inbox-lock-dlq' -Index 1
    $event = New-DirectPublishEvent -CommentId $commentId -Content $content
    Add-EventEvidence -ScenarioName 'F' -Event $event -Operation 'prepare-inbox-lock-dlq' | Out-Null

    $sqlSafePayload = $event.payload.Replace("'", "''")
    $lockSql = @"
START TRANSACTION;
INSERT INTO t_comment_inbox (event_key, event_type, payload, status, attempt_count, created_time, updated_time)
VALUES ('$($event.eventKey)', 'PUBLISH', '$sqlSafePayload', 'PENDING', 0, NOW(), NOW());
SELECT SLEEP(14400);
ROLLBACK;
"@
    $encodedLockSql = [Convert]::ToBase64String([Text.UTF8Encoding]::new($false).GetBytes($lockSql))
    $lockCommand = "printf '%s' '$encodedLockSql' | base64 -d | MYSQL_PWD=`"`$MYSQL_ROOT_PASSWORD`" mysql --protocol=socket --user=root --database=xiaohashu --batch --raw --skip-column-names"
    $null = Invoke-DockerChecked -Arguments @('exec', '--detach', $mysqlId, 'sh', '-ec', $lockCommand)
    $connectionId = $null
    $lockConnectionId = $null
    $failureStartedAt = [DateTime]::UtcNow
    try {
        $lockDeadline = (Get-Date).AddSeconds(30)
        do {
            $connectionText = Get-MySqlScalar -Sql "SELECT ID FROM information_schema.PROCESSLIST WHERE INFO LIKE '%SLEEP(14400)%' AND ID <> CONNECTION_ID() ORDER BY ID DESC LIMIT 1"
            if ($connectionText -match '^\d+$') { $connectionId = [long]$connectionText; $lockConnectionId = $connectionId; break }
            Start-Sleep -Seconds 1
        } while ((Get-Date) -lt $lockDeadline)
        if ($null -eq $connectionId) { throw 'Inbox lock transaction did not acquire its MySQL row lock.' }

        $consumerGroup = 'xiaohashu_group_PublishCommentTopic'
        $retryMaxTimes = 2
        $null = Invoke-DockerChecked -Arguments @('exec', $brokerId, 'sh', 'mqadmin', 'updateSubGroup',
            '-n', 'rocketmq-namesrv:9876', '-c', 'DefaultCluster', '-g', $consumerGroup,
            '-r', [string]$retryMaxTimes)
        $send = Invoke-Probe -Operation 'prime-dlq' -EventKey $event.eventKey `
            -Topic 'PublishCommentTopic' -ConsumerGroup '' -Payload $event.payload
        if ((Get-ProbeField -Result $send -Name 'sendStatus') -ne 'SEND_OK') { throw 'DLQ priming event was not accepted by RocketMQ.' }

        $deadline = (Get-Date).AddHours(4)
        $samples = @()
        $lastSavedMinute = -1
        do {
            $status = Invoke-Probe -Operation 'queue-status' -EventKey $event.eventKey `
                -Topic '' -ConsumerGroup $consumerGroup -Payload ''
            $retryMatches = [int](Get-ProbeField -Result $status -Name 'retryMatches')
            $dlqMatches = [int](Get-ProbeField -Result $status -Name 'dlqMatches')
            $elapsed = [math]::Round(([DateTime]::UtcNow - $failureStartedAt).TotalSeconds, 0)
            $inboxState = Get-InboxState -EventKey $event.eventKey
            $attempts = if ($inboxState) { $inboxState.attemptCount } else { 0 }
            $samples += [pscustomobject]@{ elapsedSeconds = $elapsed; retryMatches = $retryMatches; dlqMatches = $dlqMatches; visibleInboxAttempts = [int]$attempts }
            if ($dlqMatches -gt 0) { break }
            $minute = [int][math]::Floor($elapsed / 60)
            if ($minute -gt $lastSavedMinute) {
                Set-RunningScenario -Name 'F' -Result ([pscustomobject]@{ phase = 'waiting-for-dlq'; eventKey = $event.eventKey; elapsedSeconds = $elapsed; retryMatches = $retryMatches; dlqMatches = $dlqMatches; attempts = [int]$attempts; retryMaxTimes = $retryMaxTimes })
                $lastSavedMinute = $minute
            }
            Start-Sleep -Seconds 10
        } while ((Get-Date) -lt $deadline)
        if ($dlqMatches -lt 1) { throw 'The comment publish event did not reach the real DLQ within four hours.' }

        $null = Invoke-FixtureSql -Sql "KILL $connectionId"
        $connectionId = $null
        $unlockDeadline = (Get-Date).AddSeconds(30)
        do {
            $visibleLock = Get-MySqlScalar -Sql "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE INFO LIKE '%SLEEP(14400)%' AND ID <> CONNECTION_ID()"
            if ([int]$visibleLock -eq 0) { break }
            Start-Sleep -Seconds 1
        } while ((Get-Date) -lt $unlockDeadline)

        $publishSends = @()
        for ($i = 1; $i -le 2; $i++) {
            $publishSends += Invoke-Probe -Operation 'replay' -EventKey $event.eventKey `
                -Topic 'PublishCommentTopic' -ConsumerGroup '' -Payload $event.payload
        }
        foreach ($publishSend in $publishSends) {
            if ((Get-ProbeField -Result $publishSend -Name 'sendStatus') -ne 'SEND_OK') { throw 'Recovered original event replay was not accepted.' }
        }
        $null = Wait-ForInboxApplied -EventKeys @($event.eventKey) -TimeoutSeconds 300 -ExpectedCommentRows 1
        $bodyResult = Assert-PublishedBody -Event $event
        if ((Get-NoteCommentTotal) -ne ($startCount + 1)) { throw 'DLQ recovery applied the comment counter more than once.' }
        $dlqAck = Invoke-Probe -Operation 'consume-dlq' -EventKey $event.eventKey `
            -Topic '' -ConsumerGroup $consumerGroup -Payload ''
        $acknowledged = [int](Get-ProbeField -Result $dlqAck -Name 'acknowledged')
        $matching = [int](Get-ProbeField -Result $dlqAck -Name 'matching')
        if ($matching -lt 1) { throw 'The DLQ recovery consumer did not acknowledge the original failed event.' }

        Invoke-DeleteComment -CommentId $event.commentId
        $null = Wait-ForInboxApplied -EventKeys @("comment-delete:$($event.commentId)")
        Wait-ForDeleted -CommentIds @($event.commentId) -ExpectedNoteCount $startCount | Out-Null
        $dlqAfter = Invoke-Probe -Operation 'queue-status' -EventKey $event.eventKey `
            -Topic '' -ConsumerGroup $consumerGroup -Payload ''
        return [pscustomobject]@{
            status = 'passed'
            eventKey = $event.eventKey
            commentId = [string]$event.commentId
            failureInjection = 'uncommitted unique Inbox row lock'
            brokerRetryMaxTimes = $retryMaxTimes
            inboxLockConnectionId = [string]$lockConnectionId
            retrySamples = $samples
            dlqMessageObserved = $true
            dlqMessageAcknowledged = ($matching -ge 1)
            dlqAckedMessages = $acknowledged
            originalTopicReplays = 2
            inboxAppliedOnce = $true
            bodyMatched = ($bodyResult.cassandraBodySha256 -eq $event.contentSha256)
            counterDelta = 1
            cleanupComplete = $true
            dlqIndexedMessagesAfterAck = [int](Get-ProbeField -Result $dlqAfter -Name 'dlqMatches')
            completedAt = [DateTime]::UtcNow.ToString('o')
        }
    } finally {
        if ($null -ne $connectionId) {
            try { $null = Invoke-FixtureSql -Sql "KILL $connectionId" } catch { }
        }
    }
}

function Initialize-ProbeRuntime {
    $probeEntry = $manifest.containers | Where-Object service -eq 'probe' | Select-Object -First 1
    if ($null -eq $probeEntry -or $probeEntry.status -ne 'created') { throw 'Probe container is not recorded in this run.' }
    $probeDetails = Assert-RunContainer -Service 'probe'
    if ($probeDetails.State.Status -ne 'running') { $null = Invoke-DockerChecked -Arguments @('start', [string]$probeEntry.id) }
    $script:probeId = [string]$probeEntry.id

    $env:JAVA_HOME = 'D:\java\jdk-17.0.18+8'
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    Push-Location $repoRoot
    try {
        $buildLogPath = Join-Path $runDirectory 'comment-probe-build.log'
        & mvn -pl xiaohashu-comment/xiaohashu-comment-biz -am '-DskipTests' test-compile *> $buildLogPath
        $compileExitCode = $LASTEXITCODE
        if ($compileExitCode -ne 0) { throw 'Comment acceptance probe test-compile failed.' }
        & mvn -f xiaohashu-comment/xiaohashu-comment-biz/pom.xml dependency:copy-dependencies `
            '-DincludeScope=test' '-DoutputDirectory=target/recovery-probe-libs' *>> $buildLogPath
        $classpathExitCode = $LASTEXITCODE
        if ($classpathExitCode -ne 0) { throw 'Comment acceptance probe classpath preparation failed.' }
        if ($report.PSObject.Properties.Name -contains 'buildEvidence') {
            $report.buildEvidence = [pscustomobject]@{ javaHome = $env:JAVA_HOME; testCompileExitCode = $compileExitCode; classpathExitCode = $classpathExitCode; log = 'comment-probe-build.log' }
        } else {
            $report | Add-Member -NotePropertyName buildEvidence -NotePropertyValue ([pscustomobject]@{ javaHome = $env:JAVA_HOME; testCompileExitCode = $compileExitCode; classpathExitCode = $classpathExitCode; log = 'comment-probe-build.log' })
        }
        Save-Report
    } finally { Pop-Location }
    $script:probeReady = $false
    Ensure-ProbeReady
}

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'Acceptance manifest is missing.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -Depth 40
if ($manifest.schemaVersion -ne 1 -or $manifest.runId -ne $RunId -or $manifest.stage -ne 'Comment' -or
    $manifest.projectName -ne "xiaohashu-$RunId") { throw 'Acceptance manifest identity does not match.' }
foreach ($service in @('mysql', 'redis', 'cassandra', 'rocketmq-broker', 'comment', 'kv')) {
    $detail = Assert-RunContainer -Service $service
    if ($detail.State.Status -ne 'running') { throw "Required isolated service '$service' is not running." }
}
$mysqlId = [string](Get-ContainerEntry -Service 'mysql').id
$redisId = [string](Get-ContainerEntry -Service 'redis').id
$cassandraId = [string](Get-ContainerEntry -Service 'cassandra').id
$brokerId = [string](Get-ContainerEntry -Service 'rocketmq-broker').id
$commentId = [string](Get-ContainerEntry -Service 'comment').id
$kvId = [string](Get-ContainerEntry -Service 'kv').id

if (Test-Path -LiteralPath $reportPath -PathType Leaf) {
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json -Depth 40
} else {
    $report = [pscustomobject]@{ schemaVersion = 1; runId = $RunId; imageId = (Assert-RunContainer 'comment').Image; startedAt = [DateTime]::UtcNow.ToString('o'); updatedAt = [DateTime]::UtcNow.ToString('o'); scenarios = @(); status = 'running' }
}

Initialize-ProbeRuntime
try {
    $scenarios = if ($Scenario -eq 'All') { @('A', 'B', 'C', 'D', 'E', 'F') } else { @($Scenario) }
    foreach ($name in $scenarios) {
        $existing = $report.scenarios | Where-Object name -eq $name | Select-Object -First 1
        if ($null -ne $existing -and $existing.status -eq 'passed') { continue }
        $result = switch ($name) {
            'A' { Invoke-ScenarioA }
            'B' { Invoke-ScenarioB }
            'C' { Invoke-ScenarioC }
        'D' { Invoke-ScenarioD }
        'E' { Invoke-ScenarioE }
        'F' { Invoke-ScenarioF }
            default { throw "Scenario '$name' is not implemented in this runner yet." }
        }
        $record = [pscustomobject]@{ name = $name; status = $result.status; result = $result }
        $report.scenarios = @($report.scenarios | Where-Object name -ne $name) + @($record)
        $report.updatedAt = [DateTime]::UtcNow.ToString('o')
        Save-Report
        $manifest.testEvents = @($manifest.testEvents)
    }
    $passed = @($report.scenarios | Where-Object { $_.name -in $scenarios -and $_.status -eq 'passed' }).Count
    $report.status = if ($passed -eq $scenarios.Count) { 'passed' } else { 'partial' }
    foreach ($propertyName in @('failureType', 'failureLine')) {
        if ($report.PSObject.Properties.Name -contains $propertyName) { $report.PSObject.Properties.Remove($propertyName) }
    }
    $report.updatedAt = [DateTime]::UtcNow.ToString('o')
    Save-Report
} catch {
    $report.status = 'failed'
    $failureType = $_.Exception.GetType().Name
    if ($report.PSObject.Properties.Name -contains 'failureType') {
        $report.failureType = $failureType
        $report.failureLine = $_.InvocationInfo.ScriptLineNumber
    } else {
        $report | Add-Member -NotePropertyName failureType -NotePropertyValue $failureType
        $report | Add-Member -NotePropertyName failureLine -NotePropertyValue $_.InvocationInfo.ScriptLineNumber
    }
    $report.updatedAt = [DateTime]::UtcNow.ToString('o')
    Save-Report
    throw
} finally {
    foreach ($service in @('cassandra', 'rocketmq-broker', 'kv', 'comment')) {
        try {
            $detail = Get-ContainerDetails -Id ([string](Get-ContainerEntry -Service $service).id)
            if ($detail[0].State.Status -ne 'running') {
                $null = Start-FixtureService -Service $service -TimeoutSeconds 300
            } elseif ($detail[0].State.Health -and $detail[0].State.Health.Status -ne 'healthy') {
                $null = Restart-FixtureService -Service $service -TimeoutSeconds 300 -RequireHealthy
            }
        } catch { }
    }
}

[pscustomobject]@{ runId = $RunId; status = $report.status; reportPath = $reportPath; scenarios = @($report.scenarios | Select-Object name, status) }
