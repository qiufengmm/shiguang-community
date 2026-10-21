#requires -Version 7.2
<#
.SYNOPSIS
Runs an existing CLI JMeter plan in one temporary, owned Docker container.
.DESCRIPTION
No fixture is seeded or removed by this runner. Shared application services are never
stopped. The installed JMeter distribution and plan folder are mounted read-only.
All raw CSV samples include ramp-up and warmup; steady-state analysis is a separate step.
Host is an alias for TargetHost because PowerShell's automatic $Host variable is read-only.
.EXAMPLE
pwsh -File infra/benchmarks/Invoke-JMeterLoad.ps1 -RunId leaf-smoke-01 -Scenario leaf -Threads 10 -RampSeconds 2 -WarmupSeconds 3 -DurationSeconds 5
.EXAMPLE
pwsh -File infra/benchmarks/Invoke-JMeterLoad.ps1 -RunId following-100-01 -Scenario following -Threads 100 -RampSeconds 10 -WarmupSeconds 20 -DurationSeconds 30 -FixtureManifestPath F:/code/xiaohashu/xiaohashu/infra/.runtime/benchmarks/fixture/manifest.json
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]{0,39}$')]
    [string]$RunId,
    [ValidateSet('leaf', 'following')] [string]$Scenario = 'leaf',
    [ValidateRange(1, 256)] [int]$Threads = 100,
    [ValidateRange(0, 600)] [int]$RampSeconds = 10,
    [ValidateRange(0, 600)] [int]$WarmupSeconds = 20,
    [ValidateRange(1, 3600)] [int]$DurationSeconds = 30,
    [Alias('Host')] [string]$TargetHost,
    [ValidateRange(0, 65535)] [int]$Port = 0,
    [string]$Path,
    [string]$FixtureManifestPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:docker = (Get-Command docker -ErrorAction Stop).Source
$script:jmeterHome = 'D:\jmeter\apache-jmeter-5.6.3'
$script:jmeterImage = 'eclipse-temurin:17-jre'
$script:networkName = 'xiaohashu_default'
$script:planRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$script:runRoot = Join-Path (Split-Path $PSScriptRoot -Parent) '.runtime/benchmarks'
$script:runPath = Join-Path $script:runRoot $RunId
$script:metadataPath = Join-Path $script:runPath 'jmeter-metadata.json'
$script:ownerToken = [Guid]::NewGuid().ToString('N')
$script:containerName = 'resume-jmeter-' + $RunId.ToLowerInvariant() + '-' + $script:ownerToken.Substring(0, 8)
$script:containerId = $null
$script:creationAttempted = $false
$script:metadata = $null
$script:monitorIndex = 0
$script:watchedNames = @()
$script:targetContainerName = $null

function Write-Json {
    param([string]$FilePath, [object]$Value)
    [IO.File]::WriteAllText($FilePath, (ConvertTo-Json -InputObject $Value -Depth 40), [Text.UTF8Encoding]::new($false))
}

function Save-Metadata {
    $script:metadata.updatedAt = [DateTime]::UtcNow.ToString('o')
    Write-Json -FilePath $script:metadataPath -Value $script:metadata
}

function Invoke-Docker {
    param([string[]]$Arguments, [int]$TimeoutSeconds = 20)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $script:docker
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $start.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            throw "Docker client command timed out after $TimeoutSeconds seconds."
        }
        return @{ exitCode = $process.ExitCode; stdout = $stdout.GetAwaiter().GetResult(); stderr = $stderr.GetAwaiter().GetResult() }
    } finally { $process.Dispose() }
}

function Invoke-CheckedDocker {
    param([string[]]$Arguments, [int]$TimeoutSeconds = 20)
    $result = Invoke-Docker -Arguments $Arguments -TimeoutSeconds $TimeoutSeconds
    if ($result.exitCode -ne 0) { throw "Docker operation failed (exit $($result.exitCode)): $($result.stderr.Trim())" }
    return $result.stdout.Trim()
}

