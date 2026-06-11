param()

$results = @()

try {
    netsh int ip reset 2>&1 | Out-Null
    $results += [PSCustomObject]@{ Key = "TCP/IP Reset"; Value = "completed" }
} catch {
    $results += [PSCustomObject]@{ Key = "TCP/IP Reset"; Value = "failed" }
}

try {
    netsh winsock reset 2>&1 | Out-Null
    $results += [PSCustomObject]@{ Key = "Winsock Reset"; Value = "completed" }
} catch {
    $results += [PSCustomObject]@{ Key = "Winsock Reset"; Value = "failed" }
}

$allSuccess = ($results | Where-Object { $_.Value -eq "failed" }).Count -eq 0

$output = @{
    success = $allSuccess
    rebootRequired = $true
    results = @($results)
}

ConvertTo-Json -Compress $output
