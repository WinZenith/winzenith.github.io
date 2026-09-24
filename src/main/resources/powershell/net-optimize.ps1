param([string]$Preset = "Default")

$results = @()
$failed = $false
$tcpipParams = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters"
$tcpipIfRoot = "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces"

function Add-Result($key, $value, $ok) {
    $script:results += [PSCustomObject]@{ Key = $key; Value = $value; Success = $ok }
    if (-not $ok) { $script:failed = $true }
}

function Get-FirstOutputLine {
    param([string]$Text, [int]$Code)
    $line = ""
    if (-not [string]::IsNullOrWhiteSpace($Text)) {
        foreach ($row in ($Text -split "`r?`n")) {
            if (-not [string]::IsNullOrWhiteSpace($row)) { $line = $row.Trim(); break }
        }
    }
    if ([string]::IsNullOrWhiteSpace($line)) { $line = "exit $Code" }
    if ($line.Length -gt 120) { $line = $line.Substring(0, 120) }
    return $line
}

function Invoke-Netsh {
    # $Args is automatic and stays empty, so a parameter by that name drops the
    # netsh tokens and bare `netsh` exits 0 (false success, no TCP change).
    # Start-Process -PassThru leaves ExitCode null, so a real success looks like a failure.
    # One hung netsh must not consume the whole apply; the script still emits JSON.
    param([string[]]$NetshArgs)
    $proc = $null
    $outTask = $null
    $errTask = $null
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = "netsh.exe"
        # Tokens have no spaces. ProcessStartInfo takes one argument string.
        $psi.Arguments = ($NetshArgs -join " ")
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $proc = New-Object System.Diagnostics.Process
        $proc.StartInfo = $psi
        [void]$proc.Start()
        # Read before WaitForExit so a full pipe cannot stall netsh.
        $outTask = $proc.StandardOutput.ReadToEndAsync()
        $errTask = $proc.StandardError.ReadToEndAsync()
        if (-not $proc.WaitForExit(20000)) {
            try { $proc.Kill() } catch {}
            try { $proc.WaitForExit(3000) | Out-Null } catch {}
            return [PSCustomObject]@{ Ok = $false; Reason = "timed out" }
        }
        try { $proc.WaitForExit() | Out-Null } catch {}
        $code = $proc.ExitCode
        if ($null -eq $code) { $code = 1 }
        if ($code -eq 0) {
            return [PSCustomObject]@{ Ok = $true; Reason = "" }
        }
        $text = ""
        try { $text = $outTask.Result } catch {}
        if ([string]::IsNullOrWhiteSpace($text)) {
            try { $text = $errTask.Result } catch {}
        }
        return [PSCustomObject]@{ Ok = $false; Reason = (Get-FirstOutputLine -Text $text -Code $code) }
    } catch {
        return [PSCustomObject]@{ Ok = $false; Reason = "netsh failed to start" }
    } finally {
        if ($null -ne $outTask) { try { [void]$outTask.Wait(2000) } catch {} }
        if ($null -ne $errTask) { try { [void]$errTask.Wait(2000) } catch {} }
        if ($null -ne $proc) { try { $proc.Dispose() } catch {} }
    }
}

function Add-NetshResult {
    param([string]$Key, [string]$Value, [string[]]$NetshArgs)
    $r = Invoke-Netsh -NetshArgs $NetshArgs
    $shown = $Value
    if (-not $r.Ok -and $r.Reason) { $shown = "$Value ($($r.Reason))" }
    Add-Result $Key $shown ([bool]$r.Ok)
}