function Get-ContainerState {
    param([string[]]$Names)
    # Intentionally select only non-secret identity/resource/state fields, never Config.Env.
    $format = '{"name":{{json .Name}},"id":{{json .Id}},"image":{{json .Config.Image}},"imageId":{{json .Image}},"startedAt":{{json .State.StartedAt}},"finishedAt":{{json .State.FinishedAt}},"restartCount":{{.RestartCount}},"running":{{.State.Running}},"oomKilled":{{.State.OOMKilled}},"exitCode":{{.State.ExitCode}},"status":{{json .State.Status}},"health":{{with index .State "Health"}}{{json .Status}}{{else}}"none"{{end}},"memoryLimitBytes":{{.HostConfig.Memory}},"nanoCpus":{{.HostConfig.NanoCpus}},"cpuQuota":{{.HostConfig.CpuQuota}},"cpuPeriod":{{.HostConfig.CpuPeriod}}}'
    $text = Invoke-CheckedDocker -Arguments (@('inspect', '--type', 'container', '--format', $format) + $Names)
    return @($text -split '\r?\n' | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json -AsHashtable })
}

function Get-Stats {
    param([string[]]$Names)
    $text = Invoke-CheckedDocker -Arguments (@('stats', '--no-stream', '--format', '{{json .}}') + $Names)
    return @($text -split '\r?\n' | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json -AsHashtable })
}

function Assert-TargetsUnchanged {
    param([object[]]$Current, [object[]]$Baseline)
    foreach ($original in $Baseline) {
        $stateMatches = @($Current | Where-Object { $_.name -ceq $original.name })
        if ($stateMatches.Count -ne 1) { throw "Monitored application container disappeared: $($original.name)." }
        $item = $stateMatches[0]
        if (-not $item.running -or $item.health -notin @('healthy', 'none') -or $item.oomKilled) { throw "Monitored application became unhealthy/stopped/OOM: $($item.name)." }
        if ($item.id -cne $original.id -or $item.startedAt -cne $original.startedAt -or $item.restartCount -ne $original.restartCount) { throw "Monitored application container restarted or was replaced: $($item.name)." }
    }
}

function Add-MonitorSnapshot {
    param([object[]]$Baseline, [switch]$IncludeLoadGenerator)
    $script:monitorIndex++
    $targets = @(Get-ContainerState -Names $script:watchedNames)
    $names = @($script:watchedNames)
    $loadGenerator = $null
    if ($IncludeLoadGenerator -and $script:containerId) {
        $loadGenerator = @(Get-ContainerState -Names @($script:containerId))[0]
        if ($loadGenerator.running) { $names += $script:containerId }
    }
    $snapshot = @{ sequence = $script:monitorIndex; capturedAt = [DateTime]::UtcNow.ToString('o'); targets = $targets; loadGenerator = $loadGenerator; stats = @(Get-Stats -Names $names) }
    [IO.File]::AppendAllText((Join-Path $script:runPath 'jmeter-monitor.jsonl'), ((ConvertTo-Json -InputObject $snapshot -Depth 20 -Compress) + "`n"), [Text.UTF8Encoding]::new($false))
    Assert-TargetsUnchanged -Current $targets -Baseline $Baseline
    if ($null -ne $loadGenerator -and $loadGenerator.oomKilled) { throw 'The owned JMeter load generator was OOM-killed; this run is invalid.' }
    return $snapshot
}

function Resolve-SingleTarget {
    param([string]$RequestedHost)
    $text = Invoke-CheckedDocker -Arguments @('network', 'inspect', $script:networkName, '--format', '{{json .Containers}}')
    $members = $text | ConvertFrom-Json -AsHashtable
    $targetMatches = @($members.GetEnumerator() | Where-Object {
        $_.Value.Name -ceq $RequestedHost -or ($_.Value.IPv4Address -split '/')[0] -ceq $RequestedHost -or $_.Key -ceq $RequestedHost
    })
    if ($targetMatches.Count -ne 1) { throw 'Host must identify exactly one container by full name, full ID or IPv4 address on xiaohashu_default; use the single-container defaults.' }
    $member = $targetMatches[0]
    return @{ id = $member.Key; name = $member.Value.Name; ipv4Address = ($member.Value.IPv4Address -split '/')[0]; network = $script:networkName; requestedHost = $RequestedHost }
}

