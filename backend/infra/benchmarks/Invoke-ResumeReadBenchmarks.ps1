#requires -Version 7.2
<#
.SYNOPSIS
Measures real, serial HTTP reads against the existing local Docker deployment.
.DESCRIPTION
Creates SQL read fixtures; it does not publish notes or change existing users/counts.
curl time_total measures the HTTP exchange, excluding docker.exe/process/JSON overhead.
Each request uses a new curl process/TCP connection. There is no gateway/auth in this path.
The note comparison is first detail read versus the immediate second read of a new note,
not a serial-versus-parallel RPC experiment. No backend is mocked or artificially delayed.
All ownership checks, sample bodies and cleanup progress are retained in the run manifest.
.EXAMPLE
pwsh -File infra/benchmarks/Invoke-ResumeReadBenchmarks.ps1 -RunId 20261020-smoke -Warmups 2 -Rounds 1 -PairsPerRound 2 -FollowingSamplesPerRound 3
.EXAMPLE
pwsh -File infra/benchmarks/Invoke-ResumeReadBenchmarks.ps1 -RunId 20261020-final -PairsPerRound 100
.EXAMPLE
pwsh -File infra/benchmarks/Invoke-ResumeReadBenchmarks.ps1 -RunId 20261020-smoke -CleanupOnly
.EXAMPLE
pwsh -File infra/benchmarks/Invoke-ResumeReadBenchmarks.ps1 -RunId 20261020-loadfx -PrepareOnly -Warmups 0 -Rounds 1 -PairsPerRound 1
#>
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]{0,16}$')]
    [string]$RunId = ((Get-Date -Format 'yyyyMMdd') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)),
    [ValidateRange(1, 10)] [int]$Rounds = 3,
    [ValidateRange(1, 1000)] [int]$FollowingSamplesPerRound = 100,
    [ValidateRange(1, 300)] [int]$PairsPerRound = 50,
    [ValidateRange(0, 100)] [int]$Warmups = 20,
    [ValidateRange(128, 8192)] [int]$ContentCharacters = 512,
    [ValidateRange(0, 2000)] [int]$SampleIntervalMilliseconds = 30,
    [ValidateRange(1, 1000)] [double]$MaxIdleCpuPercent = 40,
    [ValidateRange(10, 600)] [int]$StabilityTimeoutSeconds = 180,
    [ValidateRange(10, 600)] [int]$CleanupTimeoutSeconds = 120,
    [string]$ProjectName = 'xiaohashu',
    [switch]$PrepareOnly,
    [switch]$CleanupOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:dockerPath = (Get-Command docker -ErrorAction Stop).Source
$script:runtimeRoot = Join-Path (Split-Path $PSScriptRoot -Parent) '.runtime/benchmarks'
$script:runPath = Join-Path $script:runtimeRoot $RunId
$script:manifestPath = Join-Path $script:runPath 'manifest.json'
$script:manifest = $null
$script:samples = [System.Collections.Generic.List[object]]::new()
$script:cleanupErrors = [System.Collections.Generic.List[string]]::new()
$script:containers = [ordered]@{}
foreach ($service in @('frontend', 'mysql', 'redis', 'cassandra', 'user', 'note', 'kv', 'user-relation', 'rocketmq-broker')) {
    $script:containers[$service] = "$ProjectName-$service-1"
}

function Write-JsonFile {
    param([string]$Path, [object]$Value)
    $json = ConvertTo-Json -InputObject $Value -Depth 40
    [IO.File]::WriteAllText($Path, $json, [Text.UTF8Encoding]::new($false))
}

function Save-Manifest {
    $script:manifest.updatedAt = [DateTime]::UtcNow.ToString('o')
    Write-JsonFile -Path $script:manifestPath -Value $script:manifest
}

function Invoke-DockerProcess {
    param([string[]]$Arguments, [AllowNull()] [string]$InputText = $null, [int]$TimeoutSeconds = 45)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $script:dockerPath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    $start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if ($null -ne $InputText) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            throw "Docker client operation exceeded $TimeoutSeconds seconds. The server operation may have an unknown outcome."
        }
        return [pscustomobject]@{
            exitCode = $process.ExitCode
            stdout = $stdout.GetAwaiter().GetResult()
            stderr = $stderr.GetAwaiter().GetResult()
        }
    } finally { $process.Dispose() }
}

function Invoke-CheckedDocker {
    param([string[]]$Arguments, [AllowNull()] [string]$InputText = $null)
    $result = Invoke-DockerProcess -Arguments $Arguments -InputText $InputText
    if ($result.exitCode -ne 0) {
        # Arguments never contain resolved passwords. Do not inspect/export container environments.
        throw "Docker command failed (exit $($result.exitCode)): $($result.stderr.Trim())"
    }
    return $result.stdout
}

function Invoke-Sql {
    param([string]$Sql)
    # stdin avoids the Windows command-line size limit and shell interpolation of SQL.
    $command = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root --database=xiaohashu --batch --raw --skip-column-names --default-character-set=utf8mb4'
    $output = Invoke-CheckedDocker -Arguments @('exec', '-i', $script:containers.mysql, 'sh', '-ec', $command) -InputText ($Sql + "`n")
    return $output.Trim()
}

function Invoke-Redis {
    param([string[]]$Arguments)
    $command = 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --raw "$@"'
    $output = Invoke-CheckedDocker -Arguments (@('exec', $script:containers.redis, 'sh', '-ec', $command, 'resume-benchmark') + $Arguments)
    if ($output -match '(?m)^(ERR |NOAUTH|WRONGPASS|\(error\))') { throw 'Redis rejected the benchmark operation.' }
    return $output.Trim()
}

function Invoke-Cql {
    param([string]$Cql)
    $result = Invoke-DockerProcess -Arguments @('exec', '-i', $script:containers.cassandra, 'cqlsh', '--no-color', '--request-timeout=20') -InputText ($Cql + "`n")
    if ($result.exitCode -ne 0 -or ($result.stdout + $result.stderr) -match '(InvalidRequest|SyntaxException|Unauthorized|AuthenticationFailed|OperationTimedOut|NoHostAvailable|ReadTimeout|WriteTimeout|Traceback)') {
        throw 'Cassandra rejected or timed out during the benchmark operation; inspect only the run UUIDs before retrying cleanup.'
    }
    return $result.stdout
}

