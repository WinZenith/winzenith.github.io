param()

$results = @()
$allSuccess = $true

netsh int ip reset 2>&1 | Out-Null
$resetOk = $LASTEXITCODE -eq 0
$results += [PSCustomObject]@{ Key = "TCP/IP Reset"; Value = if ($resetOk) { "completed" } else { "failed" } }
if (-not $resetOk) { $allSuccess = $false }

netsh winsock reset 2>&1 | Out-Null
$winsockOk = $LASTEXITCODE -eq 0
$results += [PSCustomObject]@{ Key = "Winsock Reset"; Value = if ($winsockOk) { "completed" } else { "failed" } }
if (-not $winsockOk) { $allSuccess = $false }

$output = @{
    success = $allSuccess
    rebootRequired = $allSuccess
    results = @($results)
}

ConvertTo-Json -Compress $output

if (-not $allSuccess) {
    exit 1
}
