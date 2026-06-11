$services = Get-CimInstance Win32_Service | ForEach-Object {
    $startMode = switch ($_.StartMode) {
        'Auto'   { 'Automatic' }
        'Manual' { 'Manual' }
        'Disabled' { 'Disabled' }
        default  { $_.StartMode }
    }

    $deps = @()
    try {
        $dependedOn = $_.ServicesDependedOn
        if ($dependedOn) {
            foreach ($dep in $dependedOn) {
                $deps += $dep.Name
            }
        }
    } catch {}

    [PSCustomObject]@{
        Name         = $_.Name
        DisplayName  = $_.DisplayName
        BinaryPath   = $_.PathName
        StartType    = $startMode
        State        = $_.State
        Dependencies = $deps
    }
}

@{ Services = $services } | ConvertTo-Json -Depth 3
