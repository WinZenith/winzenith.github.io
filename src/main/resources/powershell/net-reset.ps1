param()

$ErrorActionPreference = 'Continue'
$results = @()

function Test-ResetOk {
    param([int]$Code, $Output)
    if ($Code -eq 0) { return $true }
    $text = ($Output | Out-String)
    if ($text -match '(?i)successfully reset') { return $true }
    if ($text -match '(?i)Resetting .*, OK!') { return $true }
    return $false
}

# Explicit log path: netsh may refuse or write resetlog.txt into an unwritable cwd.
$log = Join-Path $env:TEMP ("winzenith-ip-reset-" + [guid]::NewGuid().ToString("N") + ".log")
$ipOut = & netsh @('int', 'ip', 'reset', $log) 2>&1
$ipCode = $LASTEXITCODE
if ($null -eq $ipCode) { $ipCode = 1 }
$resetOk = Test-ResetOk -Code $ipCode -Output $ipOut
$results += [PSCustomObject]@{ Key = "TCP/IP Reset"; Value = if ($resetOk) { "completed" } else { "failed" } }

$winsockOut = & netsh @('winsock', 'reset') 2>&1
$wsCode = $LASTEXITCODE
if ($null -eq $wsCode) { $wsCode = 1 }
$winsockOk = Test-ResetOk -Code $wsCode -Output $winsockOut
$results += [PSCustomObject]@{ Key = "Winsock Reset"; Value = if ($winsockOk) { "completed" } else { "failed" } }

$allSuccess = $resetOk -and $winsockOk
$anyDestructive = $resetOk -or $winsockOk

$output = @{
    success = $allSuccess
    ipResetOk = $resetOk
    winsockOk = $winsockOk
    rebootRequired = $anyDestructive
    results = @($results)
}

ConvertTo-Json -Compress $output

if (-not $allSuccess) {
    exit 1
}
