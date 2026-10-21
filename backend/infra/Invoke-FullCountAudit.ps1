[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^accept-[0-9a-f]{12}$')]
    [string]$RunId,

    [ValidateSet('Audit', 'SelfTest')]
    [string]$Mode = 'Audit',

    [string]$MySqlContainer = 'xiaohashu-mysql-1',
    [string]$BrokerContainer = 'xiaohashu-rocketmq-broker-1',
    [ValidateRange(0, 300)]
    [int]$MaxQueueWaitSeconds = 300
)

$ErrorActionPreference = 'Stop'
$env:MSYS_NO_PATHCONV = '1'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runDirectory = Join-Path $projectRoot "infra/.runtime/acceptance/$RunId"
$sqlPath = Join-Path $projectRoot 'database/audit/full-count-audit.sql'

function Invoke-CheckedDocker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $result = @(& docker @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed (exit $LASTEXITCODE). Diagnostics: $($result -join "`n")"
    }
    return $result
}

function Invoke-MySqlText {
    param(
        [Parameter(Mandatory = $true)][string]$SqlText,
        [string]$Container,
        [switch]$NoPassword
    )

    if ([string]::IsNullOrWhiteSpace($Container)) { $Container = $MySqlContainer }
    $utf8 = [Text.UTF8Encoding]::new($false)
    $encodedSql = [Convert]::ToBase64String($utf8.GetBytes($SqlText))
    $auth = if ($NoPassword) { '' } else { 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" ' }
    $mysqlCommand = "printf '%s' '$encodedSql' | base64 -d | ${auth}mysql --protocol=socket --user=root --database=xiaohashu --batch --raw --skip-column-names"
    return Invoke-CheckedDocker -Arguments @('exec', $Container, 'sh', '-ec', $mysqlCommand)
}

function ConvertTo-AuditRows {
    param([Parameter(Mandatory = $true)][string[]]$Lines)
    $rows = [Collections.Generic.List[object]]::new()
    foreach ($line in $Lines) {
        if ([string]::IsNullOrWhiteSpace([string]$line)) { continue }
        $fields = ([string]$line).Split("`t")
        if ($fields.Count -ne 6) {
            throw 'Full count query returned a row with an unexpected column count.'
        }
        $stored = if ($fields[3] -eq 'NULL') { $null } else { [long]$fields[3] }
        $rows.Add([pscustomobject]@{
            metric = $fields[0]
            entity_id = [string]$fields[1]
            source_count = [long]$fields[2]
            stored_count = $stored
            counter_row_present = ($fields[4] -eq '1')
            status = $fields[5]
        })
    }
    return ,$rows.ToArray()
}

function Get-AuditRows {
    param([Parameter(Mandatory = $true)][string]$Query)

    $transaction = @"
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
$Query
COMMIT;
"@
    $lines = Invoke-MySqlText -SqlText $transaction
    return ConvertTo-AuditRows -Lines $lines
}

function Get-DatabaseWatermarks {
    param([string]$Container = $MySqlContainer)

    $query = @'
SELECT 'NOTE_OUTBOX', 'EMPTY', 0 WHERE NOT EXISTS (SELECT 1 FROM t_note_operate_outbox)
UNION ALL
SELECT 'NOTE_OUTBOX', status, COUNT(*) FROM t_note_operate_outbox GROUP BY status
UNION ALL
SELECT 'COMMENT_OUTBOX', 'EMPTY', 0 WHERE NOT EXISTS (SELECT 1 FROM t_comment_outbox)
UNION ALL
SELECT 'COMMENT_OUTBOX', status, COUNT(*) FROM t_comment_outbox GROUP BY status
UNION ALL
SELECT 'COMMENT_INBOX', 'EMPTY', 0 WHERE NOT EXISTS (SELECT 1 FROM t_comment_inbox)
UNION ALL
SELECT 'COMMENT_INBOX', status, COUNT(*) FROM t_comment_inbox GROUP BY status
ORDER BY 1, 2;
'@
    $readOnlyQuery = @"
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
$query
COMMIT;
"@
$lines = Invoke-MySqlText -SqlText $readOnlyQuery -Container $Container
    $rows = [Collections.Generic.List[object]]::new()
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace([string]$line)) { continue }
        $fields = ([string]$line).Split("`t")
        if ($fields.Count -ne 3) { throw 'Watermark query returned an unexpected column count.' }
        $rows.Add([pscustomobject]@{ store = $fields[0]; status = $fields[1]; count = [long]$fields[2] })
    }
    return ,@($rows | Sort-Object store, status)
}

