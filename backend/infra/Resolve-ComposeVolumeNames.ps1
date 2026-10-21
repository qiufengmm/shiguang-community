function Resolve-XiaohashuComposeVolumeNames {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Inspections,
        [Parameter(Mandatory = $true)]
        [string]$ProjectName
    )

    $requirements = @(
        @{ setting = 'MYSQL_DATA_VOLUME_NAME'; service = 'mysql'; target = '/var/lib/mysql' },
        @{ setting = 'ZOOKEEPER_DATALOG_VOLUME_NAME'; service = 'zookeeper'; target = '/datalog' },
        @{ setting = 'ZOOKEEPER_LOGS_VOLUME_NAME'; service = 'zookeeper'; target = '/logs' },
        @{ setting = 'XXL_JOB_LOGS_VOLUME_NAME'; service = 'xxl-job-admin'; target = '/data/applogs' }
    )
    $resolved = [ordered]@{}

    foreach ($requirement in $requirements) {
        $volumeNames = @(
            foreach ($inspection in $Inspections) {
                $labels = $inspection.Config.Labels
                if ([string]$labels.'com.docker.compose.project' -cne $ProjectName -or
                    [string]$labels.'com.docker.compose.service' -cne $requirement.service) {
                    continue
                }
                foreach ($mount in $inspection.Mounts) {
                    if ([string]$mount.Type -ceq 'volume' -and
                        [string]$mount.Destination -ceq $requirement.target -and
                        -not [string]::IsNullOrWhiteSpace([string]$mount.Name)) {
                        [string]$mount.Name
                    }
                }
            }
        ) | Sort-Object -Unique
        $volumeNames = @($volumeNames)

        if ($volumeNames.Count -eq 0) {
            throw "ReuseExisting could not discover the existing named volume for Compose project '$ProjectName', service '$($requirement.service)', target '$($requirement.target)'. Use Fresh mode only with a new data root, or follow infra/RESTORE.md. No replacement volume will be created."
        }
        if ($volumeNames.Count -gt 1) {
            throw "ReuseExisting found ambiguous existing volumes for Compose project '$ProjectName', service '$($requirement.service)', target '$($requirement.target)': $($volumeNames -join ', '). Resolve the deployment manually or use an isolated restore. No replacement volume will be created."
        }
        $resolved[$requirement.setting] = [string]$volumeNames[0]
    }

    return $resolved
}