function Invoke-RegistryRemove {
    # removed = value deleted, absent = already gone, failed = delete error.
    param([string]$Path, [string]$Name)
    try {
        Remove-ItemProperty -Path $Path -Name $Name -ErrorAction Stop | Out-Null
        return "removed"
    } catch {
        # Language-neutral. PS 5.1 reports a missing value as PSArgumentException
        # (not PropertyNotFound). Missing key path is PathNotFound / ItemNotFound.
        $id = [string]$_.FullyQualifiedErrorId
        if ($id -like "PropertyNotFound*" -or $id -like "PathNotFound*" -or $id -like "ItemNotFound*" `
                -or $id -like "System.Management.Automation.PSArgumentException*") {
            return "absent"
        }
        return "failed"
    }
}

function Invoke-RegistrySet {
    param([string]$Path, [string]$Name, [int]$Value)
    try {
        if (-not (Test-Path $Path)) { New-Item -Path $Path -Force | Out-Null }
        Set-ItemProperty -Path $Path -Name $Name -Value $Value -Type DWord -ErrorAction Stop
        return $true
    } catch {
        return $false
    }
}

function Get-TcpInterfacePaths {
    @(Get-ChildItem -Path $tcpipIfRoot -ErrorAction SilentlyContinue | ForEach-Object { $_.PSPath })
}

# TcpAckFrequency / TCPNoDelay are per-interface (Interfaces\{GUID}), not global Parameters.
function Set-InterfaceDword {
    param([string]$Name, [int]$Value)
    $paths = Get-TcpInterfacePaths
    if ($paths.Count -eq 0) { return $false }
    $ok = $true
    foreach ($p in $paths) {
        if (-not (Invoke-RegistrySet -Path $p -Name $Name -Value $Value)) { $ok = $false }
    }
    return $ok
}

function Merge-RemoveState {
    param([string]$Current, [string]$Next)
    if ($Current -eq "failed" -or $Next -eq "failed") { return "failed" }
    if ($Current -eq "removed" -or $Next -eq "removed") { return "removed" }
    return "absent"
}

function Remove-AckNoDelayKey {
    param([string]$Name)
    $state = Invoke-RegistryRemove -Path $tcpipParams -Name $Name
    foreach ($p in Get-TcpInterfacePaths) {
        $state = Merge-RemoveState $state (Invoke-RegistryRemove -Path $p -Name $Name)
    }
    return $state
}

function Add-RemoveResult {
    param([string]$Key, [string]$State)
    if ($State -eq "absent") {
        Add-Result $Key "already absent" $true
    } elseif ($State -eq "removed") {
        Add-Result $Key "removed (registry default)" $true
    } else {
        Add-Result $Key "removed (registry default)" $false
    }
}

switch ($Preset) {
    "MaxPerformance" {
        Add-NetshResult "TCP AutoTuning" "normal" @("int","tcp","set","global","autotuninglevel=normal")
        Add-NetshResult "RSS" "enabled" @("int","tcp","set","global","rss=enabled")
        Add-NetshResult "RSC" "enabled" @("int","tcp","set","global","rsc=enabled")
        Add-NetshResult "ECN" "disabled" @("int","tcp","set","global","ecncapability=disabled")

        Add-RemoveResult "TCP Ack Frequency" (Remove-AckNoDelayKey -Name "TcpAckFrequency")
        Add-RemoveResult "TCP No Delay" (Remove-AckNoDelayKey -Name "TCPNoDelay")
        break
    }
    "MaxStability" {
        Add-NetshResult "TCP AutoTuning" "disabled" @("int","tcp","set","global","autotuninglevel=disabled")
        Add-NetshResult "ECN" "enabled" @("int","tcp","set","global","ecncapability=enabled")
        Add-NetshResult "RSS" "enabled" @("int","tcp","set","global","rss=enabled")

        Add-RemoveResult "TCP Ack Frequency" (Remove-AckNoDelayKey -Name "TcpAckFrequency")
        Add-RemoveResult "TCP No Delay" (Remove-AckNoDelayKey -Name "TCPNoDelay")
        break
    }
    "Gaming" {
        Add-NetshResult "TCP AutoTuning" "disabled" @("int","tcp","set","global","autotuninglevel=disabled")
        Add-NetshResult "RSS" "enabled" @("int","tcp","set","global","rss=enabled")
        Add-NetshResult "ECN" "disabled" @("int","tcp","set","global","ecncapability=disabled")

        # Drop stale global Parameters copies so snapshot/preview do not read a no-op location.
        Invoke-RegistryRemove -Path $tcpipParams -Name "TcpAckFrequency" | Out-Null
        Invoke-RegistryRemove -Path $tcpipParams -Name "TCPNoDelay" | Out-Null
        $ok1 = Set-InterfaceDword -Name "TcpAckFrequency" -Value 1
        $ok2 = Set-InterfaceDword -Name "TCPNoDelay" -Value 1
        Add-Result "TCP Ack Frequency" "1 (set via registry)" $ok1
        Add-Result "TCP No Delay" "1 (set via registry)" $ok2
        break
    }
    default {
        Add-NetshResult "TCP AutoTuning" "normal" @("int","tcp","set","global","autotuninglevel=normal")
        Add-NetshResult "RSS" "default" @("int","tcp","set","global","rss=default")
        Add-NetshResult "ECN" "default" @("int","tcp","set","global","ecncapability=default")
        Add-NetshResult "RSC" "default" @("int","tcp","set","global","rsc=default")

        Add-RemoveResult "TCP Ack Frequency" (Remove-AckNoDelayKey -Name "TcpAckFrequency")
        Add-RemoveResult "TCP No Delay" (Remove-AckNoDelayKey -Name "TCPNoDelay")
        break
    }
}

ConvertTo-Json -Compress $results

if ($failed) {
    exit 1
}