function ConvertTo-ConsumerProgressSnapshot {
    param([string[]]$Lines, [Parameter(Mandatory = $true)][string]$Group)

    if (@($Lines).Count -eq 0 -or @($Lines | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }).Count -eq 0) {
        throw "RocketMQ returned no consumer progress for group '$Group'."
    }

    $retryLag = 0L
    $retryInflight = 0L
    $mainLag = 0L
    $mainInflight = 0L
    $inflight = 0L
    $totalLag = 0L
    foreach ($line in $Lines) {
        $text = ([string]$line).Trim()
        if ($text -match '^Consume Diff Total:\s*(\d+)\s*$') {
            $totalLag = [long]$Matches[1]
            continue
        }
        if ($text -match '^Consume Inflight Total:\s*(\d+)\s*$') {
            $inflight = [long]$Matches[1]
            continue
        }
        $parts = $text -split '\s+'
        if ($parts.Count -lt 7 -or $parts[2] -notmatch '^\d+$' -or
            $parts[3] -notmatch '^\d+$' -or $parts[4] -notmatch '^\d+$' -or
            $parts[5] -notmatch '^\d+$' -or $parts[6] -notmatch '^\d+$') { continue }
        $diff = [long]$parts[5]
        $queueInflight = [long]$parts[6]
        if ($parts[0] -eq "%RETRY%$Group") {
            $retryLag += $diff
            $retryInflight += $queueInflight
        } elseif ($parts[0] -notlike '#*') {
            $mainLag += $diff
            $mainInflight += $queueInflight
        }
    }
    if ($totalLag -eq 0 -and $mainLag + $retryLag -gt 0) { $totalLag = $mainLag + $retryLag }
    if ($inflight -eq 0 -and $retryInflight -gt 0) { $inflight = $retryInflight }
    return [pscustomobject]@{
        group = $Group
        lag = $totalLag
        mainLag = $mainLag
        mainInflight = $mainInflight
        retryLag = $retryLag
        inflight = $inflight
        retryInflight = $retryInflight
    }
}

function ConvertTo-TopicStatusCount {
    param([Parameter(Mandatory = $true)][string[]]$Lines)

    $count = 0L
    foreach ($line in $Lines) {
        $parts = ([string]$line).Trim() -split '\s+'
        if ($parts.Count -lt 4 -or $parts[1] -notmatch '^\d+$' -or
            $parts[2] -notmatch '^\d+$' -or $parts[3] -notmatch '^\d+$') { continue }
        $minimum = [long]$parts[2]
        $maximum = [long]$parts[3]
        if ($maximum -lt $minimum) { throw 'RocketMQ topic status reported max offset below min offset.' }
        $count += ($maximum - $minimum)
    }
    return $count
}

