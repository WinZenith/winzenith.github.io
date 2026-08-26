try {
    $services = Get-CimInstance Win32_Service -ErrorAction Stop | ForEach-Object {
        $startMode = switch ($_.StartMode) {
            'Auto' {
                $mode = 'Automatic'
                try {
                    $svcPath = "HKLM:\SYSTEM\CurrentControlSet\Services\$($_.Name)"
                    $delayed = Get-ItemProperty -Path $svcPath -Name 'DelayedAutostart' -ErrorAction SilentlyContinue
                    if ($delayed -and $delayed.DelayedAutostart -eq 1) {
                        $mode = 'Automatic (Delayed Start)'
                    }
                } catch {}
                $mode
            }
            'Manual' { 'Manual' }
            'Disabled' { 'Disabled' }
            default  { $_.StartMode }
        }

        $deps = @()
        try {
            $dependedOn = $_.ServicesDependedOn
            if ($dependedOn) {
                foreach ($dep in $dependedOn) {
                    try { $deps += $dep.Name } catch {}
                }
            }
        } catch {}

        [PSCustomObject]@{
            Name         = $_.Name
            DisplayName  = if ($_.DisplayName) { $_.DisplayName } else { $_.Name }
            BinaryPath   = if ($_.PathName) { $_.PathName } else { "" }
            StartType    = $startMode
            State        = $_.State
            Dependencies = $deps
        }
    }
    @{ Services = @($services) } | ConvertTo-Json -Depth 3
} catch {
    # Return empty list with error info
    @{ Services = @(); Error = $_.Exception.Message } | ConvertTo-Json -Depth 3
}
