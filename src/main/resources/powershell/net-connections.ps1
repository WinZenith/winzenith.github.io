param([string]$State = "")

$results = @()

$netstat = netstat -ano
$processMap = @{}
Get-Process | ForEach-Object {
    try { $processMap[$_.Id] = $_.ProcessName } catch {}
}

foreach ($line in $netstat) {
    $line = $line.Trim()
    if ($line -match '^(TCP|UDP)\s+(\S+)\s+(\S+)\s+(\S*)') {
        $proto = $Matches[1]
        $local = $Matches[2]
        $remote = $Matches[3]
        $stateVal = $Matches[4]
        if ($stateVal -eq "") { $stateVal = "-" }

        if ($State -and $stateVal -ne $State -and $State -ne "ALL") { continue }

        $pidMatch = [regex]::Match($line, '(\d+)\s*$')
        $pidVal = 0
        $procName = ""
        if ($pidMatch.Success) {
            $pidVal = [int]$pidMatch.Groups[1].Value
            if ($processMap.ContainsKey($pidVal)) {
                $procName = $processMap[$pidVal]
            }
        }

        $results += [PSCustomObject]@{
            Protocol      = $proto
            LocalAddress  = $local
            RemoteAddress = $remote
            State         = $stateVal
            PID           = $pidVal
            ProcessName   = $procName
        }
    }
}

ConvertTo-Json -Compress $results