function Get-QueueSnapshot {
    param([switch]$IncludeDetails)

    $lines = Invoke-CheckedDocker -Arguments @(
        'exec', $BrokerContainer, 'sh', 'mqadmin', 'consumerProgress',
        '-n', 'rocketmq-namesrv:9876'
    )
    $relevantPattern = '^xiaohashu_group_(NoteOperateTopic|PublishNoteTransactionTopic|PublishCommentTopic|DeleteCommentTopic|CountNoteCommentTopic|FollowUnfollowTopic|LikeUnlikeTopic|CollectUnCollectTopic|CountFollowing(2DB)?Topic|CountFans(2DB)?Topic|CountNoteLike(2DBT)?Topic|CountNoteCollect(2DBT)?Topic|data_align_.+)$'
    $rows = [Collections.Generic.List[object]]::new()
    foreach ($line in $lines) {
        $text = ([string]$line).Trim()
        if ($text -notmatch '^([A-Za-z0-9_]+)\s+') { continue }
        $parts = $text -split '\s+'
        if ($parts.Count -lt 2 -or $parts[-1] -notmatch '^\d+$') { continue }
        $group = $parts[0]
        $activeCount = 0
        if ($parts.Count -ge 2 -and $parts[1] -match '^\d+$') { $activeCount = [int]$parts[1] }
        $rows.Add([pscustomobject]@{
            group = $group
            active_consumers = $activeCount
            lag = [long]$parts[-1]
            relevant = ($group -match $relevantPattern)
        })
    }
    $topicNames = @()
    if ($IncludeDetails) {
        $topicNames = @(Invoke-CheckedDocker -Arguments @('exec', $BrokerContainer, 'sh', 'mqadmin', 'topicList', '-n', 'rocketmq-namesrv:9876') |
            ForEach-Object { ([string]$_).Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    }
    $detailedRows = [Collections.Generic.List[object]]::new()
    foreach ($row in $rows) {
        $retryLag = 0L
        $retryInflight = 0L
        $consumerInflight = 0L
        $mainInflight = 0L
        $mainLag = $row.lag
        $groupLag = $row.lag
        $dlqCount = 0L
        if ($IncludeDetails -and $row.relevant) {
            $progressLines = Invoke-CheckedDocker -Arguments @(
                'exec', $BrokerContainer, 'sh', 'mqadmin', 'consumerProgress',
                '-n', 'rocketmq-namesrv:9876', '-g', $row.group
            )
            try {
                $progress = ConvertTo-ConsumerProgressSnapshot -Lines @($progressLines) -Group $row.group
            } catch {
                throw "Failed to inspect RocketMQ consumer group '$($row.group)': $($_.Exception.Message)"
            }
            $retryLag = $progress.retryLag
            $retryInflight = $progress.retryInflight
            $consumerInflight = $progress.inflight
            $mainLag = $progress.mainLag
            $mainInflight = $progress.mainInflight
            $groupLag = $progress.lag
            $dlqTopic = "%DLQ%$($row.group)"
            if ($topicNames -contains $dlqTopic) {
                $statusLines = Invoke-CheckedDocker -Arguments @(
                    'exec', $BrokerContainer, 'sh', 'mqadmin', 'topicStatus',
                    '-n', 'rocketmq-namesrv:9876', '-t', $dlqTopic
                )
                $dlqCount = ConvertTo-TopicStatusCount -Lines $statusLines
            }
        }
        $detailedRows.Add([pscustomobject]@{
            group = $row.group
            active_consumers = $row.active_consumers
            lag = $groupLag
            mainLag = $mainLag
            mainInflight = $mainInflight
            retryLag = $retryLag
            inflight = $consumerInflight
            retryInflight = $retryInflight
            dlqCount = $dlqCount
            relevant = $row.relevant
        })
    }
    return ,@($detailedRows | Sort-Object group)
}

function Get-PendingSummary {
    param(
        [Parameter(Mandatory = $true)][object[]]$DatabaseWatermarks,
        [Parameter(Mandatory = $true)][object[]]$Queues
    )
    $databasePending = @($DatabaseWatermarks | Where-Object {
        ($_.store -like '*OUTBOX' -and $_.status -ne 'SENT') -or
        ($_.store -eq 'COMMENT_INBOX' -and $_.status -ne 'APPLIED')
    } | Where-Object { $_.count -gt 0 })
    $queuePending = @($Queues | Where-Object {
        $_.relevant -and ($_.lag -gt 0 -or $_.retryLag -gt 0 -or $_.mainInflight -gt 0 -or $_.inflight -gt 0 -or
            $_.retryInflight -gt 0 -or $_.dlqCount -gt 0)
    })
    return [pscustomobject]@{ database = $databasePending; queues = $queuePending }
}

function Write-JsonFile {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)]$Value)
    $json = ConvertTo-Json -InputObject $Value -Depth 12
    $temporaryPath = "$Path.tmp"
    [IO.File]::WriteAllText($temporaryPath, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
}

function Assert-FixtureStatus {
    param([object[]]$Rows, [string]$Metric, [string]$EntityId, [string]$Expected)
    $matches = @($Rows | Where-Object { $_.metric -eq $Metric -and $_.entity_id -eq $EntityId })
    if ($matches.Count -ne 1 -or $matches[0].status -ne $Expected) {
        $actual = if ($matches.Count -eq 1) { $matches[0].status } else { "row-count=$($matches.Count)" }
        throw "Audit self-test failed for $Metric/$EntityId; expected $Expected, got $actual."
    }
}

