$results = @{
    StartupFolders = @()
    ScheduledTasks = @()
}

$shell = New-Object -ComObject WScript.Shell

function Scan-Folder($folderPath, $locationLabel) {
    if (-not (Test-Path $folderPath)) { return }
    Get-ChildItem -Path $folderPath -File | ForEach-Object {
        $file = $_
        $name = $file.BaseName
        $ext = $file.Extension
        $enabled = $true
        
        if ($ext -eq ".disabled") {
            $enabled = $false
            $actualName = $file.Name.Substring(0, $file.Name.Length - 9)
            $name = [System.IO.Path]::GetFileNameWithoutExtension($actualName)
            $ext = [System.IO.Path]::GetExtension($actualName)
        }
        
        $target = $file.FullName
        if ($ext -eq ".lnk") {
            try {
                $shortcut = $shell.CreateShortcut($file.FullName)
                $target = $shortcut.TargetPath
                if ($shortcut.Arguments) {
                    $target = "$target $($shortcut.Arguments)"
                }
            } catch {
                $target = $file.FullName
            }
        }
        
        # Extract publisher if target path is a file
        $publisher = ""
        $cleanTarget = $target.Trim()
        if ($cleanTarget.StartsWith('"')) {
            $endQuote = $cleanTarget.IndexOf('"', 1)
            if ($endQuote -gt 0) {
                $cleanTarget = $cleanTarget.Substring(1, $endQuote - 1)
            }
        } else {
            $spaceIdx = $cleanTarget.IndexOf(' ')
            if ($spaceIdx -gt 0) {
                $cleanTarget = $cleanTarget.Substring(0, $spaceIdx)
            }
        }
        if (Test-Path $cleanTarget) {
            try {
                $publisher = (Get-Item $cleanTarget).VersionInfo.CompanyName
            } catch {}
        }
        
        $results.StartupFolders += [PSCustomObject]@{
            Name = $name
            Path = $target
            FilePath = $file.FullName
            Location = $locationLabel
            Enabled = $enabled
            Publisher = $publisher
        }
    }
}

# 1. Scan User and Common Startup folders
$userStartup = [System.IO.Path]::Combine($env:APPDATA, "Microsoft\Windows\Start Menu\Programs\Startup")
$commonStartup = [System.IO.Path]::Combine($env:PROGRAMDATA, "Microsoft\Windows\Start Menu\Programs\Startup")

Scan-Folder $userStartup "User Startup Folder"
Scan-Folder $commonStartup "Common Startup Folder"

# 2. Scan Scheduled Tasks
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
