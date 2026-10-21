[CmdletBinding()]
param(
    [ValidateRange(1, 1000)]
    [int]$NoteCount = 100,

    [ValidateRange(1, 100)]
    [int]$CommentsPerNote = 10,

    [string]$RunId = ((Get-Date -Format 'yyyyMMdd') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)),

    [int]$DelayMilliseconds = 40
)

$ErrorActionPreference = 'Stop'

$frontendContainer = 'xiaohashu-frontend-1'
$mysqlContainer = 'xiaohashu-mysql-1'
$runtimeRoot = Join-Path $PSScriptRoot '.runtime'
$titlePrefix = "LOCALDEMO-$RunId-"
$manifestPath = Join-Path $runtimeRoot "demo-content-$RunId.json"

if ($RunId -notmatch '^[A-Za-z0-9-]{1,32}$') {
    throw 'RunId may contain only ASCII letters, digits, and dashes (1-32 characters).'
}
if ($DelayMilliseconds -lt 0 -or $DelayMilliseconds -gt 5000) {
    throw 'DelayMilliseconds must be between 0 and 5000.'
}
if (Test-Path -LiteralPath $manifestPath) {
    throw "Manifest already exists: $manifestPath. Use a new RunId to avoid duplicate content."
}

foreach ($container in @($frontendContainer, $mysqlContainer)) {
    $health = & docker 'inspect' '--format' '{{.State.Health.Status}}' $container 2>$null
    if ($LASTEXITCODE -ne 0 -or $health -ne 'healthy') {
        throw "Required container '$container' is not healthy."
    }
}

New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null

$titleTemplates = @(
    '雨后散步时发现的小巷咖啡店', '周末给自己做了一顿简单早餐', '城市边缘的日落比想象中温柔',
    '把旧相机带去公园的一天', '最近常去的一家安静书店', '下班路上遇见一片晚霞',
    '试着用十分钟整理了房间', '春天适合慢慢走过每条街', '今天的晚餐颜色很丰富',
    '窗台上的小植物又长出新叶', '第一次挑战手冲咖啡', '短途旅行里的意外惊喜',
    '给普通工作日加一点仪式感', '周末骑车绕湖一圈', '换季后重新整理衣柜',
    '最近读到的一段温柔文字', '在家也能完成的轻松运动', '小店里的招牌甜品值得一试',
    '把手机放下以后看到的风景', '记录一场没有计划的散步'
)
$bodyTemplates = @(
    '没有特别安排的一天，反而遇到不少喜欢的小细节。把照片和心情记下来，之后翻到时应该还会觉得很放松。',
    '最近在练习让生活慢一点。认真吃饭、出门走走，再给自己留一点空白，状态比前阵子好了很多。',
    '路线是临时决定的，沿途有树影、风和几家没见过的小店。下次还想带朋友一起再来一次。',
    '这是一次轻量尝试，过程比结果更有意思。记下几个小经验，下一次可以做得更顺手。',
    '分享给也喜欢日常记录的朋友。没有复杂攻略，照着自己的节奏来就很好。'
)
$commentTemplates = @(
    '这个记录很有生活感，收藏起来慢慢看。', '照片里的光线好舒服，周末也想去走走。',
    '谢谢分享，正好给我一些新的灵感。', '这种不赶时间的安排最适合放松了。',
    '看完心情都变好了，期待下一篇。', '地点和氛围都很喜欢，已经记到清单里。',
    '细节写得很真实，像跟着一起体验了一遍。', '简单但很实用，准备找时间试试看。',
    '这个角度拍得真好，感觉特别宁静。', '喜欢这种轻松的日常分享。',
    '下次可以再多分享一点路线信息吗？', '读起来很治愈，给今天加了一点好心情。',
    '同感，平常的小事认真记录也很珍贵。', '色彩搭配很舒服，看起来很有食欲。',
    '周末安排有着落了，谢谢推荐。', '这个想法不错，我也准备试一下。',
    '很适合和朋友一起去，已经发给同伴了。', '文字和图片都很自然，支持继续更新。',
    '好喜欢这种随手记录的感觉。', '看起来轻松又不费力，正是我需要的。'
)