function Get-OwnedContainer {
    # The owner token and run label must match before every stop/remove operation.
    $identity = if ($script:containerId) { $script:containerId } else { $script:containerName }
    $format = '{"id":{{json .Id}},"name":{{json .Name}},"owner":{{json (index .Config.Labels "codex.benchmark.owner")}},"runId":{{json (index .Config.Labels "codex.benchmark.run-id")}},"kind":{{json (index .Config.Labels "codex.benchmark.kind")}},"running":{{.State.Running}},"oomKilled":{{.State.OOMKilled}},"exitCode":{{.State.ExitCode}},"startedAt":{{json .State.StartedAt}},"finishedAt":{{json .State.FinishedAt}}}'
    $result = Invoke-Docker -Arguments @('inspect', '--type', 'container', '--format', $format, $identity)
    if ($result.exitCode -ne 0) {
        if ($result.stderr -match '(No such (object|container)|not found)') { return $null }
        throw "Cannot verify owned JMeter container identity: $($result.stderr.Trim())"
    }
    $item = $result.stdout.Trim() | ConvertFrom-Json -AsHashtable
    if ($item.owner -cne $script:ownerToken -or $item.runId -cne $RunId -or $item.kind -cne 'jmeter' -or $item.name -cne ('/' + $script:containerName)) { throw 'JMeter container ownership did not match; no stop/remove operation is permitted.' }
    if ($script:containerId -and $item.id -cne $script:containerId) { throw 'Owned JMeter container ID changed; refusing to stop/remove it.' }
    return $item
}

function Stop-OwnedContainer {
    param([string]$Reason)
    $owned = Get-OwnedContainer
    if ($null -eq $owned) { return }
    if ($owned.running) {
        $script:metadata.loadGenerator.stopRequestedAt = [DateTime]::UtcNow.ToString('o')
        $script:metadata.loadGenerator.stopReason = $Reason
        Save-Metadata
        $null = Invoke-CheckedDocker -Arguments @('stop', '--time', '8', $owned.id) -TimeoutSeconds 15
    }
}

function Save-OwnedLogs {
    $owned = Get-OwnedContainer
    if ($null -eq $owned) { return }
    $logs = Invoke-Docker -Arguments @('logs', $owned.id)
    [IO.File]::WriteAllText((Join-Path $script:runPath 'jmeter-container.stdout.log'), $logs.stdout, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $script:runPath 'jmeter-container.stderr.log'), $logs.stderr, [Text.UTF8Encoding]::new($false))
    if ($logs.exitCode -ne 0) { throw 'Could not preserve the owned JMeter container logs.' }
}

function Remove-OwnedContainer {
    $owned = Get-OwnedContainer
    if ($null -eq $owned) { $script:metadata.cleanup.status = 'already-absent'; return }
    if ($owned.running) { Stop-OwnedContainer -Reason 'runner finally cleanup' }
    $owned = Get-OwnedContainer
    if ($null -eq $owned) { $script:metadata.cleanup.status = 'already-absent'; return }
    if ($owned.running) { throw 'Owned JMeter container is still running; refusing an unverified forced removal.' }
    $script:metadata.loadGenerator.finalState = $owned
    Save-Metadata
    $null = Invoke-CheckedDocker -Arguments @('rm', $owned.id)
    $script:metadata.cleanup.status = 'removed'
    $script:metadata.cleanup.removedAt = [DateTime]::UtcNow.ToString('o')
}