function Get-ContentRows {
    param([object[]]$Notes)
    $rows = @{}
    if ($Notes.Count -eq 0) { return ,$rows }
    $queries = foreach ($note in $Notes) {
        "SELECT JSON id,content,is_deleted FROM xiaohashu.note_content WHERE id=$($note.uuid);"
    }
    $output = Invoke-Cql -Cql ($queries -join "`n")
    foreach ($line in ($output -split '\r?\n')) {
        $candidate = $line.Trim()
        if ($candidate.StartsWith('{') -and $candidate.EndsWith('}')) {
            $row = $candidate | ConvertFrom-Json -AsHashtable
            $rows[[string]$row.id] = $row
        }
    }
    return ,$rows
}

function Quote-Sql {
    param([string]$Value)
    # Generated fixture text is ASCII; escaping both slash and quote keeps this reusable.
    return "'" + $Value.Replace('\', '\\').Replace("'", "''") + "'"
}

function Get-ContainerMetadata {
    $template = '{"name":{{json .Name}},"id":{{json .Id}},"imageId":{{json .Image}},"image":{{json .Config.Image}},"startedAt":{{json .State.StartedAt}},"restartCount":{{.RestartCount}},"running":{{.State.Running}},"health":{{if .State.Health}}{{json .State.Health.Status}}{{else}}"none"{{end}}}'
    $output = Invoke-CheckedDocker -Arguments (@('inspect', '--format', $template) + @($script:containers.Values))
    return @($output -split '\r?\n' | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json -AsHashtable })
}

function Get-Stats {
    $output = Invoke-CheckedDocker -Arguments (@('stats', '--no-stream', '--format', '{{json .}}') + @($script:containers.Values))
    return @($output -split '\r?\n' | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json -AsHashtable })
}

function Assert-Healthy {
    param([object[]]$Metadata)
    foreach ($item in $Metadata) {
        if (-not $item.running -or $item.health -notin @('healthy', 'none')) { throw "Required container $($item.name) is not healthy/running." }
    }
}

function Wait-Stable {
    param([string]$Label)
    $deadline = [DateTime]::UtcNow.AddSeconds($StabilityTimeoutSeconds)
    $stable = 0
    $observations = [System.Collections.Generic.List[object]]::new()
    while ([DateTime]::UtcNow -lt $deadline) {
        $metadata = @(Get-ContainerMetadata)
        $stats = @(Get-Stats)
        $healthy = @($metadata | Where-Object { -not $_.running -or $_.health -notin @('healthy', 'none') }).Count -eq 0
        $cpu = 0.0
        foreach ($item in $stats) { $cpu += [double]::Parse($item.CPUPerc.TrimEnd('%'), [Globalization.CultureInfo]::InvariantCulture) }
        $observations.Add(@{ at = [DateTime]::UtcNow.ToString('o'); cpuSumPercent = $cpu; healthy = $healthy; stats = $stats })
        if ($healthy -and $cpu -le $MaxIdleCpuPercent) { $stable++ } else { $stable = 0 }
        if ($stable -ge 3) {
            Write-JsonFile -Path (Join-Path $script:runPath "$Label-stability.json") -Value $observations.ToArray()
            return
        }
        Start-Sleep -Milliseconds 2000
    }
    Write-JsonFile -Path (Join-Path $script:runPath "$Label-stability.json") -Value $observations.ToArray()
    throw "Containers did not stay healthy and below the declared aggregate CPU threshold before $Label."
}

function Invoke-Http {
    param([string]$Url, [object]$Body, [long]$UserId)
    $json = ConvertTo-Json -InputObject $Body -Depth 10 -Compress -EscapeHandling EscapeNonAscii
    $arguments = @('exec', '-i', $script:containers.frontend, 'curl', '-sS', '--connect-timeout', '5', '--max-time', '15',
        '-H', 'Content-Type: application/json', '-H', "userId: $UserId", '--data-binary', '@-',
        '-w', '\n__RESUME_BENCH__%{http_code} %{time_total}', $Url)
    try { $result = Invoke-DockerProcess -Arguments $arguments -InputText $json -TimeoutSeconds 25 } catch {
        $result = @{ exitCode = 124; stdout = ''; stderr = $_.Exception.Message }
    }
    $match = [regex]::Match($result.stdout, '(?s)\r?\n__RESUME_BENCH__(\d{3}) ([0-9.]+)\s*$')
    $bodyText = if ($match.Success) { $result.stdout.Substring(0, $match.Index) } else { $result.stdout }
    $bodyObject = $null
    $jsonError = $null
    try { $bodyObject = $bodyText | ConvertFrom-Json -AsHashtable } catch { $jsonError = 'Response was not JSON.' }
    return @{
        httpStatus = $(if ($match.Success) { [int]$match.Groups[1].Value } else { 0 })
        elapsedMs = $(if ($match.Success) { [double]::Parse($match.Groups[2].Value, [Globalization.CultureInfo]::InvariantCulture) * 1000 } else { $null })
        curlExitCode = $result.exitCode; body = $bodyObject; rawBody = $bodyText
        parseError = $jsonError; transportError = $result.stderr.Trim()
    }
}

function Assert-ApiResponse {
    param([hashtable]$Response)
    if ($Response.curlExitCode -ne 0 -or $Response.httpStatus -ne 200 -or $Response.parseError -or $null -eq $Response.body -or $Response.body.success -ne $true) {
        $code = if ($null -ne $Response.body) { $Response.body.errorCode } else { 'no-json' }
        throw "HTTP/API failed: HTTP=$($Response.httpStatus), curl=$($Response.curlExitCode), business=$code."
    }
}

function Get-EsFixturePresence {
    param([object[]]$Documents)
    if ($Documents.Count -eq 0) { return @() }
    $response = Invoke-Http -Url 'http://elasticsearch:9200/_mget?_source=false' -UserId 0 -Body @{ docs = $Documents }
    if ($response.httpStatus -ne 200 -or $response.curlExitCode -ne 0 -or $null -eq $response.body -or -not $response.body.ContainsKey('docs')) { throw 'Elasticsearch fixture _mget did not return HTTP 200 and a docs array.' }
    $results = @($response.body.docs)
    if ($results.Count -ne $Documents.Count) { throw 'Elasticsearch _mget returned an unexpected document count.' }
    $found = [System.Collections.Generic.List[string]]::new()
    for ($index = 0; $index -lt $results.Count; $index++) {
        $result = $results[$index]
        if (-not $result.ContainsKey('found') -or [string]$result._id -cne [string]$Documents[$index]._id -or $result._index -cne $Documents[$index]._index) { throw 'Elasticsearch _mget returned a document error or unexpected identity.' }
        if ($result.found) { $found.Add("$($result._index):$($result._id)") }
    }
    return $found.ToArray()
}