function Assert-Watermark {
    param([object[]]$Rows, [string]$Store, [string]$Status, [long]$ExpectedCount)
    $matches = @($Rows | Where-Object { $_.store -eq $Store -and $_.status -eq $Status })
    if ($matches.Count -ne 1 -or $matches[0].count -ne $ExpectedCount) {
        $actual = if ($matches.Count -eq 1) { $matches[0].count } else { "row-count=$($matches.Count)" }
        throw "Watermark self-test failed for $Store/$Status; expected $ExpectedCount, got $actual."
    }
}

function Invoke-SelfTest {
    $fixtureContainer = "count-audit-$RunId"
    $null = Invoke-CheckedDocker -Arguments @('image', 'inspect', 'mysql:8.0.27')
    $existing = @(& docker container ls --all --filter "name=^/$fixtureContainer$" --format '{{.Names}}')
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify the audit fixture container name.' }
    if ($existing -contains $fixtureContainer) { throw "Refusing to replace existing container '$fixtureContainer'. Inspect and remove it individually before rerunning." }

    $null = Invoke-CheckedDocker -Arguments @(
        'run', '--detach', '--rm', '--name', $fixtureContainer,
        '--label', "codex.acceptance.run=$RunId",
        '--label', 'codex.acceptance.fixture=count-audit',
        '--network', 'none', '--memory', '512m',
        '--env', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes',
        '--env', 'MYSQL_DATABASE=xiaohashu',
        'mysql:8.0.27', '--performance-schema=OFF', '--innodb-buffer-pool-size=64M', '--max-connections=24'
    )
    try {
        $ready = $false
        $deadline = [DateTimeOffset]::Now.AddSeconds(90)
        while ([DateTimeOffset]::Now -lt $deadline) {
            $probe = @(& docker exec $fixtureContainer mysql --user=root --database=xiaohashu --batch --skip-column-names --execute='SELECT 1' 2>$null)
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Seconds 2
        }
        if (-not $ready) { throw 'The isolated MySQL audit fixture database did not become ready within 90 seconds.' }

    $fixtureSetup = @'
CREATE TABLE t_user (id BIGINT NOT NULL);
CREATE TABLE t_user_count (
  user_id BIGINT NOT NULL, fans_total BIGINT NULL, following_total BIGINT NULL,
  note_total BIGINT NULL, like_total BIGINT NULL, collect_total BIGINT NULL
);
CREATE TABLE t_note (id BIGINT NOT NULL, creator_id BIGINT NOT NULL, status INT NOT NULL);
CREATE TABLE t_note_count (
  note_id BIGINT NOT NULL, like_total BIGINT NULL, collect_total BIGINT NULL, comment_total BIGINT NULL
);
CREATE TABLE t_following (id BIGINT NOT NULL, user_id BIGINT NOT NULL, following_user_id BIGINT NOT NULL);
CREATE TABLE t_note_like (id BIGINT NOT NULL, note_id BIGINT NOT NULL, status INT NOT NULL);
CREATE TABLE t_note_collection (id BIGINT NOT NULL, note_id BIGINT NOT NULL, status INT NOT NULL);
CREATE TABLE t_comment (id BIGINT NOT NULL, note_id BIGINT NOT NULL);
CREATE TABLE t_note_operate_outbox (status VARCHAR(16) NOT NULL);
CREATE TABLE t_comment_outbox (status VARCHAR(16) NOT NULL);
CREATE TABLE t_comment_inbox (status VARCHAR(16) NOT NULL);

INSERT INTO t_user (id) VALUES (810000000000000001),(810000000000000002),(810000000000000003),(810000000000000004),(810000000000000005);
INSERT INTO t_note (id, creator_id, status) VALUES (820000000000000001,810000000000000001,1);
INSERT INTO t_following (id, user_id, following_user_id) VALUES
  (1,810000000000000001,810000000000000002),
  (2,810000000000000002,810000000000000001),
  (3,810000000000000003,810000000000000002),
  (4,810000000000000005,810000000000000002);
INSERT INTO t_note_like (id, note_id, status) VALUES (1,820000000000000001,1);
INSERT INTO t_note_collection (id, note_id, status) VALUES (1,820000000000000001,1);
INSERT INTO t_comment (id, note_id) VALUES (1,820000000000000001);
INSERT INTO t_user_count (user_id, fans_total, following_total, note_total, like_total, collect_total) VALUES
  (810000000000000001,NULL,1,-1,1,1),
  (810000000000000002,3,1,0,0,0),
  (810000000000000003,0,0,0,0,0),
  (810000000000000999,0,0,0,0,0);
INSERT INTO t_note_count (note_id, like_total, collect_total, comment_total)
  VALUES (820000000000000001,1,1,1);
'@

    $sqlQuery = Get-Content -LiteralPath $sqlPath -Raw
    $fullSql = @"
$fixtureSetup
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
$sqlQuery
COMMIT;
"@
    $rows = ConvertTo-AuditRows -Lines (Invoke-MySqlText -SqlText $fullSql -Container $fixtureContainer -NoPassword)
    Assert-FixtureStatus -Rows $rows -Metric FOLLOWING -EntityId '810000000000000001' -Expected MATCH
    Assert-FixtureStatus -Rows $rows -Metric FANS -EntityId '810000000000000001' -Expected NULL_COUNTER
    Assert-FixtureStatus -Rows $rows -Metric NOTE_PUBLISH -EntityId '810000000000000001' -Expected NEGATIVE_COUNTER
    Assert-FixtureStatus -Rows $rows -Metric FOLLOWING -EntityId '810000000000000003' -Expected MISMATCH
    Assert-FixtureStatus -Rows $rows -Metric FOLLOWING -EntityId '810000000000000004' -Expected ZERO_ROW_ABSENT
    Assert-FixtureStatus -Rows $rows -Metric FOLLOWING -EntityId '810000000000000005' -Expected MISSING_COUNTER
    Assert-FixtureStatus -Rows $rows -Metric FOLLOWING -EntityId '810000000000000999' -Expected ORPHAN_ENTITY
    Assert-FixtureStatus -Rows $rows -Metric USER_LIKE -EntityId '810000000000000001' -Expected MATCH
    Assert-FixtureStatus -Rows $rows -Metric USER_COLLECT -EntityId '810000000000000001' -Expected MATCH
    Assert-FixtureStatus -Rows $rows -Metric NOTE_LIKE -EntityId '820000000000000001' -Expected MATCH
    Assert-FixtureStatus -Rows $rows -Metric NOTE_COLLECT -EntityId '820000000000000001' -Expected MATCH
    Assert-FixtureStatus -Rows $rows -Metric NOTE_COMMENT -EntityId '820000000000000001' -Expected MATCH

    $watermarks = Get-DatabaseWatermarks -Container $fixtureContainer
    Assert-Watermark -Rows $watermarks -Store NOTE_OUTBOX -Status EMPTY -ExpectedCount 0
    Assert-Watermark -Rows $watermarks -Store COMMENT_OUTBOX -Status EMPTY -ExpectedCount 0
    Assert-Watermark -Rows $watermarks -Store COMMENT_INBOX -Status EMPTY -ExpectedCount 0
    $null = Invoke-MySqlText -SqlText "INSERT INTO t_note_operate_outbox(status) VALUES ('PENDING'),('SENT');" -Container $fixtureContainer -NoPassword
    $watermarks = Get-DatabaseWatermarks -Container $fixtureContainer
    Assert-Watermark -Rows $watermarks -Store NOTE_OUTBOX -Status PENDING -ExpectedCount 1
    Assert-Watermark -Rows $watermarks -Store NOTE_OUTBOX -Status SENT -ExpectedCount 1
    $consumerProgress = @(
        '#Topic #Broker Name #QID #Broker Offset #Consumer Offset #Diff #Inflight #LastTime',
        '%RETRY%xiaohashu_group_PublishCommentTopic broker-a 0 7 3 4 2 N/A',
        'PublishCommentTopic broker-a 0 1066 1062 4 1 2026-10-19 09:49:31',
        'Consume Diff Total: 8',
        'Consume Inflight Total: 3'
    )
    $progress = ConvertTo-ConsumerProgressSnapshot -Lines $consumerProgress -Group xiaohashu_group_PublishCommentTopic
    if ($progress.retryLag -ne 4 -or $progress.retryInflight -ne 2 -or $progress.mainInflight -ne 1 -or
        $progress.inflight -ne 3 -or $progress.lag -ne 8) {
        throw 'Consumer progress self-test failed to parse main/retry lag and inflight totals.'
    }
    $topicStatus = @(
        '#Broker Name #QID #Min Offset #Max Offset #Last Updated',
        'broker-a 0 0 1 2026-10-17 00:46:32,879',
        'broker-a 1 3 5 2026-10-17 00:46:32,879'
    )
    $dlqCount = ConvertTo-TopicStatusCount -Lines $topicStatus
    if ($dlqCount -ne 3) { throw "DLQ topic status self-test expected 3 messages, got $dlqCount." }
    [pscustomobject]@{
        runId = $RunId
        result = 'passed'
        isolation = 'single-use MySQL container on network none; no shared tables or persistent data'
        assertions = 19
        observedStatuses = @($rows | Group-Object status | ForEach-Object { [pscustomobject]@{ status = $_.Name; count = $_.Count } })
    }
    } finally {
        $null = Invoke-CheckedDocker -Arguments @('stop', '--time', '10', $fixtureContainer)
    }
}

