param()
$ErrorActionPreference = 'Stop'
function Format-WmiDate {
    param($dt)
    if ($null -eq $dt) { return "" }
    try {
        if ($dt -is [DateTime]) {
            return $dt.ToString('yyyy-MM-dd HH:mm:ss')
        }
        if ($dt -is [string]) {
            if ([string]::IsNullOrWhiteSpace($dt)) { return "" }
            # Try WMI DMTF format (e.g., 20260825120000.000000+120)
            try {
                $converted = [Management.ManagementDateTimeConverter]::ToDateTime($dt)
                return $converted.ToString('yyyy-MM-dd HH:mm:ss')
            } catch { }
            try {
                $parsed = [DateTime]::Parse($dt)
                return $parsed.ToString('yyyy-MM-dd HH:mm:ss')
            } catch { }
            return $dt
        }
        try { return ([DateTime]$dt).ToString('yyyy-MM-dd HH:mm:ss') } catch { }
        return "$dt"
    } catch { return "" }
}
try {
    $rps = Get-CimInstance -Namespace 'root\default' -Class SystemRestore -ErrorAction Stop | ForEach-Object {
        [PSCustomObject]@{
            Description    = $_.Description
            CreationTime   = Format-WmiDate $_.CreationTime
            EventType      = $_.EventType
            SequenceNumber = $_.SequenceNumber
        }
    }
    if ($null -eq $rps) { $rps = @() }
    $arr = @($rps)
    if ($arr.Count -eq 0) {
        Write-Output "[]"
    } else {
        # Ensure single object is wrapped as array
        $json = ConvertTo-Json -Compress -Depth 3 -InputObject $arr
        # PowerShell 5.1 may unwrap single-element array; force array notation
        if ($arr.Count -eq 1 -and $json.TrimStart().StartsWith("{")) {
            $json = "[" + $json + "]"
        }
        Write-Output $json
    }
    exit 0
} catch {
    try {
        $fallback = Get-CimInstance -Namespace 'root\default' -Class SystemRestore -ErrorAction Stop | ForEach-Object {
            [PSCustomObject]@{
                Description    = $_.Description
                CreationTime   = Format-WmiDate $_.CreationTime
                EventType      = $_.EventType
                SequenceNumber = $_.SequenceNumber
            }
        } | Select-Object Description, CreationTime, EventType, SequenceNumber | ConvertTo-Csv -NoTypeInformation
        if ($fallback) {
            $fallback | ForEach-Object { Write-Output $_ }
        } else {
            Write-Output "Description,CreationTime,EventType,SequenceNumber"
        }
        exit 0
    } catch {
        Write-Error $_.Exception.Message
        exit 1
    }
}