function Add-Sample {
    param([string]$Scenario, [int]$Round, [int]$Index, [string]$Variant, [hashtable]$Response, [string]$ValidationError, [string]$EntityId)
    $sample = [ordered]@{
        scenario = $Scenario; round = $Round; index = $Index; variant = $Variant; entityId = $EntityId
        capturedAt = [DateTime]::UtcNow.ToString('o'); elapsedMs = $Response.elapsedMs
        httpStatus = $Response.httpStatus; curlExitCode = $Response.curlExitCode
        valid = [string]::IsNullOrEmpty($ValidationError); validationError = $ValidationError
        transportError = $Response.transportError; rawResponse = $Response.rawBody
    }
    $script:samples.Add([pscustomobject]$sample)
    [IO.File]::AppendAllText((Join-Path $script:runPath 'samples.jsonl'), ((ConvertTo-Json $sample -Depth 12 -Compress) + "`n"), [Text.UTF8Encoding]::new($false))
    if ($ValidationError) { throw "Invalid $Scenario/$Variant sample (round $Round, index $Index): $ValidationError" }
}

function Measure-Following {
    param([int]$Round, [int]$Index)
    $ownerId = [long]$script:manifest.users[0].id
    $response = Invoke-Http -Url 'http://user-relation:8087/relation/following/list' -UserId $ownerId -Body @{ userId = $ownerId; pageNo = 1 }
    $problem = $null
    try {
        Assert-ApiResponse $response
        $items = @($response.body.data)
        if ($items.Count -ne 10 -or $response.body.totalCount -ne 10 -or $response.body.pageSize -ne 10 -or $response.body.pageNo -ne 1) { throw 'Expected exactly ten following users, totalCount=10, pageNo=1, pageSize=10.' }
        $expectedIds = @($script:manifest.users | Select-Object -Skip 1 | ForEach-Object { [string]$_.id } | Sort-Object)
        $actualIds = @($items | ForEach-Object { [string]$_.userId } | Sort-Object)
        if (($expectedIds -join ',') -cne ($actualIds -join ',')) { throw 'Following user IDs did not match the fixture.' }
        foreach ($item in $items) {
            $expected = @($script:manifest.users | Where-Object { [string]$_.id -ceq [string]$item.userId })[0]
            if ($item.nickname -cne $expected.nickname) { throw 'Following nickname did not match the fixture.' }
        }
    } catch { $problem = $_.Exception.Message }
    Add-Sample -Scenario 'following' -Round $Round -Index $Index -Variant 'hot' -Response $response -ValidationError $problem -EntityId ([string]$ownerId)
}

function Measure-Note {
    param([hashtable]$Note, [int]$Round, [int]$Index, [string]$Variant)
    $owner = $script:manifest.users[0]
    $response = Invoke-Http -Url 'http://note:8086/note/detail' -UserId ([long]$owner.id) -Body @{ id = [long]$Note.id }
    $problem = $null
    try {
        Assert-ApiResponse $response
        $detail = $response.body.data
        if ($null -eq $detail -or [string]$detail.id -cne [string]$Note.id -or $detail.content -cne $Note.content -or $detail.title -cne $Note.title) { throw 'Note ID, title or full content did not match the fixture.' }
        if ([string]$detail.creatorId -cne [string]$owner.id -or $detail.creatorName -cne $owner.nickname -or $detail.visible -ne 0) { throw 'Note author or public visibility did not match the fixture.' }
    } catch { $problem = $_.Exception.Message }
    Add-Sample -Scenario 'note' -Round $Round -Index $Index -Variant $Variant -Response $response -ValidationError $problem -EntityId ([string]$Note.id)
}