function Read-FollowingFixture {
    param([string]$ManifestFile)
    if ([string]::IsNullOrWhiteSpace($ManifestFile) -or -not (Test-Path -LiteralPath $ManifestFile -PathType Leaf)) { throw 'following requires an existing FixtureManifestPath; this runner does not seed data.' }
    $resolved = (Resolve-Path -LiteralPath $ManifestFile).Path
    $fixture = Get-Content -LiteralPath $resolved -Raw | ConvertFrom-Json -AsHashtable
    $ownerId = $null
    $expectedIds = @()
    if ($fixture.ContainsKey('users')) {
        $users = @($fixture.users)
        if ($users.Count -ne 11) { throw 'Fixture manifest users must contain one owner followed by ten targets.' }
        $ownerId = [string]$users[0].id
        $expectedIds = @($users | Select-Object -Skip 1 | ForEach-Object { [string]$_.id })
    } elseif ($fixture.ContainsKey('owner_id') -and $fixture.ContainsKey('expected_user_ids')) {
        $ownerId = [string]$fixture.owner_id
        $expectedIds = if ($fixture.expected_user_ids -is [string]) { @($fixture.expected_user_ids -split ',') } else { @($fixture.expected_user_ids | ForEach-Object { [string]$_ }) }
    } else { throw 'Fixture manifest must contain users[owner,10 targets], or owner_id and expected_user_ids.' }
    if ($ownerId -notmatch '^[1-9][0-9]*$' -or $expectedIds.Count -ne 10 -or @($expectedIds | Where-Object { $_ -notmatch '^[1-9][0-9]*$' }).Count -gt 0) { throw 'Following fixture IDs must be positive decimal IDs with ten targets.' }
    if (@($expectedIds | Sort-Object -Unique).Count -ne 10 -or $ownerId -in $expectedIds) { throw 'Following target IDs must be unique and must exclude the owner.' }
    # Check Long range without converting through floating point or JavaScript Number.
    [void][long]::Parse($ownerId, [Globalization.CultureInfo]::InvariantCulture)
    foreach ($id in $expectedIds) { [void][long]::Parse($id, [Globalization.CultureInfo]::InvariantCulture) }
    return @{ path = $resolved; sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash; ownerId = $ownerId; expectedUserIds = $expectedIds; seedResponsibility = 'external; runner is read-only toward fixtures' }
}

