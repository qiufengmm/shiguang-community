[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^1[3-9][0-9]{9}$')]
    [string]$Phone
)

$ErrorActionPreference = 'Stop'
$environmentFile = Join-Path $PSScriptRoot '.runtime/compose.env'
if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) {
    throw 'Local deployment settings are missing. Run infra/Start-Local.ps1 first.'
}

$composeArgs = @(
    'compose', '--env-file', $environmentFile,
    '-f', (Join-Path $PSScriptRoot 'compose.yaml'),
    '-f', (Join-Path $PSScriptRoot 'compose.app.yaml')
)

# Rejection sampling keeps all six-digit codes equally likely, including leading zeroes.
$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
$randomBytes = New-Object byte[] 4
try {
    do {
        $generator.GetBytes($randomBytes)
        $randomValue = [BitConverter]::ToUInt32($randomBytes, 0)
    } while ($randomValue -ge 4294000000)
    $code = ([int]($randomValue % 1000000)).ToString('D6')
}
finally {
    $generator.Dispose()
}

$redisKey = "verification_code:$Phone"
# Auth uses Jackson2JsonRedisSerializer<Object>, so the Redis value must be a JSON string.
$jsonCode = ConvertTo-Json -InputObject ([string]$code) -Compress
$redisCommand = 'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli -n 0 -x SETEX "$1" 180'
$result = $jsonCode | & docker @composeArgs 'exec' '-T' 'redis' 'sh' '-c' $redisCommand 'sh' $redisKey 2>&1
if ($LASTEXITCODE -ne 0 -or (($result -join "`n").Trim() -ne 'OK')) {
    throw 'Could not store the local login code. Check that the Compose Redis service is running.'
}

Write-Host 'Open http://localhost:8088 and enter this phone and code in the login dialog.'
Write-Host 'Do not click the send-code button. This local Redis code expires in 180 seconds; no SMS is sent.'
[PSCustomObject]@{
    Phone = $Phone
    Code = $code
    ExpiresInSeconds = 180
}
