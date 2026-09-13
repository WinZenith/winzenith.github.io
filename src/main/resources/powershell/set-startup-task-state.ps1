param(
    [Parameter(Mandatory = $true)][string]$TaskName,
    [Parameter(Mandatory = $true)][string]$TaskPath,
    [Parameter(Mandatory = $true)][ValidateSet('Enable', 'Disable', 'TestExists')][string]$Action
)

$ErrorActionPreference = 'Stop'
$startupTriggerClasses = @('MSFT_TaskLogonTrigger', 'MSFT_TaskBootTrigger')

function Write-ExistsJson($exists) {
    @{ Exists = [bool]$exists } | ConvertTo-Json -Compress
}

if ($Action -eq 'TestExists') {
    try {
        $null = Get-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath -ErrorAction Stop
        Write-ExistsJson $true
        exit 0
    } catch {
        Write-ExistsJson $false
        exit 0
    }
}

if ($Action -eq 'Disable') {
    try {
        Disable-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath -ErrorAction Stop | Out-Null
        exit 0
    } catch {
        Write-Error $_.Exception.Message
        exit 1
    }
}

# Enable: re-enable Boot/Logon triggers first, then enable the task (never enable on trigger failure).
try {
    $t = Get-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath -ErrorAction Stop
    $changed = $false
    if ($t.Triggers) {
        foreach ($tr in $t.Triggers) {
            try {
                $cls = $tr.CimSystemProperties.ClassName
                if ($startupTriggerClasses -contains $cls) {
                    if (-not $tr.Enabled) {
                        $tr.Enabled = $true
                        $changed = $true
                    }
                }
            } catch {}
        }
    }
    if ($changed) {
        $t | Set-ScheduledTask -ErrorAction Stop | Out-Null
    }
    Enable-ScheduledTask -TaskName $TaskName -TaskPath $TaskPath -ErrorAction Stop | Out-Null
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