if ([string]::IsNullOrWhiteSpace($TargetHost)) { $TargetHost = if ($Scenario -eq 'leaf') { 'xiaohashu-distributed-id-generator-1' } else { 'xiaohashu-user-relation-1' } }
if ($Port -eq 0) { $Port = if ($Scenario -eq 'leaf') { 8085 } else { 8087 } }
if ([string]::IsNullOrWhiteSpace($Path)) { $Path = if ($Scenario -eq 'leaf') { '/id/snowflake/get/resume-bench' } else { '/relation/following/list' } }
if ($TargetHost -notmatch '^[A-Za-z0-9_.:-]+$' -or -not $Path.StartsWith('/') -or $Path -match '[\r\n]') { throw 'Invalid target host/path.' }
$planPath = Join-Path $script:planRoot "$Scenario-load.jmx"
$assertionPath = Join-Path $script:planRoot 'following-assert.groovy'
$jarPath = Join-Path $script:jmeterHome 'bin/ApacheJMeter.jar'
foreach ($file in @($PSCommandPath, $planPath, $jarPath)) { if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required file does not exist: $file" } }
if ($Scenario -eq 'following' -and -not (Test-Path -LiteralPath $assertionPath -PathType Leaf)) { throw 'The following assertion script does not exist.' }
$fixture = if ($Scenario -eq 'following') { Read-FollowingFixture -ManifestFile $FixtureManifestPath } else { $null }
if (Test-Path -LiteralPath $script:runPath) { throw 'RunId output already exists; choose a new RunId. Existing results are never overwritten.' }
[void][IO.Directory]::CreateDirectory($script:runPath)
$totalSeconds = $RampSeconds + $WarmupSeconds + $DurationSeconds
$script:metadata = @{
    schemaVersion = 1; runId = $RunId; status = 'preparing'; startedAt = [DateTime]::UtcNow.ToString('o'); finishedAt = $null; updatedAt = ''; failure = $null
    parameters = @{ scenario = $Scenario; threads = $Threads; rampSeconds = $RampSeconds; warmupSeconds = $WarmupSeconds; durationSeconds = $DurationSeconds; schedulerSeconds = $totalSeconds; host = $TargetHost; port = $Port; path = $Path; connectTimeoutMs = 3000; responseTimeoutMs = 5000 }
    fixture = $fixture; targetIdentity = $null; targetBaseline = @(); targetAfter = @()
    runtimePath = $script:runPath; network = $script:networkName
    hashes = @{ runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash; planSha256 = (Get-FileHash -LiteralPath $planPath -Algorithm SHA256).Hash; jmeterLauncherSha256 = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash; assertionSha256 = $(if ($Scenario -eq 'following') { (Get-FileHash -LiteralPath $assertionPath -Algorithm SHA256).Hash } else { $null }) }
    jmeter = @{ installedHome = $script:jmeterHome; installedVersion = '5.6.3'; cli = $true; rawSamplesFile = 'samples.jtl'; sampleVariables = 'issued_id'; allPhasesRetained = $true; listeners = 'Plan has no GUI result listeners; CSV written with -l.' }
    loadGenerator = @{ ownerToken = $script:ownerToken; containerName = $script:containerName; containerId = $null; image = $script:jmeterImage; imageId = $null; createdAt = $null; startedAt = $null; stopRequestedAt = $null; stopReason = $null; finalState = $null; memoryLimitBytes = 536870912; javaOptions = @('-Xms64m', '-Xmx256m', '-Xss320k', '-XX:MaxMetaspaceSize=128m', '-XX:MaxDirectMemorySize=32m', '-XX:ActiveProcessorCount=2', '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-Duser.timezone=UTC') }
    monitoring = @{ nominalIntervalSeconds = 5; samples = 0; snapshotsFile = 'jmeter-monitor.jsonl'; detection = 'running, health, OOMKilled, identity, StartedAt, RestartCount'; onlyOwnedLoadGeneratorMayBeStopped = $true }
    cleanup = @{ status = 'not-started'; removedAt = $null; errors = @() }
    interpretation = @('Thread group is closed-loop and uses HTTP keep-alive; ramp-up and warmup are retained in raw CSV.', 'Steady state starts at first sample timestamp + ramp_seconds + warmup_seconds; analyze exactly duration_seconds after that boundary.', 'A zero JMeter process exit code does not establish HTTP/business success or a throughput claim; raw CSV assertions and steady-state statistics require separate analysis.', 'The runner never modifies production source, fixtures, shared application configuration or shared service lifecycle.', 'The default Leaf path is Snowflake with a nonempty label; it does not consume a production segment business tag.')
}
Save-Metadata
$baseline = @()
$runFailure = $null
$loadFinished = $false
try {
    $target = Resolve-SingleTarget -RequestedHost $TargetHost
    $script:targetContainerName = $target.name
    $script:metadata.targetIdentity = $target
    $script:watchedNames = @($target.name)
    if ($Scenario -eq 'following') { $script:watchedNames += @('xiaohashu-user-1', 'xiaohashu-redis-1') }
    $script:watchedNames = @($script:watchedNames | Select-Object -Unique)
    $baseline = @(Get-ContainerState -Names $script:watchedNames)
    Assert-TargetsUnchanged -Current $baseline -Baseline $baseline
    $script:metadata.targetBaseline = $baseline
    Write-Json -FilePath (Join-Path $script:runPath 'target-before.json') -Value $baseline
    Write-Json -FilePath (Join-Path $script:runPath 'stats-before.json') -Value @(Get-Stats -Names $script:watchedNames)
    $dockerInfoFormat = '{"nCpu":{{.NCPU}},"memTotalBytes":{{.MemTotal}},"operatingSystem":{{json .OperatingSystem}},"serverVersion":{{json .ServerVersion}}}'
    Write-Json -FilePath (Join-Path $script:runPath 'docker-info.json') -Value ((Invoke-CheckedDocker -Arguments @('info', '--format', $dockerInfoFormat)) | ConvertFrom-Json -AsHashtable)
    # image inspect is a local existence check; --pull=never is also explicit on create.
    $script:metadata.loadGenerator.imageId = Invoke-CheckedDocker -Arguments @('image', 'inspect', $script:jmeterImage, '--format', '{{.Id}}')
    $existing = Invoke-Docker -Arguments @('inspect', '--type', 'container', $script:containerName)
    if ($existing.exitCode -eq 0) { throw 'The unique load-generator container name already exists; refusing to reuse it.' }
    if ($existing.stderr -notmatch '(No such (object|container)|not found)') { throw 'Could not verify that the unique load-generator name is available.' }
    $createArguments = @('create', '--pull=never', '--name', $script:containerName,
        '--label', "codex.benchmark.owner=$script:ownerToken", '--label', "codex.benchmark.run-id=$RunId", '--label', 'codex.benchmark.kind=jmeter',
        '--network', $script:networkName, '--memory', '512m', '--init',
        '--mount', "type=bind,source=$script:jmeterHome,target=/jmeter,readonly",
        '--mount', "type=bind,source=$script:planRoot,target=/plans,readonly",
        '--mount', "type=bind,source=$script:runPath,target=/results", '--workdir', '/results', '--entrypoint', 'java', $script:jmeterImage)
    $createArguments += @($script:metadata.loadGenerator.javaOptions)
    $createArguments += @('-jar', '/jmeter/bin/ApacheJMeter.jar', '-n', '-t', "/plans/$Scenario-load.jmx", '-l', '/results/samples.jtl', '-j', '/results/jmeter.log',
        "-Jthreads=$Threads", "-Jramp_seconds=$RampSeconds", "-Jwarmup_seconds=$WarmupSeconds", "-Jduration_seconds=$DurationSeconds", "-Jhost=$TargetHost", "-Jport=$Port", "-Jpath=$Path",
        '-Jconnect_timeout_ms=3000', '-Jresponse_timeout_ms=5000', '-Jsample_variables=issued_id',
        '-Jjmeter.save.saveservice.output_format=csv', '-Jjmeter.save.saveservice.print_field_names=true', '-Jjmeter.save.saveservice.timestamp_format=ms', '-Jsampleresult.timestamp.start=true',
        '-Jjmeter.save.saveservice.response_data=false', '-Jjmeter.save.saveservice.samplerData=false', '-Jjmeter.save.saveservice.responseHeaders=false', '-Jjmeter.save.saveservice.requestHeaders=false',
        '-Jjmeter.save.saveservice.assertion_results_failure_message=true', '-Jjmeter.save.saveservice.connect_time=true', '-Jjmeter.save.saveservice.latency=true', '-Jjmeter.save.saveservice.thread_counts=true',
        '-Jsummariser.name=summary', '-Jsummariser.interval=5')
    if ($Scenario -eq 'following') { $createArguments += @("-Jowner_id=$($fixture.ownerId)", "-Jexpected_user_ids=$($fixture.expectedUserIds -join ',')") }
    $script:creationAttempted = $true
    $script:metadata.loadGenerator.creationAttemptedAt = [DateTime]::UtcNow.ToString('o')
    Save-Metadata
    $createdId = Invoke-CheckedDocker -Arguments $createArguments
    if ($createdId -notmatch '^[a-f0-9]{64}$') { throw 'Docker did not return a full newly-created container ID.' }
    $script:containerId = $createdId
    $script:metadata.loadGenerator.containerId = $createdId
    $script:metadata.loadGenerator.createdAt = [DateTime]::UtcNow.ToString('o')
    Save-Metadata
    $null = Get-OwnedContainer
    $null = Invoke-CheckedDocker -Arguments @('start', $createdId)
    $script:metadata.loadGenerator.startedAt = [DateTime]::UtcNow.ToString('o')
    $script:metadata.status = 'running'
    Save-Metadata
    Write-Host "JMeter started: scenario=$Scenario, threads=$Threads, ramp=$RampSeconds s, warmup=$WarmupSeconds s, duration=$DurationSeconds s; target=$TargetHost`:$Port."
    Write-Host "Owned container: $script:containerName; raw results: $script:runPath"
    $deadline = [DateTime]::UtcNow.AddSeconds($totalSeconds + 120)
    $nextMonitor = [DateTime]::UtcNow
    while ([DateTime]::UtcNow -lt $deadline) {
        $owned = Get-OwnedContainer
        if ($null -eq $owned) { throw 'Owned JMeter container disappeared during the run.' }
        if ($owned.oomKilled) { throw 'Owned JMeter container was OOM-killed.' }
        if (-not $owned.running) {
            $script:metadata.loadGenerator.finalState = $owned
            if ($owned.exitCode -ne 0) { throw "JMeter exited with code $($owned.exitCode)." }
            $loadFinished = $true
            break
        }
        if ([DateTime]::UtcNow -ge $nextMonitor) {
            $nextMonitor = [DateTime]::UtcNow.AddSeconds(5)
            $null = Add-MonitorSnapshot -Baseline $baseline -IncludeLoadGenerator
            $script:metadata.monitoring.samples = $script:monitorIndex
            Save-Metadata
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $loadFinished) { throw 'JMeter exceeded scheduler duration plus 120 seconds of startup/shutdown allowance.' }
    $null = Add-MonitorSnapshot -Baseline $baseline
    $samplesFile = Join-Path $script:runPath 'samples.jtl'
    if (-not (Test-Path -LiteralPath $samplesFile -PathType Leaf) -or (Get-Item -LiteralPath $samplesFile).Length -eq 0) { throw 'JMeter produced no raw CSV samples file.' }
    $firstLines = @(Get-Content -LiteralPath $samplesFile -TotalCount 2)
    if ($firstLines.Count -lt 2 -or $firstLines[0] -notmatch '(^|,)timeStamp(,|$)' -or $firstLines[0] -notmatch '(^|,)elapsed(,|$)' -or $firstLines[0] -notmatch '(^|,)success(,|$)') { throw 'Raw JMeter CSV is missing required columns or samples.' }
    $script:metadata.rawCsv = @{ file = 'samples.jtl'; byteLength = (Get-Item -LiteralPath $samplesFile).Length; header = $firstLines[0]; everyPhaseRetained = $true; qualityAnalysis = 'pending separate steady-state analysis, including every HTTP/business assertion failure' }
    $script:metadata.status = 'completed-awaiting-sample-analysis'
} catch {
    $runFailure = $_.Exception.Message
    $script:metadata.failure = $runFailure
    $script:metadata.status = 'invalid'
    if ($script:creationAttempted) {
        try { Stop-OwnedContainer -Reason $runFailure } catch { $script:metadata.cleanup.errors += $_.Exception.Message }
    }
} finally {
    # Every cleanup action rechecks labels plus exact ID/name ownership.
    if ($script:creationAttempted) {
        try { Save-OwnedLogs } catch { $script:metadata.cleanup.errors += $_.Exception.Message }
        try {
            $owned = Get-OwnedContainer
            if ($null -ne $owned) { Write-Json -FilePath (Join-Path $script:runPath 'load-generator-final.json') -Value @(Get-ContainerState -Names @($owned.id)) }
        } catch { $script:metadata.cleanup.errors += $_.Exception.Message }
        try { Remove-OwnedContainer } catch { $script:metadata.cleanup.status = 'failed'; $script:metadata.cleanup.errors += $_.Exception.Message }
    } else { $script:metadata.cleanup.status = 'not-created' }
    if ($script:watchedNames.Count -gt 0) {
        try {
            $after = @(Get-ContainerState -Names $script:watchedNames)
            $script:metadata.targetAfter = $after
            Write-Json -FilePath (Join-Path $script:runPath 'target-after.json') -Value $after
            Write-Json -FilePath (Join-Path $script:runPath 'stats-after.json') -Value @(Get-Stats -Names $script:watchedNames)
            if ($baseline.Count) { Assert-TargetsUnchanged -Current $after -Baseline $baseline }
        } catch {
            if (-not $runFailure) { $runFailure = $_.Exception.Message; $script:metadata.failure = $runFailure }
            $script:metadata.status = 'invalid'
        }
    }
    if ($script:metadata.cleanup.errors.Count -gt 0) { $script:metadata.status = 'invalid' }
    $script:metadata.monitoring.samples = $script:monitorIndex
    $script:metadata.finishedAt = [DateTime]::UtcNow.ToString('o')
    Save-Metadata
}
Write-Host "JMeter runner finished: $($script:metadata.status); metadata=$script:metadataPath"
if ($script:metadata.status -eq 'invalid') { throw "JMeter run invalid: $runFailure; cleanup=$($script:metadata.cleanup.status). Inspect jmeter-metadata.json and retained logs." }
