[CmdletBinding()]
param(
    [string]$AdminUrl = 'http://127.0.0.1:7777/xxl-job-admin',
    [ValidateRange(1, [int]::MaxValue)]
    [int]$JobGroup = 1,
    [string]$Username = 'admin',
    [string]$Password = $env:XXL_JOB_ADMIN_PASSWORD,
    [string]$Cron = '0 0 3 * * ?',
    [switch]$RunOnce,
    [switch]$EnableMarkerCleanup
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Password)) {
    throw 'Set XXL_JOB_ADMIN_PASSWORD or supply -Password for the local XXL-JOB admin account.'
}
$AdminUrl = $AdminUrl.TrimEnd('/')
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()

function Invoke-AdminForm([string]$Path, [hashtable]$Form) {
    Invoke-RestMethod -Uri "$AdminUrl/$Path" -Method Post -WebSession $session `
        -ContentType 'application/x-www-form-urlencoded' -Body $Form -TimeoutSec 30
}

function Assert-AdminSuccess($Result, [string]$Action) {
    if ($null -eq $Result -or $Result.code -ne 200) {
        throw "$Action failed: $($Result.msg)"
    }
}

function Find-AuditJob([string]$Handler) {
    $page = Invoke-AdminForm 'jobinfo/pageList' @{
        jobGroup = $JobGroup; triggerStatus = -1; jobDesc = ''; executorHandler = $Handler
        author = ''; start = 0; length = 100
    }
    if ($null -eq $page.data) { throw 'XXL-JOB task query did not return a task list.' }
    if ($page.recordsFiltered -gt 100) { throw "Too many matching tasks for $Handler; inspect duplicates manually." }
    $matching = @($page.data | Where-Object { $_.executorHandler -eq $Handler })
    if ($matching.Count -gt 1) { throw "Duplicate XXL-JOB tasks exist for $Handler; no task was changed." }
    if ($matching.Count -eq 1) { return $matching[0] }
    return $null
}

$login = Invoke-AdminForm 'login' @{ userName = $Username; password = $Password }
Assert-AdminSuccess $login 'XXL-JOB login'

# These handlers compare source records with stored counters and repair only
# marked mismatches while holding the counter row lock. Marker cleanup remains
# opt-in; its handler performs a final reconciliation before dropping an old table.
$definitions = @(
    @{ handler = 'followingCountShardingJobHandler'; description = 'Reconcile following count' },
    @{ handler = 'noteLikeCountShardingJobHandler'; description = 'Reconcile note like count' },
    @{ handler = 'fansCountShardingJobHandler'; description = 'Reconcile fans count' },
    @{ handler = 'noteCollectCountShardingJobHandler'; description = 'Reconcile note collect count' },
    @{ handler = 'userCollectCountShardingJobHandler'; description = 'Reconcile user collect count' },
    @{ handler = 'userLikeCountShardingJobHandler'; description = 'Reconcile user like count' },
    @{ handler = 'notePublishCountShardingJobHandler'; description = 'Reconcile note publish count' }
)

foreach ($definition in $definitions) {
    $job = Find-AuditJob $definition.handler
    if ($null -eq $job) {
        $created = Invoke-AdminForm 'jobinfo/add' @{
            jobGroup = $JobGroup; jobDesc = $definition.description; author = 'xiaohashu'
            alarmEmail = ''; scheduleType = 'CRON'; scheduleConf = $Cron
            misfireStrategy = 'DO_NOTHING'; executorRouteStrategy = 'FIRST'
            executorHandler = $definition.handler; executorParam = ''
            executorBlockStrategy = 'SERIAL_EXECUTION'; executorTimeout = 0
            executorFailRetryCount = 0; glueType = 'BEAN'; glueSource = ''
            glueRemark = ''; childJobId = ''
        }
        Assert-AdminSuccess $created "Create $($definition.handler)"
        $job = Find-AuditJob $definition.handler
        if ($null -eq $job) { throw "Created task could not be found: $($definition.handler)" }
    }
    if ($job.triggerStatus -ne 1) {
        $started = Invoke-AdminForm 'jobinfo/start' @{ id = $job.id }
        Assert-AdminSuccess $started "Start $($definition.handler)"
    }
    if ($RunOnce) {
        $triggered = Invoke-AdminForm 'jobinfo/trigger' @{
            id = $job.id; executorParam = ''; addressList = ''
        }
        Assert-AdminSuccess $triggered "Trigger $($definition.handler)"
    }
    $current = Find-AuditJob $definition.handler
    if ($null -eq $current -or $current.triggerStatus -ne 1) {
        throw "Task did not become enabled: $($definition.handler)"
    }
    Write-Host "$($definition.handler): id=$($current.id), enabled, schedule=$($current.scheduleConf)"
}

if ($EnableMarkerCleanup) {
    $cleanupJob = Find-AuditJob 'deleteTableJobHandler'
    if ($null -eq $cleanupJob) {
        throw 'The existing deleteTableJobHandler task was not found; no cleanup task was created.'
    }
    if ($cleanupJob.triggerStatus -ne 1) {
        $started = Invoke-AdminForm 'jobinfo/start' @{ id = $cleanupJob.id }
        Assert-AdminSuccess $started 'Start deleteTableJobHandler'
    }
    $currentCleanup = Find-AuditJob 'deleteTableJobHandler'
    if ($null -eq $currentCleanup -or $currentCleanup.triggerStatus -ne 1) {
        throw 'deleteTableJobHandler did not become enabled.'
    }
    Write-Host "deleteTableJobHandler: id=$($currentCleanup.id), enabled, schedule=$($currentCleanup.scheduleConf)"
}