if (-not (Test-Path -LiteralPath $sqlPath)) { throw "Audit SQL file is missing: $sqlPath" }
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

if ($Mode -eq 'SelfTest') {
    $fixtureReport = Invoke-SelfTest
    Write-JsonFile -Path (Join-Path $runDirectory 'count-audit-selftest.json') -Value $fixtureReport
    $fixtureReport | ConvertTo-Json -Depth 8
    exit 0
}

$null = Invoke-CheckedDocker -Arguments @('inspect', '--format', '{{.Id}}', $MySqlContainer)
$null = Invoke-CheckedDocker -Arguments @('inspect', '--format', '{{.Id}}', $BrokerContainer)
$auditQuery = Get-Content -LiteralPath $sqlPath -Raw

$deadline = [DateTimeOffset]::Now.AddSeconds($MaxQueueWaitSeconds)
$initialPending = $null
do {
    $databaseWatermarks = Get-DatabaseWatermarks
    $queues = Get-QueueSnapshot
    $pending = Get-PendingSummary -DatabaseWatermarks $databaseWatermarks -Queues $queues
    if ($pending.database.Count -eq 0 -and $pending.queues.Count -eq 0) { break }
    $initialPending = $pending
    if ([DateTimeOffset]::Now -ge $deadline) { break }
    Start-Sleep -Seconds 5
} while ($true)

