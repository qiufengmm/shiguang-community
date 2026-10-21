[CmdletBinding()]
param(
    [switch]$SkipMaven
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jdkHome = 'D:\java\jdk-17.0.18+8'
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH

$serviceModules = [ordered]@{
    'auth' = 'xiaohashu-auth'
    'gateway' = 'xiaohashu-gateway'
    'oss' = 'xiaohashu-oss/xiaohashu-oss-biz'
    'user' = 'xiaohashu-user/xiaohashu-user-biz'
    'kv' = 'xiaohashu-kv/xiaohashu-kv-biz'
    'distributed-id-generator' = 'xiaohashu-distributed-id-generator/xiaohashu-distributed-id-generator-biz'
    'note' = 'xiaohashu-note/xiaohashu-note-biz'
    'user-relation' = 'xiaohashu-user-relation/xiaohashu-user-relation-biz'
    'count' = 'xiaohashu-count/xiaohashu-count-biz'
    'data-align' = 'xiaohashu-data-align'
    'search' = 'xiaohashu-search/xiaohashu-search-biz'
    'comment' = 'xiaohashu-comment/xiaohashu-comment-biz'
}

Push-Location $projectRoot
try {
    if (-not $SkipMaven) {
        if (-not (Test-Path -LiteralPath (Join-Path $jdkHome 'bin/java.exe'))) {
            throw "JDK 17 was not found at $jdkHome."
        }
        $env:JAVA_HOME = $jdkHome
        $env:PATH = (Join-Path $jdkHome 'bin') + [IO.Path]::PathSeparator + $previousPath
        & mvn '-DskipTests' 'package'
        if ($LASTEXITCODE -ne 0) {
            throw "Maven package failed with exit code $LASTEXITCODE."
        }
    }

    foreach ($service in $serviceModules.Keys) {
        $module = $serviceModules[$service]
        $targetPath = Join-Path $projectRoot "$module/target"
        # Maven metadata identifies the main artifact even when old verification
        # or classifier JARs remain in target (this script never runs clean).
        $artifactMetadata = Join-Path $targetPath 'maven-archiver/pom.properties'
        $artifactProperties = ConvertFrom-StringData (Get-Content -LiteralPath $artifactMetadata -Raw)
        $jarName = "$($artifactProperties.artifactId)-$($artifactProperties.version).jar"
        if (-not (Test-Path -LiteralPath (Join-Path $targetPath $jarName) -PathType Leaf)) {
            throw "Executable JAR was not found: $targetPath/$jarName."
        }

        $jarFile = "$module/target/$jarName"
        $imageTag = "xiaohashu/${service}:local"
        Write-Host "Building $imageTag from $jarFile"
        & docker 'build' '-f' 'infra/docker/Dockerfile' '--build-arg' "JAR_FILE=$jarFile" '-t' $imageTag '.'
        if ($LASTEXITCODE -ne 0) {
            throw "Docker build failed for $imageTag with exit code $LASTEXITCODE."
        }
    }

    Write-Host 'Built all 12 Xiaohashu service images.'
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    Pop-Location
}