function Invoke-InternalApi {
    param(
        [Parameter(Mandatory)] [string]$Service,
        [Parameter(Mandatory)] [int]$Port,
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [long]$UserId,
        [Parameter(Mandatory)] [object]$Body
    )

    # Keep native docker.exe arguments ASCII-only; JSON still decodes to the original Chinese text in the service.
    $json = ConvertTo-Json -InputObject $Body -Depth 20 -Compress -EscapeHandling EscapeNonAscii
    $url = "http://${Service}:$Port$Path"
    $responseText = & docker 'exec' $frontendContainer 'curl' '-sS' `
        '-H' 'Content-Type: application/json' `
        '-H' "userId: $UserId" `
        '--data-binary' $json $url
    if ($LASTEXITCODE -ne 0) {
        throw "HTTP request failed for $Path (docker exit $LASTEXITCODE)."
    }

    $response = ($responseText -join "`n") | ConvertFrom-Json
    if ($response.success -ne $true) {
        throw "Application rejected $Path with error '$($response.errorCode)': $($response.message)"
    }
    return $response
}

function Invoke-MySqlQuery {
    param([Parameter(Mandatory)] [string]$Sql)

    if ($Sql.Contains('"')) {
        throw 'SQL query contains an unsupported double quote.'
    }
    $mysqlCommand = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names xiaohashu -e "' + $Sql + '"'
    $rows = & docker 'exec' $mysqlContainer 'sh' '-c' $mysqlCommand
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not read generated IDs from the local MySQL container.'
    }
    return ,@($rows | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
}

function Save-Manifest {
    param([Parameter(Mandatory)] [object]$Value)
    $json = ConvertTo-Json -InputObject $Value -Depth 12
    Set-Content -LiteralPath $manifestPath -Value $json -Encoding UTF8
}

$existingCount = Invoke-MySqlQuery -Sql "SELECT COUNT(*) FROM t_note WHERE title LIKE '$titlePrefix%';"
if ([int]$existingCount[0] -ne 0) {
    throw "The database already contains notes for RunId '$RunId'. Choose a new RunId."
}

$manifest = [ordered]@{
    schemaVersion = 1
    runId = $RunId
    status = 'running'
    startedAt = (Get-Date).ToString('o')
    expectedNoteCount = $NoteCount
    commentsPerNote = $CommentsPerNote
    imageUrl = 'https://placehold.co/640x800/png?text=xiaohashu-demo'
    notes = @()
}
Save-Manifest -Value $manifest