$queuesReady = Get-QueueSnapshot -IncludeDetails
while ($true) {
    $databaseWatermarks = Get-DatabaseWatermarks
    $pending = Get-PendingSummary -DatabaseWatermarks $databaseWatermarks -Queues $queuesReady
    if ($pending.database.Count -eq 0 -and $pending.queues.Count -eq 0) { break }
    $initialPending = $pending
    if ([DateTimeOffset]::Now -ge $deadline) { break }
    Start-Sleep -Seconds 5
    $queuesReady = Get-QueueSnapshot -IncludeDetails
}

$snapshotOne = Get-AuditRows -Query $auditQuery
$watermarksOne = Get-DatabaseWatermarks
$queuesOne = $queuesReady
Start-Sleep -Seconds 10
$snapshotTwo = Get-AuditRows -Query $auditQuery
$watermarksTwo = Get-DatabaseWatermarks
$queuesTwo = Get-QueueSnapshot -IncludeDetails

$firstByKey = @{}
foreach ($row in $snapshotOne) { $firstByKey["$($row.metric)|$($row.entity_id)"] = $row }
$unstable = [Collections.Generic.List[object]]::new()
$stableIssues = [Collections.Generic.List[object]]::new()
foreach ($row in $snapshotTwo) {
    $key = "$($row.metric)|$($row.entity_id)"
    $before = $firstByKey[$key]
    if ($null -eq $before -or $before.source_count -ne $row.source_count -or $before.stored_count -ne $row.stored_count -or $before.status -ne $row.status) {
        $unstable.Add([pscustomobject]@{ metric = $row.metric; entity_id = $row.entity_id; first_status = $before.status; second_status = $row.status })
    } elseif ($row.status -notin @('MATCH', 'ZERO_ROW_ABSENT')) {
        $stableIssues.Add($row)
    }
    $firstByKey.Remove($key)
}
foreach ($missing in $firstByKey.Values) {
    $unstable.Add([pscustomobject]@{ metric = $missing.metric; entity_id = $missing.entity_id; first_status = $missing.status; second_status = 'ROW_MISSING' })
}

