$results = @{
    ScheduledTasks = @()
}

# Relevant trigger classes for auto-start at logon/boot
$startupTriggerClasses = @('MSFT_TaskLogonTrigger', 'MSFT_TaskBootTrigger')

function Split-TaskFullName {
    param([string]$fullName)
    if ([string]::IsNullOrWhiteSpace($fullName)) {
        return @{ TaskPath = '\'; TaskName = '' }
    }
    $full = $fullName.Trim()
    if (-not $full.StartsWith('\')) {
        return @{ TaskPath = '\'; TaskName = $full }
    }
    $last = $full.LastIndexOf('\')
    if ($last -le 0) {
        return @{ TaskPath = '\'; TaskName = $full.TrimStart('\') }
    }
    $path = $full.Substring(0, $last + 1)
    $name = $full.Substring($last + 1)
    return @{ TaskPath = $path; TaskName = $name }
}

function Test-SchtasksStartupScheduleType {
    param([string]$scheduleType)
    if ([string]::IsNullOrWhiteSpace($scheduleType)) { return $false }
    $st = $scheduleType.ToLowerInvariant()
    # English Task Scheduler CSV; best-effort when Get-ScheduledTask is unavailable
    return ($st -match 'log\s*on|at\s+logon|startup|system\s+start|boot')
}

function Get-PublisherFromAction {
    param([string]$actionStr)
    $publisher = ""
    $cleanExec = $actionStr.Trim()
    if ($cleanExec.StartsWith('"')) {
        $endQuote = $cleanExec.IndexOf('"', 1)
        if ($endQuote -gt 0) { $cleanExec = $cleanExec.Substring(1, $endQuote - 1) }
    } else {
        $spaceIdx = $cleanExec.IndexOf(' ')
        if ($spaceIdx -gt 0) { $cleanExec = $cleanExec.Substring(0, $spaceIdx) }
        $semi = $cleanExec.IndexOf(';')
        if ($semi -gt 0) { $cleanExec = $cleanExec.Substring(0, $semi).Trim() }
    }
    try { $cleanExec = [System.Environment]::ExpandEnvironmentVariables($cleanExec) } catch {}
    if ($cleanExec -and (Test-Path $cleanExec -ErrorAction SilentlyContinue)) {
        try { $publisher = (Get-Item $cleanExec -ErrorAction SilentlyContinue).VersionInfo.CompanyName } catch {}
    }
    return $publisher
}

function Add-StartupTaskFromScheduledTask {
    param($task, [hashtable]$resultsRef)

    $hasStartupTrigger = $false
    if ($task.Triggers) {
        foreach ($tr in $task.Triggers) {
            try {
                $cls = $tr.CimSystemProperties.ClassName
                if ($startupTriggerClasses -contains $cls) { $hasStartupTrigger = $true; break }
            } catch {}
        }
    }
    if (-not $hasStartupTrigger) { return }

    $isEnabled = $task.State -ne 'Disabled'
    try {
        if ($null -ne $task.Settings -and $null -ne $task.Settings.Enabled -and -not $task.Settings.Enabled) {
            $isEnabled = $false
        }
    } catch {}

    $triggerEnabled = $false
    foreach ($t in $task.Triggers) {
        try {
            $cls = $t.CimSystemProperties.ClassName
            if ($startupTriggerClasses -contains $cls) {
                $en = $true
                try { $en = $t.Enabled } catch {}
                if ($en) { $triggerEnabled = $true; break }
            }
        } catch {}
    }

    $actionStr = ""
    if ($task.Actions) {
        $actionsList = @()
        foreach ($action in $task.Actions) {
            $exec = $null
            try { $exec = $action.Execute } catch {}
            if ($exec) {
                $arg = $null
                try { $arg = $action.Arguments } catch {}
                if ($arg) { $exec = "$exec $arg" }
                $actionsList += $exec
            } else {
                try {
                    if ($action.ClassId) { $actionsList += "COM:$($action.ClassId)" }
                    elseif ($action.Id) { $actionsList += $action.Id }
                } catch {}
            }
        }
        $actionStr = $actionsList -join "; "
    }

    $publisher = Get-PublisherFromAction $actionStr

    $resultsRef.ScheduledTasks += [PSCustomObject]@{
        TaskName         = $task.TaskName
        TaskPath         = $task.TaskPath
        Enabled          = ($isEnabled -and $triggerEnabled)
        TaskEnabled      = $isEnabled
        TriggerEnabled   = $triggerEnabled
        Actions          = $actionStr
        Publisher        = $publisher
    }
}

function Add-StartupTaskFromSchtasksRow {
    param($row, [hashtable]$resultsRef)

    $scheduleType = $null
    try { $scheduleType = $row.'Schedule Type' } catch {}
    if (-not (Test-SchtasksStartupScheduleType $scheduleType)) { return }

    $fullName = $null
    try { $fullName = $row.'TaskName' } catch {}
    if ([string]::IsNullOrWhiteSpace($fullName)) { return }

    $split = Split-TaskFullName $fullName
    $taskPath = $split.TaskPath
    $taskName = $split.TaskName
    if ([string]::IsNullOrWhiteSpace($taskName)) { return }

    $actionStr = ""
    try { $actionStr = $row.'Task To Run' } catch {}
    if ($null -eq $actionStr) { $actionStr = "" }

    $status = ""
    try { $status = $row.'Status' } catch {}
    $schedState = ""
    try { $schedState = $row.'Scheduled Task State' } catch {}
    $isEnabled = $true
    if ($status -match 'Disabled' -or $schedState -match 'Disabled') { $isEnabled = $false }

    $publisher = Get-PublisherFromAction $actionStr

    $resultsRef.ScheduledTasks += [PSCustomObject]@{
        TaskName         = $taskName
        TaskPath         = $taskPath
        Enabled          = $isEnabled
        TaskEnabled      = $isEnabled
        TriggerEnabled   = $isEnabled
        Actions          = $actionStr
        Publisher        = $publisher
    }
}

function Import-StartupTasksViaSchtasks {
    param([hashtable]$resultsRef, [string]$originalError)

    $seen = @{}
    try {
        $csv = schtasks /query /fo CSV /v 2>$null | ConvertFrom-Csv -ErrorAction SilentlyContinue
    } catch {
        $csv = $null
    }
    if (-not $csv) { return }

    foreach ($row in $csv) {
        $fullName = $null
        try { $fullName = $row.'TaskName' } catch {}
        if ([string]::IsNullOrWhiteSpace($fullName)) { continue }
        if ($seen.ContainsKey($fullName)) { continue }
        $seen[$fullName] = $true

        $split = Split-TaskFullName $fullName
        $hydrated = $false
        try {
            $task = Get-ScheduledTask -TaskName $split.TaskName -TaskPath $split.TaskPath -ErrorAction Stop
            Add-StartupTaskFromScheduledTask $task $resultsRef
            $hydrated = $true
        } catch {}

        if (-not $hydrated) {
            Add-StartupTaskFromSchtasksRow $row $resultsRef
        }
    }

    if ($resultsRef.ScheduledTasks.Count -gt 0) {
        $resultsRef.SchtasksFallback = $true
        $resultsRef.Warning = "Get-ScheduledTask failed; listed via schtasks/per-task hydration (original: $originalError)"
    }
}

# Scan Scheduled Tasks – filter to tasks that have at least one startup-relevant trigger
try {
    $allTasks = Get-ScheduledTask -ErrorAction Stop
    foreach ($task in $allTasks) {
        Add-StartupTaskFromScheduledTask $task $results
    }
} catch {
    $scanError = $_.Exception.Message
    $results.Error = $scanError
    Import-StartupTasksViaSchtasks $results $scanError
}

$results | ConvertTo-Json -Depth 3