for ($noteNumber = 1; $noteNumber -le $NoteCount; $noteNumber++) {
    $sequence = $noteNumber.ToString('D3')
    $title = $titlePrefix + $sequence + ' ' + $titleTemplates[($noteNumber - 1) % $titleTemplates.Count]
    $creatorId = if ($noteNumber % 2 -eq 0) { 100L } else { 3L }
    $noteMarker = "LOCALDEMO-$RunId-N$sequence"
    $body = "$noteMarker`n$($bodyTemplates[($noteNumber - 1) % $bodyTemplates.Count])"

    $noteManifest = [ordered]@{
        sequence = $noteNumber
        id = $null
        title = $title
        creatorId = $creatorId
        commentIds = @()
        commentAttempts = @()
    }
    $manifest.notes += $noteManifest
    Save-Manifest -Value $manifest

    try {
        [void](Invoke-InternalApi -Service 'note' -Port 8086 -Path '/note/publish' -UserId $creatorId -Body @{
            type = 0
            title = $title
            content = $body
            imgUris = @($manifest.imageUrl)
        })
    } catch {
        $noteManifest.publishStatus = 'request-failed-or-unknown'
        Save-Manifest -Value $manifest
        throw
    }
    $noteManifest.publishStatus = 'accepted'
    Save-Manifest -Value $manifest

    $noteId = $null
    for ($attempt = 0; $attempt -lt 30 -and -not $noteId; $attempt++) {
        $noteRows = Invoke-MySqlQuery -Sql "SELECT id FROM t_note WHERE title LIKE '$titlePrefix$sequence %' AND creator_id=$creatorId ORDER BY id DESC LIMIT 1;"
        if ($noteRows.Count -gt 0) { $noteId = [long]$noteRows[0] }
        elseif ($attempt -lt 29) { Start-Sleep -Milliseconds 250 }
    }
    if (-not $noteId) { throw "Published note ID was not found for $noteMarker." }
    $noteManifest.id = $noteId
    $noteManifest.publishStatus = 'persisted'
    Save-Manifest -Value $manifest

    for ($commentNumber = 1; $commentNumber -le $CommentsPerNote; $commentNumber++) {
        $commentSequence = $commentNumber.ToString('D2')
        $commentUserId = if (($noteNumber + $commentNumber) % 2 -eq 0) { 3L } else { 100L }
        $commentText = "LOCALDEMO-$RunId-N$sequence-C$commentSequence $($commentTemplates[($noteNumber + $commentNumber - 2) % $commentTemplates.Count])"
        $commentAttempt = [ordered]@{
            sequence = $commentNumber
            marker = "LOCALDEMO-$RunId-N$sequence-C$commentSequence"
            userId = $commentUserId
            id = $null
            persistedIds = @()
            status = 'publishing'
        }
        $noteManifest.commentAttempts += $commentAttempt
        Save-Manifest -Value $manifest

        try {
            [void](Invoke-InternalApi -Service 'comment' -Port 8093 -Path '/comment/publish' -UserId $commentUserId -Body @{
                noteId = $noteId
                content = $commentText
            })
            $commentAttempt.status = 'accepted'
        } catch {
            $commentAttempt.status = 'request-failed-or-unknown'
            Save-Manifest -Value $manifest
            throw
        }
        Save-Manifest -Value $manifest
        if ($DelayMilliseconds -gt 0) { Start-Sleep -Milliseconds $DelayMilliseconds }

        $commentRows = @()
        for ($attempt = 0; $attempt -lt 120; $attempt++) {
            $commentRows = Invoke-MySqlQuery -Sql "SELECT id,user_id FROM t_comment WHERE note_id=$noteId ORDER BY id;"
            if ($commentRows.Count -ge $commentNumber) { break }
            Start-Sleep -Milliseconds 250
        }
        if ($commentRows.Count -lt $commentNumber) {
            throw "Note $noteId has $($commentRows.Count) persisted comments after comment $commentNumber; manifest is retained for recovery."
        }

        $knownCommentIds = @($noteManifest.commentIds | ForEach-Object { [long]$_.id })
        $persistedNewIds = @()
        foreach ($row in $commentRows) {
            $parts = ([string]$row).Split("`t")
            $commentId = [long]$parts[0]
            if ($knownCommentIds -notcontains $commentId) {
                $noteManifest.commentIds += [ordered]@{ id = $commentId; userId = [long]$parts[1] }
                $knownCommentIds += $commentId
                $persistedNewIds += $commentId
            }
        }
        $commentAttempt.persistedIds = $persistedNewIds
        if ($persistedNewIds.Count -eq 1) { $commentAttempt.id = $persistedNewIds[0] }
        $commentAttempt.status = 'persisted'
        Save-Manifest -Value $manifest
    }

    if ($commentRows.Count -ne $CommentsPerNote -or $noteManifest.commentIds.Count -ne $CommentsPerNote) {
        throw "Note $noteId has $($commentRows.Count) rows and $($noteManifest.commentIds.Count) recorded IDs; expected $CommentsPerNote. Manifest is retained for recovery."
    }
    Write-Progress -Activity 'Seeding local Xiaohashu demo data' `
        -Status "Notes $noteNumber/$NoteCount; comments $($noteNumber * $CommentsPerNote)/$($NoteCount * $CommentsPerNote)" `
        -PercentComplete ([int](100 * $noteNumber / $NoteCount))
}

$manifest.status = 'complete'
$manifest.completedAt = (Get-Date).ToString('o')
Save-Manifest -Value $manifest
Write-Progress -Activity 'Seeding local Xiaohashu demo data' -Completed
Write-Host "Created $NoteCount notes and $($NoteCount * $CommentsPerNote) comments."
Write-Host "RunId: $RunId"
Write-Host "Manifest: $manifestPath"
