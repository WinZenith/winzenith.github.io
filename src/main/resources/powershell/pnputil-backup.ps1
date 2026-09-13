# Export driver package for backup. Args: InfName BackupFolder
param(
    [Parameter(Mandatory = $true)][string]$InfName,
    [Parameter(Mandatory = $true)][string]$BackupFolder
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $BackupFolder | Out-Null
# Also try WMI fallback for localized Windows where pnputil output is translated
function Find-OemInf {
    param([string]$TargetInf)
    # 1) Try pnputil enum-drivers (English check + case-insensitive fallback)
    try {
        $enum = & pnputil.exe /enum-drivers 2>&1 | Out-String
        $escapedInf = [regex]::Escape($TargetInf)
        $entries = $enum -split '\r?\n\r?\n'
        foreach ($entry in $entries) {
            # English pattern (capture OEM first: a second -match overwrites $Matches)
            if ($entry -match "Published Name\s*:\s*(oem\d+\.inf)") {
                $oemName = $Matches[1]
                if ($entry -match "Original Name\s*:\s*$escapedInf") {
                    return $oemName
                }
            }
            # Localized fallback: any oem*.inf line near the target inf (case-insensitive, no header language dependency).
            # Filename-boundary anchored: a bare Contains() lets hdaudio.inf match
            # the intchdaudio.inf entry (wrong OEM exported). The char before the
            # hit must not be [A-Za-z0-9].
            if ($entry -match "(oem\d+\.inf)") {
                $oemCandidate = $Matches[1]
                $hit = $false
                foreach ($line in ($entry -split '\r?\n')) {
                    $t = $line.Trim()
                    if ($t.Length -ge $TargetInf.Length -and $t.ToLower().EndsWith($TargetInf.ToLower())) {
                        $prev = ' '
                        if ($t.Length -gt $TargetInf.Length) { $prev = $t[$t.Length - $TargetInf.Length - 1] }
                        # [_-] are glue too (my_hdaudio.inf): only a boundary
                        # outside [A-Za-z0-9_-] counts as a filename hit.
                        if ([string]$prev -notmatch '[A-Za-z0-9_-]') { $hit = $true; break }
                    }
                }
                if ($hit) { return $oemCandidate }
            }
        }
    } catch {}
    # 2) WMI fallback via Win32_PnPSignedDriver - language independent
    try {
        $wmi = Get-CimInstance Win32_PnPSignedDriver -ErrorAction SilentlyContinue | Where-Object { $_.InfName -eq $TargetInf -or $_.InfName.ToLower() -eq $TargetInf.ToLower() }
        foreach ($d in $wmi) {
            # InfName may be oem*.inf already; try to map via DriverName
            # Search pnputil again for the wmi device's InfName
            if ($d.InfName -match '^oem\d+\.inf$') { return $d.InfName }
        }
    } catch {}
    # 3) Search DriverStore directly (filter wildcards escaped: a scan-supplied
    # INF name containing * ? [ ] must not glob unrelated entries; literal
    # .Replace chain avoids regex-quoting traps)
    try {
        $filterEscaped = $TargetInf.Replace('*', '`*').Replace('?', '`?').Replace('[', '`[').Replace(']', '`]')
        $store = Get-ChildItem "$env:SystemRoot\System32\DriverStore\FileRepository" -Filter $filterEscaped -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($store) {
            # Find OEM within the same enum entry as the target INF (not the first OEM overall)
            $enum2 = & pnputil.exe /enum-drivers 2>&1 | Out-String
            $entries2 = $enum2 -split '\r?\n\r?\n'
            foreach ($e2 in $entries2) {
                if ($e2 -match "Original Name\s*:\s*$([regex]::Escape($TargetInf))") {
                    $m = [regex]::Match($e2, "(oem\d+\.inf)")
                    if ($m.Success) { return $m.Groups[1].Value }
                }
            }
        }
    } catch {}
    return $null
}

if ($InfName -match '^oem\d+\.inf$') {
    & pnputil.exe /export-driver $InfName $BackupFolder
} else {
    $oem = Find-OemInf -TargetInf $InfName
    if (-not $oem) {
        Write-Error "Could not resolve OEM INF for $InfName (localized lookup also failed)"
        exit 1
    }
    & pnputil.exe /export-driver $oem $BackupFolder
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
# Verify at least one file was exported
$exported = Get-ChildItem -Path $BackupFolder -Recurse -ErrorAction SilentlyContinue | Measure-Object | Select-Object -ExpandProperty Count
if (-not $exported -or $exported -eq 0) {
    Write-Error "Export succeeded but no files in $BackupFolder"
    exit 1
}
@{ success = $true; folder = $BackupFolder; files = $exported } | ConvertTo-Json -Compress
