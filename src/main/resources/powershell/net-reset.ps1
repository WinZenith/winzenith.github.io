param()

$ErrorActionPreference = 'Continue'
$results = @()

function Test-ResetOk {
    param([int]$Code, $Output)
    if ($Code -eq 0) { return $true }
    $text = ($Output | Out-String)
    # Non-zero can still be success on a localized host ("..., OK!") when no line failed.
    if ($text -match '(?i)(failed|fehlgeschlagen|denied|verweigert)') { return $false }
    if ($text -match '(?i)OK!') { return $true }
    if ($text -match '(?i)successfully reset') { return $true }
    return $false
}

function Get-ResetExcerpt {
    param($Output)
    $text = ($Output | Out-String)
    if ([string]::IsNullOrWhiteSpace($text)) { return "" }
    $text = ($text -replace '\s+', ' ').Trim()
    if ($text.Length -gt 240) { $text = $text.Substring(0, 240) }
    return $text
}

# Explicit log path: netsh may refuse or write resetlog.txt into an unwritable cwd.
$log = Join-Path $env:TEMP ("winzenith-ip-reset-" + [guid]::NewGuid().ToString("N") + ".log")
$ipOut = & netsh @('int', 'ip', 'reset', $log) 2>&1
$ipCode = $LASTEXITCODE
if ($null -eq $ipCode) { $ipCode = 1 }
$resetOk = Test-ResetOk -Code $ipCode -Output $ipOut
$results += [PSCustomObject]@{
    Key = "TCP/IP Reset"
    Value = if ($resetOk) { "completed" } else { "failed" }
    Detail = Get-ResetExcerpt $ipOut
}

$winsockOut = & netsh @('winsock', 'reset') 2>&1
$wsCode = $LASTEXITCODE
if ($null -eq $wsCode) { $wsCode = 1 }
$winsockOk = Test-ResetOk -Code $wsCode -Output $winsockOut
$results += [PSCustomObject]@{
    Key = "Winsock Reset"
    Value = if ($winsockOk) { "completed" } else { "failed" }
    Detail = Get-ResetExcerpt $winsockOut
}

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
