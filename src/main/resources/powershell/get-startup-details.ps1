$results = @{
    ScheduledTasks = @()
}

# Relevant trigger classes for auto-start at logon/boot
$startupTriggerClasses = @('MSFT_TaskLogonTrigger','MSFT_TaskBootTrigger','MSFT_TaskRegistrationTrigger')

# Scan Scheduled Tasks – filter to tasks that have at least one startup-relevant trigger
$scanError = $null
try {
    $allTasks = Get-ScheduledTask -ErrorAction Stop
} catch {
    $allTasks = @()
    $scanError = $_.Exception.Message
    $results.Error = $scanError
    # Fallback: try enumerating via schtasks.exe CSV as non-privileged fallback
    try {
        $csv = schtasks /query /fo CSV /v 2>$null | ConvertFrom-Csv -ErrorAction SilentlyContinue
        if ($csv) {
            # schtasks fallback cannot reliably determine trigger type, so return empty but with Error
            $results.SchtasksFallback = $true
        }
    } catch {}
}

foreach ($task in $allTasks) {
    $hasStartupTrigger = $false
    if ($task.Triggers) {
        foreach ($tr in $task.Triggers) {
            try {
                $cls = $tr.CimSystemProperties.ClassName
                if ($startupTriggerClasses -contains $cls) { $hasStartupTrigger = $true; break }
            } catch {}
        }
    }
    if (-not $hasStartupTrigger) { continue }

    # Task enabled if State not Disabled and Settings allow
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
                # Trigger Enabled defaults to true if property missing
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
                # Handle ComHandler or other action types
                try {
                    if ($action.ClassId) { $actionsList += "COM:$($action.ClassId)" }
                    elseif ($action.Id) { $actionsList += $action.Id }
                } catch {}
            }
        }
        $actionStr = $actionsList -join "; "
    }

    # Get publisher – expand env vars first
    $publisher = ""
    $cleanExec = $actionStr.Trim()
    if ($cleanExec.StartsWith('"')) {
        $endQuote = $cleanExec.IndexOf('"', 1)
        if ($endQuote -gt 0) { $cleanExec = $cleanExec.Substring(1, $endQuote - 1) }
    } else {
        $spaceIdx = $cleanExec.IndexOf(' ')
        if ($spaceIdx -gt 0) { $cleanExec = $cleanExec.Substring(0, $spaceIdx) }
        # Strip trailing ; if multiple actions
        $semi = $cleanExec.IndexOf(';')
        if ($semi -gt 0) { $cleanExec = $cleanExec.Substring(0, $semi).Trim() }
    }
    try { $cleanExec = [System.Environment]::ExpandEnvironmentVariables($cleanExec) } catch {}
    if ($cleanExec -and (Test-Path $cleanExec -ErrorAction SilentlyContinue)) {
        try { $publisher = (Get-Item $cleanExec -ErrorAction SilentlyContinue).VersionInfo.CompanyName } catch {}
    }

    $results.ScheduledTasks += [PSCustomObject]@{
        TaskName  = $task.TaskName
        TaskPath  = $task.TaskPath
        Enabled   = ($isEnabled -and $triggerEnabled)
        Actions   = $actionStr
        Publisher = $publisher
    }
}

$results | ConvertTo-Json -Depth 3