$pendingAfter = Get-PendingSummary -DatabaseWatermarks $watermarksTwo -Queues $queuesTwo
$queueLagChanges = ((ConvertTo-Json -InputObject $queuesOne -Depth 6 -Compress) -ne
                    (ConvertTo-Json -InputObject $queuesTwo -Depth 6 -Compress))
$watermarkChanges = ((ConvertTo-Json -InputObject $watermarksOne -Depth 6 -Compress) -ne
                     (ConvertTo-Json -InputObject $watermarksTwo -Depth 6 -Compress))
$isUnstable = $unstable.Count -gt 0 -or $pendingAfter.database.Count -gt 0 -or
              $pendingAfter.queues.Count -gt 0 -or $queueLagChanges -or $watermarkChanges
$exitCode = if ($isUnstable) { 3 } elseif ($stableIssues.Count -gt 0) { 2 } else { 0 }

$report = [pscustomobject]@{
    runId = $RunId
    auditedAt = [DateTimeOffset]::Now.ToString('o')
    database = 'xiaohashu'
    mode = 'read-only consistent snapshots'
    status = switch ($exitCode) { 0 { 'passed' } 2 { 'stable-differences' } 3 { 'unstable-window' } }
    counts = [pscustomobject]@{
        userEntities = @($snapshotTwo | Where-Object { $_.metric -match '^(FOLLOWING|FANS|USER_LIKE|USER_COLLECT|NOTE_PUBLISH)$' } | Select-Object -ExpandProperty entity_id -Unique).Count
        notes = @($snapshotTwo | Where-Object { $_.metric -match '^NOTE_(LIKE|COLLECT|COMMENT)$' } | Select-Object -ExpandProperty entity_id -Unique).Count
        auditRows = $snapshotTwo.Count
        stableIssues = $stableIssues.Count
        unstableRows = $unstable.Count
    }
    metricScope = @('FOLLOWING', 'FANS', 'USER_LIKE', 'USER_COLLECT', 'NOTE_PUBLISH', 'NOTE_LIKE', 'NOTE_COLLECT', 'NOTE_COMMENT')
    coveredConsumerGroups = @($queuesTwo | Where-Object relevant | Select-Object -ExpandProperty group)
    databaseWatermarks = $watermarksTwo
    relevantQueues = @($queuesTwo | Where-Object relevant)
    nonzeroOtherQueues = @($queuesTwo | Where-Object { -not $_.relevant -and $_.lag -gt 0 })
    relevantDeadLetterMessages = @($queuesTwo | Where-Object { $_.relevant -and $_.dlqCount -gt 0 })
    nonzeroOtherDeadLetterMessages = @($queuesTwo | Where-Object { -not $_.relevant -and $_.dlqCount -gt 0 })
    stableDifferences = $stableIssues
    unstableEntities = $unstable
}
Write-JsonFile -Path (Join-Path $runDirectory 'count-audit.json') -Value $report
$csvRows = @($snapshotTwo | Where-Object { $_.status -notin @('MATCH', 'ZERO_ROW_ABSENT') } |
    Select-Object metric, entity_id, source_count, stored_count, counter_row_present, status)
$csvRows | Export-Csv -LiteralPath (Join-Path $runDirectory 'count-mismatches.csv') -NoTypeInformation -Encoding utf8
Write-JsonFile -Path (Join-Path $runDirectory 'count-watermarks.json') -Value @{
    before = @{ database = $watermarksOne; queues = $queuesOne }
    after = @{ database = $watermarksTwo; queues = $queuesTwo }
    initialPending = $initialPending
    stable = (-not $isUnstable)
}
$report | ConvertTo-Json -Depth 12
exit $exitCode