function New-Fixtures {
    $autoIncrement = [long](Invoke-Sql 'SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=''t_user'';')
    if ($autoIncrement -lt 32) { throw 'This deployment has too few user-ID holes for this nonintrusive fixture allocator.' }
    $upper = [int][Math]::Min($autoIncrement - 1, [int]::MaxValue - 1)
    $selected = [System.Collections.Generic.HashSet[long]]::new()
    for ($index = 0; $index -lt 11; $index++) {
        $found = $false
        for ($attempt = 0; $attempt -lt 500; $attempt++) {
            $id = [long](Get-Random -Minimum 1 -Maximum ($upper + 1))
            if ($selected.Contains($id)) { continue }
            $phone = '000' + (Get-Random -Minimum 10000000 -Maximum 100000000)
            $xhsId = 'b' + [Guid]::NewGuid().ToString('N').Substring(0, 14)
            $count = [long](Invoke-Sql "SELECT (SELECT COUNT(*) FROM t_user WHERE id=$id OR phone='$phone' OR xiaohashu_id='$xhsId') + (SELECT COUNT(*) FROM t_following WHERE user_id=$id OR following_user_id=$id) + (SELECT COUNT(*) FROM t_fans WHERE user_id=$id OR fans_user_id=$id) + (SELECT COUNT(*) FROM t_note WHERE creator_id=$id) + (SELECT COUNT(*) FROM t_user_count WHERE user_id=$id) + (SELECT COUNT(*) FROM t_comment WHERE user_id=$id) + (SELECT COUNT(*) FROM t_note_like WHERE user_id=$id) + (SELECT COUNT(*) FROM t_note_collection WHERE user_id=$id) + (SELECT COUNT(*) FROM t_note_operate_outbox WHERE creator_id=$id);")
            $keys = @("following:$id", "fans:$id", "user:info:$id", "user:profile:$id", "user:roles:$id", "note:published:list:$id")
            if ($count -eq 0 -and [int](Invoke-Redis -Arguments (@('EXISTS') + $keys)) -eq 0) { $found = $true; break }
        }
        if (-not $found) { throw 'Could not reserve an unused user-ID hole with absent related data/caches.' }
        [void]$selected.Add($id)
        $user = @{ id = [string]$id; phone = $phone; xiaohashuId = $xhsId; nickname = "b-$RunId-$index"; existedBefore = $false; insertAttempted = $false; inserted = $false; ownershipProven = $false }
        $script:manifest.users += $user
        foreach ($key in $keys) { $script:manifest.redisKeys += @{ key = $key; entityType = 'user'; entityId = [string]$id; existedBefore = $false } }
        Save-Manifest
    }
    $noteCount = $Warmups + $Rounds * $PairsPerRound
    for ($index = 0; $index -lt $noteCount; $index++) {
        do {
            $id = [long](Get-Random -Minimum ([long]100000000000000) -Maximum ([long]900000000000000))
            $count = [long](Invoke-Sql "SELECT (SELECT COUNT(*) FROM t_note WHERE id=$id) + (SELECT COUNT(*) FROM t_note_operate_outbox WHERE note_id=$id) + (SELECT COUNT(*) FROM t_note_count WHERE note_id=$id);")
            $key = "note:detail:v2:$id"
        } while ($count -ne 0 -or [int](Invoke-Redis -Arguments @('EXISTS', $key)) -ne 0 -or @($script:manifest.notes | Where-Object { $_.id -eq [string]$id }).Count -gt 0)
        $uuid = [Guid]::NewGuid().ToString()
        $title = "bench-$RunId-$index"
        $prefix = "SQL read fixture; RunId=$RunId; note=$id; "
        $content = $prefix.PadRight($ContentCharacters, 'c')
        $script:manifest.notes += @{ id = [string]$id; uuid = $uuid; title = $title; content = $content; existedBefore = $false; contentExistedBefore = $null; contentAttempted = $false; contentAcknowledged = $false; insertAttempted = $false; inserted = $false; cacheInvalidated = $false; sqlDeleted = $false; kvDeleted = $false; fenceDeleted = $false }
        $script:manifest.redisKeys += @{ key = $key; entityType = 'note'; entityId = [string]$id; existedBefore = $false }
    }
    Save-Manifest
    $existingContent = Get-ContentRows -Notes @($script:manifest.notes)
    if ($existingContent.Count -ne 0) { throw 'A generated Cassandra UUID already exists; no content was written.' }
    foreach ($note in $script:manifest.notes) { $note.contentExistedBefore = $false }
    $documents = @($script:manifest.users | ForEach-Object { @{ _index = 'user'; _id = [string]$_.id } }) + @($script:manifest.notes | ForEach-Object { @{ _index = 'note'; _id = [string]$_.id } })
    $found = @(Get-EsFixturePresence -Documents $documents)
    $script:manifest.esPreflight = @{ checkedAt = [DateTime]::UtcNow.ToString('o'); documentCount = $documents.Count; found = $found }
    Save-Manifest
    if ($found.Count) { throw 'A candidate fixture ID has an existing Elasticsearch document; no SQL fixtures were written.' }
    $statements = [System.Collections.Generic.List[string]]::new()
    $statements.Add('START TRANSACTION;')
    foreach ($user in $script:manifest.users) {
        $user.insertAttempted = $true
        $statements.Add("INSERT INTO t_user(id,xiaohashu_id,nickname,phone,status,is_deleted,introduction) VALUES($($user.id),'$($user.xiaohashuId)','$($user.nickname)','$($user.phone)',0,b'0','SQL read benchmark $RunId');")
    }
    $ownerId = $script:manifest.users[0].id
    foreach ($target in ($script:manifest.users | Select-Object -Skip 1)) {
        $relationTime = ([DateTime]::new(2026, 1, 1)).AddSeconds([long]$target.id).ToString('yyyy-MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
        $script:manifest.relations += @{ ownerId = [string]$ownerId; targetId = [string]$target.id; createTime = $relationTime }
        $statements.Add("INSERT INTO t_following(user_id,following_user_id,create_time) VALUES($ownerId,$($target.id),'$relationTime');")
        $statements.Add("INSERT INTO t_fans(user_id,fans_user_id,create_time) VALUES($($target.id),$ownerId,'$relationTime');")
    }
    $statements.Add('COMMIT;')
    Save-Manifest
    $null = Invoke-Sql ($statements -join "`n")
    foreach ($user in $script:manifest.users) { $user.inserted = $true }
    Save-Manifest
    foreach ($note in $script:manifest.notes) {
        $note.contentAttempted = $true
        Save-Manifest
        $response = Invoke-Http -Url 'http://kv:8084/kv/note/content/add' -UserId ([long]$ownerId) -Body @{ uuid = $note.uuid; content = $note.content }
        Assert-ApiResponse $response
        $note.contentAcknowledged = $true
        Save-Manifest
    }
    $statements.Clear()
    $statements.Add('START TRANSACTION;')
    foreach ($note in $script:manifest.notes) {
        $note.insertAttempted = $true
        $title = Quote-Sql $note.title
        $statements.Add("INSERT INTO t_note(id,title,is_content_empty,creator_id,topic_id,topic_name,is_top,type,img_uris,video_uri,visible,create_time,update_time,status,content_uuid) VALUES($($note.id),$title,b'0',$ownerId,NULL,'',b'0',0,NULL,NULL,0,NOW(),NOW(),1,'$($note.uuid)');")
    }
    $statements.Add('COMMIT;')
    Save-Manifest
    $null = Invoke-Sql ($statements -join "`n")
    foreach ($note in $script:manifest.notes) { $note.inserted = $true }
    $script:manifest.status = 'prepared'
    Save-Manifest
    Write-Host "Fixtures prepared: 11 synthetic users, 10 following/fans pairs, $noteCount notes with real KV bodies."
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    return [Math]::Round($sorted[[Math]::Max(0, [int][Math]::Ceiling($Percentile * $sorted.Count) - 1)], 3)
}

function Get-SampleSummary {
    param([object[]]$Rows)
    $valid = @($Rows | Where-Object valid)
    $values = [double[]]@($valid | ForEach-Object elapsedMs)
    return @{
        sampleCount = $Rows.Count; validCount = $valid.Count; errorCount = $Rows.Count - $valid.Count
        meanMs = $(if ($values.Count) { [Math]::Round(($values | Measure-Object -Average).Average, 3) } else { $null })
        p50Ms = Get-Percentile $values 0.50; p95Ms = Get-Percentile $values 0.95
        eligible = ($Rows.Count -gt 0 -and $Rows.Count -eq $valid.Count)
        percentileMethod = 'nearest rank'; errorsAreExcludedFromTimingOnlyButInvalidateRun = $true
    }
}

function Export-Samples {
    foreach ($group in ($script:samples | Group-Object scenario, round)) {
        $first = $group.Group[0]
        $stem = "$($first.scenario)-round-$('{0:D2}' -f [int]$first.round)"
        Write-JsonFile -Path (Join-Path $script:runPath "$stem.json") -Value @($group.Group)
        $group.Group | Export-Csv -LiteralPath (Join-Path $script:runPath "$stem.csv") -NoTypeInformation -Encoding utf8
    }
}

function Remove-Fixtures {
    Write-Host 'Starting cleanup of manifest-owned fixtures.'
    $script:manifest.cleanup.status = 'running'
    Save-Manifest
    $ownerId = if ($script:manifest.users.Count) { $script:manifest.users[0].id } else { $null }
    $noteIds = @($script:manifest.notes | ForEach-Object id)
    # Validate every user before *any* mutation. A failed INSERT or a reused hole must
    # never grant permission to delete relationships or caches belonging to that ID.
    foreach ($user in $script:manifest.users) {
        if (-not $user.ContainsKey('ownershipProven')) { $user.ownershipProven = $false }
        if (-not $user.insertAttempted) { continue }
        $owned = [long](Invoke-Sql "SELECT COUNT(*) FROM t_user WHERE id=$($user.id) AND nickname='$($user.nickname)' AND phone='$($user.phone)' AND xiaohashu_id='$($user.xiaohashuId)';")
        $exists = [long](Invoke-Sql "SELECT COUNT(*) FROM t_user WHERE id=$($user.id);")
        if ($exists -ne $owned) { throw "Ownership mismatch for user $($user.id); all cleanup mutations refused." }
        if ($owned -eq 1 -or $user.inserted) { $user.ownershipProven = $true }
    }
    Save-Manifest
    # The private-visibility endpoint synchronously invalidates this note instance's Caffeine.
    # It emits only cache invalidations (operation_type=2), not publish-count deltas.
    foreach ($note in $script:manifest.notes) {
        if (-not $note.insertAttempted -or $note.sqlDeleted) { continue }
        $match = [long](Invoke-Sql "SELECT COUNT(*) FROM t_note WHERE id=$($note.id) AND creator_id=$ownerId AND title=$(Quote-Sql $note.title) AND content_uuid='$($note.uuid)';")
        if ($match -eq 0) {
            $anyRow = [long](Invoke-Sql "SELECT COUNT(*) FROM t_note WHERE id=$($note.id);")
            if ($anyRow) { throw "Ownership mismatch for note $($note.id); cleanup refused." }
            continue
        }
        if (-not $note.cacheInvalidated) {
            $response = Invoke-Http -Url 'http://note:8086/note/visible/onlyme' -UserId ([long]$ownerId) -Body @{ id = [long]$note.id }
            Assert-ApiResponse $response
            $note.cacheInvalidated = $true
            Save-Manifest
        }
    }
    if ($ownerId -and $noteIds.Count) {
        $idList = $noteIds -join ','
        $deadline = [DateTime]::UtcNow.AddSeconds($CleanupTimeoutSeconds)
        do {
            $pending = [long](Invoke-Sql "SELECT COUNT(*) FROM t_note_operate_outbox WHERE creator_id=$ownerId AND note_id IN($idList) AND operation_type=2 AND status<>'SENT';")
            if ($pending -eq 0) { break }
            Start-Sleep -Milliseconds 1000
        } while ([DateTime]::UtcNow -lt $deadline)
        if ($pending) { throw 'Own cache invalidation outbox is not SENT; retain fixtures and retry -CleanupOnly.' }
        $script:manifest.cleanup.outbox = @((Invoke-Sql "SELECT id,event_id,note_id,status FROM t_note_operate_outbox WHERE creator_id=$ownerId AND note_id IN($idList) AND operation_type=2;") -split '\r?\n' | Where-Object { $_ })
        Save-Manifest
    }
    foreach ($note in $script:manifest.notes) {
        if ($note.insertAttempted -and -not $note.sqlDeleted) {
            $null = Invoke-Sql "DELETE FROM t_note WHERE id=$($note.id) AND creator_id=$ownerId AND title=$(Quote-Sql $note.title) AND content_uuid='$($note.uuid)';"
            if ([long](Invoke-Sql "SELECT COUNT(*) FROM t_note WHERE id=$($note.id);") -ne 0) { throw "Note $($note.id) was not removed." }
            $note.sqlDeleted = $true
            Save-Manifest
        }
        if ($note.contentAttempted -and -not $note.kvDeleted -and $note.contentExistedBefore -eq $false) {
            $response = Invoke-Http -Url 'http://kv:8084/kv/note/content/delete' -UserId ([long]$ownerId) -Body @{ uuid = $note.uuid }
            Assert-ApiResponse $response
            $verify = Invoke-Http -Url 'http://kv:8084/kv/note/content/find' -UserId ([long]$ownerId) -Body @{ uuid = $note.uuid }
            if ($verify.httpStatus -ne 200 -or $verify.curlExitCode -ne 0 -or $null -eq $verify.body -or $verify.body.success -ne $false -or $verify.body.errorCode -ne 'KV-20000') { throw "KV deletion verification failed for fixture UUID $($note.uuid)." }
            $note.kvDeleted = $true
            Save-Manifest
        }
    }
    $contentNotes = @($script:manifest.notes | Where-Object { $_.kvDeleted -and -not $_.fenceDeleted })
    if ($contentNotes.Count) {
        $rows = Get-ContentRows $contentNotes
        $deletes = [System.Collections.Generic.List[string]]::new()
        foreach ($note in $contentNotes) {
            if (-not $note.contentAcknowledged) { throw "Retaining UUID $($note.uuid) deletion fence because the original KV add outcome was unknown." }
            # A previous cleanup may have deleted the fence before the manifest write completed.
            if (-not $rows.ContainsKey($note.uuid)) { $note.fenceDeleted = $true; continue }
            if ($rows[$note.uuid].is_deleted -ne $true -or $null -ne $rows[$note.uuid].content) { throw "Expected an empty deletion fence for UUID $($note.uuid)." }
            if ([long](Invoke-Sql "SELECT COUNT(*) FROM t_note WHERE content_uuid='$($note.uuid)';") -ne 0) { throw 'Fixture content is still referenced; physical fence cleanup refused.' }
            $deletes.Add("DELETE FROM xiaohashu.note_content WHERE id=$($note.uuid);")
        }
        # Each CQL statement names one UUID proved absent before this run and deleted via KV.
        if ($deletes.Count) { $null = Invoke-Cql ($deletes -join "`n") }
        $remaining = Get-ContentRows $contentNotes
        if ($remaining.Count) { throw 'A fixture Cassandra UUID remains after physical cleanup.' }
        foreach ($note in $contentNotes) { $note.fenceDeleted = $true }
        Save-Manifest
    }
    if ($ownerId) {
        $statements = [System.Collections.Generic.List[string]]::new()
        $statements.Add('START TRANSACTION;')
        $relations = if ($script:manifest.ContainsKey('relations')) { @($script:manifest.relations) } else {
            # Initial smoke manifests used the same deterministic timestamp expression.
            @($script:manifest.users | Select-Object -Skip 1 | ForEach-Object {
                @{ ownerId = [string]$ownerId; targetId = [string]$_.id; createTime = ([DateTime]::new(2026, 1, 1)).AddSeconds([long]$_.id).ToString('yyyy-MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture) }
            })
        }
        foreach ($relation in $relations) {
            $ownerOwned = $script:manifest.users[0].ownershipProven
            $targetOwned = @($script:manifest.users | Where-Object { $_.id -ceq $relation.targetId -and $_.ownershipProven }).Count -eq 1
            if ($ownerOwned -and $targetOwned) {
                $statements.Add("DELETE FROM t_following WHERE user_id=$ownerId AND following_user_id=$($relation.targetId) AND create_time='$($relation.createTime)';")
                $statements.Add("DELETE FROM t_fans WHERE user_id=$($relation.targetId) AND fans_user_id=$ownerId AND create_time='$($relation.createTime)';")
            }
        }
        foreach ($note in $script:manifest.notes) {
            if ($note.cacheInvalidated -and $script:manifest.users[0].ownershipProven) { $statements.Add("DELETE FROM t_note_operate_outbox WHERE creator_id=$ownerId AND note_id=$($note.id) AND operation_type=2 AND status='SENT';") }
        }
        foreach ($user in $script:manifest.users) {
            if ($user.ownershipProven) { $statements.Add("DELETE FROM t_user WHERE id=$($user.id) AND nickname='$($user.nickname)' AND phone='$($user.phone)' AND xiaohashu_id='$($user.xiaohashuId)';") }
        }
        $statements.Add('COMMIT;')
        $null = Invoke-Sql ($statements -join "`n")
    }
    # Allow already submitted asynchronous cache writes to finish, outside all timed samples.
    Start-Sleep -Milliseconds 2000
    foreach ($entry in $script:manifest.redisKeys) {
        if (Test-OwnedRedisKey $entry) { $null = Invoke-Redis -Arguments @('DEL', [string]$entry.key) }
    }
    Start-Sleep -Milliseconds 1000
    foreach ($entry in $script:manifest.redisKeys) {
        if ((Test-OwnedRedisKey $entry) -and [int](Invoke-Redis -Arguments @('EXISTS', [string]$entry.key)) -ne 0) { throw "Fixture Redis key reappeared: $($entry.key). Retry -CleanupOnly." }
    }
    if ($ownerId) {
        $ids = @($script:manifest.users | ForEach-Object id) -join ','
        $remaining = [long](Invoke-Sql "SELECT (SELECT COUNT(*) FROM t_user WHERE id IN($ids)) + (SELECT COUNT(*) FROM t_following WHERE user_id=$ownerId) + (SELECT COUNT(*) FROM t_fans WHERE fans_user_id=$ownerId) + (SELECT COUNT(*) FROM t_note WHERE creator_id=$ownerId) + (SELECT COUNT(*) FROM t_note_operate_outbox WHERE creator_id=$ownerId);")
        if ($remaining) { throw 'Own SQL fixture rows remain; inspect the manifest and retry -CleanupOnly.' }
    }
    $esDocuments = @($script:manifest.users | Where-Object ownershipProven | ForEach-Object { @{ _index = 'user'; _id = [string]$_.id } }) + @($script:manifest.notes | Where-Object { $_.inserted -or $_.cacheInvalidated } | ForEach-Object { @{ _index = 'note'; _id = [string]$_.id } })
    if ($esDocuments.Count) {
        $esDeadline = [DateTime]::UtcNow.AddSeconds(60)
        $absentChecks = 0
        $checks = [System.Collections.Generic.List[object]]::new()
        do {
            $found = @(Get-EsFixturePresence -Documents $esDocuments)
            $checks.Add(@{ at = [DateTime]::UtcNow.ToString('o'); found = $found })
            if ($found.Count -eq 0) { $absentChecks++ } else { $absentChecks = 0 }
            if ($absentChecks -ge 3) { break }
            Start-Sleep -Milliseconds 2000
        } while ([DateTime]::UtcNow -lt $esDeadline)
        $script:manifest.cleanup.esVerification = @{ documentCount = $esDocuments.Count; allAbsent = ($absentChecks -ge 3); checks = $checks.ToArray() }
        Save-Manifest
        if ($absentChecks -lt 3) { throw 'Own Elasticsearch documents have not converged to found=false; no ES document was manually deleted. Retry -CleanupOnly.' }
    }
    $script:manifest.cleanup.status = 'complete'
    $script:manifest.cleanup.completedAt = [DateTime]::UtcNow.ToString('o')
    Save-Manifest
    Write-Host 'Cleanup completed: own SQL/Redis/Cassandra fixtures removed; own ES documents absent. Author user-service L1 expires naturally.'
}

function Test-OwnedRedisKey {
    param([hashtable]$Entry)
    if ($Entry.existedBefore -ne $false) { return $false }
    # Compatibility for manifests created by the first smoke version of this script.
    $type = if ($Entry.ContainsKey('entityType')) { $Entry.entityType } elseif ($Entry.key.StartsWith('note:detail:v2:')) { 'note' } else { 'user' }
    $id = if ($Entry.ContainsKey('entityId')) { $Entry.entityId } else { ($Entry.key -split ':')[-1] }
    if ($type -eq 'user') { return @($script:manifest.users | Where-Object { $_.id -ceq $id -and $_.ownershipProven }).Count -eq 1 }
    return @($script:manifest.notes | Where-Object { $_.id -ceq $id -and ($_.inserted -or $_.cacheInvalidated) }).Count -eq 1
}

if ($CleanupOnly) {
    if (-not (Test-Path -LiteralPath $script:manifestPath -PathType Leaf)) { throw 'No manifest exists for that RunId.' }
    $script:manifest = Get-Content -LiteralPath $script:manifestPath -Raw | ConvertFrom-Json -AsHashtable
    if ($script:manifest.schemaVersion -ne 1 -or $script:manifest.runId -cne $RunId -or $script:manifest.projectName -cne $ProjectName) { throw 'Manifest identity does not match this script/run/project.' }
    try { Remove-Fixtures } catch {
        $script:manifest.cleanup.status = 'failed'; $script:manifest.cleanup.errors += $_.Exception.Message; Save-Manifest; throw
    }
    Write-Output "Cleanup completed: $script:manifestPath"
    return
}

if (Test-Path -LiteralPath $script:runPath) { throw 'RunId output already exists. Choose a new RunId; results are never overwritten.' }
[void][IO.Directory]::CreateDirectory($script:runPath)
$script:manifest = @{
    schemaVersion = 1; runId = $RunId; projectName = $ProjectName; status = 'planning'; updatedAt = ''; startedAt = [DateTime]::UtcNow.ToString('o')
    parameters = @{ rounds = $Rounds; followingSamplesPerRound = $FollowingSamplesPerRound; pairsPerRound = $PairsPerRound; warmups = $Warmups; contentCharacters = $ContentCharacters; sampleIntervalMs = $SampleIntervalMilliseconds; maxIdleCpuPercent = $MaxIdleCpuPercent }
    users = @(); notes = @(); relations = @(); redisKeys = @(); cleanup = @{ status = 'not-started'; completedAt = $null; errors = @(); outbox = @() }
    containers = $script:containers; fixtureKind = 'Direct SQL read fixtures; note content written through the real KV/add API; no follow/publish/count events.'
}
Save-Manifest
if ($PrepareOnly) {
    try {
        $script:manifest.preparationOnly = $true
        Save-Manifest
        Assert-Healthy @(Get-ContainerMetadata)
        New-Fixtures
        Write-Output "Prepared fixtures retained: $script:manifestPath"
        Write-Output 'Use the same RunId with -CleanupOnly after the external benchmark.'
    } catch {
        $preparationFailure = $_
        $script:manifest.status = 'failed'
        Save-Manifest
        try { Remove-Fixtures } catch {
            $script:manifest.cleanup.status = 'failed'
            $script:manifest.cleanup.errors += $_.Exception.Message
            Save-Manifest
        }
        throw $preparationFailure
    }
    return
}
$failure = $null
$measurementBefore = @()
$measurementAfter = @()
try {
    Assert-Healthy @(Get-ContainerMetadata)
    $infoTemplate = '{"nCpu":{{.NCPU}},"memTotalBytes":{{.MemTotal}},"serverVersion":{{json .ServerVersion}},"operatingSystem":{{json .OperatingSystem}}}'
    Write-JsonFile -Path (Join-Path $script:runPath 'docker-info.json') -Value ((Invoke-CheckedDocker -Arguments @('info', '--format', $infoTemplate)) | ConvertFrom-Json)
    Write-JsonFile -Path (Join-Path $script:runPath 'containers-initial.json') -Value @(Get-ContainerMetadata)
    New-Fixtures
    Wait-Stable 'prepared'
    $ownerId = [long]$script:manifest.users[0].id
    $ownerWarm = Invoke-Http -Url 'http://user:8082/user/findById' -UserId $ownerId -Body @{ id = $ownerId }
    Assert-ApiResponse $ownerWarm
    for ($index = 0; $index -lt $Warmups; $index++) {
        Measure-Following -Round 0 -Index ($index + 1)
        Measure-Note -Note $script:manifest.notes[$index] -Round 0 -Index ($index + 1) -Variant 'first-source-read'
        Measure-Note -Note $script:manifest.notes[$index] -Round 0 -Index ($index + 1) -Variant 'immediate-local-cache-read'
        if ($SampleIntervalMilliseconds) { Start-Sleep -Milliseconds $SampleIntervalMilliseconds }
    }
    if ($Warmups -eq 0) {
        # Still populate the following and author caches without counting this as a formal sample.
        Measure-Following -Round 0 -Index 1
    }
    Wait-Stable 'warmed'
    Write-Host 'Warmup completed and containers stable; starting formal samples.'
    $followingCardinality = [int](Invoke-Redis -Arguments @('ZCARD', "following:$ownerId"))
    if ($followingCardinality -ne 10) { throw 'Following Redis fixture is not a ten-member hot ZSet.' }
    foreach ($user in $script:manifest.users) {
        if ([int](Invoke-Redis -Arguments @('EXISTS', "user:info:$($user.id)")) -ne 1) { throw 'A fixture user-info cache is missing after warmup.' }
    }
    $formalNotes = @($script:manifest.notes | Select-Object -Skip $Warmups)
    $formalKeys = @($formalNotes | ForEach-Object { "note:detail:v2:$($_.id)" })
    if ([int](Invoke-Redis -Arguments (@('EXISTS') + $formalKeys)) -ne 0) { throw 'A formal note detail key already exists before its first measured read.' }
    Write-JsonFile -Path (Join-Path $script:runPath 'cache-preconditions.json') -Value @{ at = [DateTime]::UtcNow.ToString('o'); followingZCard = $followingCardinality; userInfoKeysPresent = 11; formalNoteDetailKeysPresent = 0; formalNoteCount = $formalNotes.Count }
    $measurementBefore = @(Get-ContainerMetadata)
    Write-JsonFile -Path (Join-Path $script:runPath 'containers-before.json') -Value $measurementBefore
    Write-JsonFile -Path (Join-Path $script:runPath 'stats-before.json') -Value @(Get-Stats)
    $script:manifest.status = 'measuring'; Save-Manifest
    for ($round = 1; $round -le $Rounds; $round++) {
        for ($index = 1; $index -le $FollowingSamplesPerRound; $index++) {
            Measure-Following -Round $round -Index $index
            if ($SampleIntervalMilliseconds) { Start-Sleep -Milliseconds $SampleIntervalMilliseconds }
        }
        for ($index = 1; $index -le $PairsPerRound; $index++) {
            $note = $script:manifest.notes[$Warmups + ($round - 1) * $PairsPerRound + $index - 1]
            Measure-Note -Note $note -Round $round -Index $index -Variant 'first-source-read'
            Measure-Note -Note $note -Round $round -Index $index -Variant 'immediate-local-cache-read'
            if ($SampleIntervalMilliseconds) { Start-Sleep -Milliseconds $SampleIntervalMilliseconds }
        }
        Export-Samples
        Write-JsonFile -Path (Join-Path $script:runPath "stats-after-round-$round.json") -Value @(Get-Stats)
        $roundFollowing = Get-SampleSummary @($script:samples | Where-Object { $_.round -eq $round -and $_.scenario -eq 'following' })
        $roundCold = Get-SampleSummary @($script:samples | Where-Object { $_.round -eq $round -and $_.scenario -eq 'note' -and $_.variant -eq 'first-source-read' })
        $roundHot = Get-SampleSummary @($script:samples | Where-Object { $_.round -eq $round -and $_.scenario -eq 'note' -and $_.variant -eq 'immediate-local-cache-read' })
        Write-Host "Round $round completed: following mean=$($roundFollowing.meanMs) ms; first detail=$($roundCold.meanMs) ms; immediate detail=$($roundHot.meanMs) ms."
    }
    $script:manifest.status = 'measured'
} catch {
    $failure = $_.Exception.Message
    $script:manifest.status = 'failed'
} finally {
    try {
        $measurementAfter = @(Get-ContainerMetadata)
        Write-JsonFile -Path (Join-Path $script:runPath 'containers-after.json') -Value $measurementAfter
        Write-JsonFile -Path (Join-Path $script:runPath 'stats-after.json') -Value @(Get-Stats)
    } catch { if (-not $failure) { $failure = $_.Exception.Message } }
    Export-Samples
    try { Remove-Fixtures } catch {
        $script:manifest.cleanup.status = 'failed'
        $script:manifest.cleanup.errors += $_.Exception.Message
        $script:cleanupErrors.Add($_.Exception.Message)
        Save-Manifest
    }
}

$restarts = [System.Collections.Generic.List[string]]::new()
foreach ($before in $measurementBefore) {
    $after = @($measurementAfter | Where-Object { $_.name -ceq $before.name })
    if ($after.Count -ne 1 -or $after[0].id -cne $before.id -or $after[0].startedAt -cne $before.startedAt -or $after[0].restartCount -ne $before.restartCount -or -not $after[0].running -or $after[0].health -notin @('healthy', 'none')) { $restarts.Add($before.name) }
}
$formal = @($script:samples | Where-Object { $_.round -gt 0 })
$following = Get-SampleSummary @($formal | Where-Object scenario -eq 'following')
$cold = Get-SampleSummary @($formal | Where-Object { $_.scenario -eq 'note' -and $_.variant -eq 'first-source-read' })
$hot = Get-SampleSummary @($formal | Where-Object { $_.scenario -eq 'note' -and $_.variant -eq 'immediate-local-cache-read' })
$expectedSamples = $Rounds * ($FollowingSamplesPerRound + 2 * $PairsPerRound)
$eligible = -not $failure -and $script:manifest.cleanup.status -eq 'complete' -and $restarts.Count -eq 0 -and $formal.Count -eq $expectedSamples -and @($formal | Where-Object { -not $_.valid }).Count -eq 0
$perRound = foreach ($round in 1..$Rounds) {
    @{ round = $round
        following = Get-SampleSummary @($formal | Where-Object { $_.round -eq $round -and $_.scenario -eq 'following' })
        noteFirstRead = Get-SampleSummary @($formal | Where-Object { $_.round -eq $round -and $_.scenario -eq 'note' -and $_.variant -eq 'first-source-read' })
        noteImmediateRead = Get-SampleSummary @($formal | Where-Object { $_.round -eq $round -and $_.scenario -eq 'note' -and $_.variant -eq 'immediate-local-cache-read' })
    }
}
$report = @{
    runId = $RunId; eligibleForResume = $eligible; failure = $failure; cleanup = $script:manifest.cleanup; expectedFormalSamples = $expectedSamples; actualFormalSamples = $formal.Count
    warmupSamples = @($script:samples | Where-Object round -eq 0).Count; restartedOrUnhealthyContainers = $restarts.ToArray()
    followingHotHttp = $following; noteFirstReadHttp = $cold; noteImmediateReadHttp = $hot; perRound = @($perRound)
    noteMeanReductionPercent = $(if ($eligible -and $cold.meanMs -gt 0) { [Math]::Round((1 - $hot.meanMs / $cold.meanMs) * 100, 2) } else { $null })
    scope = @('Existing local Docker deployment; single client, concurrency one; direct internal HTTP from frontend curl.', 'time_total includes DNS/connect/response transfer; excludes docker exec startup and host parsing; a fresh curl/TCP connection per sample.', 'Ten-item following page, relation ZSet and user-info Redis caches warmed; the Feign user RPC is included.', 'New independent SQL notes with real Cassandra bodies: first read versus the immediate second read on the same note container. Backend DB/OS caches are warm; first-source-read does not mean cold operating-system caches.', 'Current code synchronously populates Caffeine when a complete author/body detail is returned; no instrumentation was added to production.', 'No claimed parallel-RPC speedup, sustained throughput, public-network latency or production-capacity result.', 'SQL fixtures bypass publication and count updates; cache-only outbox is used during cleanup. Auto-increment sequences may retain normal allocation gaps.', 'The synthetic author has one user-service Caffeine profile entry that cannot be externally invalidated; it expires naturally after one hour. Cleanup verifies own SQL, Redis and Cassandra data and invalidates note-service Caffeine via the private-visibility API. Shared user services are not restarted.')
}
Write-JsonFile -Path (Join-Path $script:runPath 'report.json') -Value $report
$script:manifest.status = $(if ($eligible) { 'complete' } else { 'invalid' })
Save-Manifest
Write-Output "Report: $(Join-Path $script:runPath 'report.json')"
Write-Output "Eligible for resume: $eligible; following mean=$($following.meanMs) ms; first note mean=$($cold.meanMs) ms; immediate note mean=$($hot.meanMs) ms."
if (-not $eligible) { throw "Benchmark run is not eligible. Failure: $failure; cleanup: $($script:manifest.cleanup.status); inspect report.json and manifest.json." }
