$results = @{
    ScheduledTasks = @()
}

# Scan Scheduled Tasks
Get-ScheduledTask | Where-Object { ($_.Triggers | Where-Object { $_.CimSystemProperties.ClassName -eq 'MSFT_TaskLogonTrigger' -or $_.CimSystemProperties.ClassName -eq 'MSFT_TaskBootTrigger' }) } | ForEach-Object {
    $task = $_
    $isEnabled = $task.State -ne 'Disabled'
    
    $triggerEnabled = $false
    foreach ($t in $task.Triggers) {
        if ($t.CimSystemProperties.ClassName -eq 'MSFT_TaskLogonTrigger' -or $t.CimSystemProperties.ClassName -eq 'MSFT_TaskBootTrigger') {
            if ($t.Enabled) {
                $triggerEnabled = $true
            }
        }
    }
    
    $actionStr = ""
    if ($task.Actions) {
        $actionsList = @()
        foreach ($action in $task.Actions) {
            if ($action.Execute) {
                $exec = $action.Execute
                if ($action.Arguments) {
                    $exec = "$exec $($action.Arguments)"
                }
                $actionsList += $exec
            }
        }
        $actionStr = $actionsList -join "; "
    }
    
    # Get publisher for Scheduled Task action executable
    $publisher = ""
    $cleanExec = $actionStr.Trim()
    if ($cleanExec.StartsWith('"')) {
        $endQuote = $cleanExec.IndexOf('"', 1)
        if ($endQuote -gt 0) {
            $cleanExec = $cleanExec.Substring(1, $endQuote - 1)
        }
    } else {
        $spaceIdx = $cleanExec.IndexOf(' ')
        if ($spaceIdx -gt 0) {
            $cleanExec = $cleanExec.Substring(0, $spaceIdx)
        }
    }
    if (Test-Path $cleanExec) {
        try {
            $publisher = (Get-Item $cleanExec).VersionInfo.CompanyName
        } catch {}
    }
    
    $results.ScheduledTasks += [PSCustomObject]@{
        TaskName = $task.TaskName
        TaskPath = $task.TaskPath
        Enabled  = ($isEnabled -and $triggerEnabled)
        Actions  = $actionStr
        Publisher = $publisher
    }
}

$results | ConvertTo-Json
