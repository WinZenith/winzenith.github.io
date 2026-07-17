param([string]$DriveLetter)
$ErrorActionPreference = 'SilentlyContinue'
$drive = $DriveLetter -replace ':', ''

$fragmentsFound = 0
$fragmentationPercent = 0
$fragmentedFileCount = 0
$totalFileCount = 0
$averageFragmentsPerFile = 0
$mftSizeBytes = 0
$pageFileSizeBytes = 0
$hiberFileSizeBytes = 0
$swapFileSizeBytes = 0
$totalDirectories = 0

# ── 1. Optimize-Volume -Analyze ──
$optOut = @()
try {
    $optOut = @(Optimize-Volume -DriveLetter $drive -Analyze 2>&1 6>&1)
} catch {}

foreach ($line in $optOut) {
    if ($line -isnot [string]) { continue }
    if ($line -match 'Total fragmented space\s*:\s*([\d,]+)\s*(KB|MB|GB|Bytes|bytes)') {
        if ($fragmentsFound -eq 0) {
            $val = $matches[1] -replace ',', ''
            $unit = $matches[2]
            switch ($unit) {
                'Bytes' { $fragmentsFound = [long]$val }
                'bytes' { $fragmentsFound = [long]$val }
                'KB'    { $fragmentsFound = [long]$val * 1024 }
                'MB'    { $fragmentsFound = [long]$val * 1024 * 1024 }
                'GB'    { $fragmentsFound = [long]$val * 1024 * 1024 * 1024 }
            }
        }
    }
    if ($line -match 'Fragmentation percentage\s*:\s*([\d]+)') {
        if ($fragmentationPercent -eq 0) { $fragmentationPercent = [int]$matches[1] }
    }
    if ($line -match 'Fragmentation\s*:\s*([\d]+)\s*%') {
        if ($fragmentationPercent -eq 0) { $fragmentationPercent = [int]$matches[1] }
    }
    if ($line -match 'Fragmented files\s*:\s*([\d]+)') {
        $fragmentedFileCount = [int]$matches[1]
    }
    if ($line -match 'Total files\s*:\s*([\d]+)') {
        $totalFileCount = [int]$matches[1]
    }
    if ($line -match 'Average fragments per file\s*:\s*([\d]+\.?[\d]*)') {
        $averageFragmentsPerFile = [double]$matches[1]
    }
}

# ── 2. Secondary: defrag.exe /A fallback ──
$defragOut = ''
try {
    $defragOut = (& defrag ${drive}: /A 2>&1 | Out-String)
} catch {}

if ($defragOut) {
    # Parse "Total fragmented space" - handles both byte-based and fragment-count formats
    # e.g. "Total fragmented space      =     0 bytes"
    # e.g. "Total fragmented space      =    17 fragments"
    # e.g. "  Total fragmented space: 1.5 GB"
    if ($fragmentsFound -eq 0) {
        if ($defragOut -match 'Total fragmented space\s*[=:]\s*([\d,]+)\s*(bytes|KB|MB|GB|fragments)') {
            $val = $matches[1] -replace ',', ''
            $unit = $matches[2]
            switch ($unit) {
                'bytes'    { $fragmentsFound = [long]$val }
                'KB'       { $fragmentsFound = [long]$val * 1024 }
                'MB'       { $fragmentsFound = [long]$val * 1024 * 1024 }
                'GB'       { $fragmentsFound = [long]$val * 1024 * 1024 * 1024 }
                'fragments' { $fragmentedFileCount = [long]$val }
            }
        }
        if ($fragmentsFound -eq 0 -and $defragOut -match 'Fragmented space\s*[=:]\s*([\d,]+)\s*(bytes|KB|MB|GB|fragments)') {
            $val = $matches[1] -replace ',', ''
            $unit = $matches[2]
            switch ($unit) {
                'bytes'    { $fragmentsFound = [long]$val }
                'KB'       { $fragmentsFound = [long]$val * 1024 }
                'MB'       { $fragmentsFound = [long]$val * 1024 * 1024 }
                'GB'       { $fragmentsFound = [long]$val * 1024 * 1024 * 1024 }
                'fragments' { $fragmentedFileCount = [long]$val }
            }
        }
    }

    # Parse fragmentation percentage - multiple formats
    if ($fragmentationPercent -eq 0) {
        if ($defragOut -match 'Volume fragmentation ratio\s*[=:]\s*(\d+)\s*percent') {
            $fragmentationPercent = [int]$matches[1]
        }
        elseif ($defragOut -match 'Fragmentation ratio\s*[=:]\s*(\d+)\s*%') {
            $fragmentationPercent = [int]$matches[1]
        }
        elseif ($defragOut -match 'Fragmentation\s*[=:]\s*(\d+)\s*%') {
            $fragmentationPercent = [int]$matches[1]
        }
        elseif ($defragOut -match '\((\d+)\s*%\)') {
            $fragmentationPercent = [int]$matches[1]
        }
    }

    # Parse fragmented file count from defrag output
    # e.g. "Fragmented files        : 6"
    if ($fragmentedFileCount -eq 0 -and $defragOut -match 'Fragmented\s+files?\s*[=:]\s*(\d+)') {
        $fragmentedFileCount = [int]$matches[1]
    }
    if ($totalFileCount -eq 0 -and $defragOut -match 'Total\s+files?\s*[=:]\s*(\d+)') {
        $totalFileCount = [int]$matches[1]
    }
}

# ── 3. Get-Volume fallback for fragmentation percentage ──
if ($fragmentationPercent -eq 0) {
    try {
        $vol = Get-Volume -DriveLetter $drive -ErrorAction SilentlyContinue
        if ($vol -and $vol.HealthStatus -ne 'Healthy') {
            if ($vol.HealthStatus -eq 'Caution') { $fragmentationPercent = 5 }
            elseif ($vol.HealthStatus -eq 'Critical') { $fragmentationPercent = 50 }
        }
    } catch {}
}

# ── 4. Estimate fragmented space from fragmentation percent ──
if ($fragmentsFound -eq 0 -and $fragmentationPercent -gt 0) {
    try {
        $vol = Get-Volume -DriveLetter $drive -ErrorAction SilentlyContinue
        if ($vol -and $vol.Size -gt 0) {
            $usedBytes = [long]($vol.Size - $vol.SizeRemaining)
            $fragmentsFound = [long]($usedBytes * $fragmentationPercent / 100)
        }
    } catch {}
}

# ── 5. System files ──
$sysFiles = @("$($drive):\pagefile.sys", "$($drive):\hiberfil.sys", "$($drive):\swapfile.sys")
foreach ($sf in $sysFiles) {
    if (Test-Path -LiteralPath $sf) {
        $item = Get-Item -LiteralPath $sf -ErrorAction SilentlyContinue
        if ($item) {
            $name = $item.Name.ToLower()
            $size = $item.Length
            switch ($name) {
                'pagefile.sys'  { $pageFileSizeBytes = $size }
                'hiberfil.sys'  { $hiberFileSizeBytes = $size }
                'swapfile.sys'  { $swapFileSizeBytes = $size }
            }
        }
    }
}

# ── 6. MFT size via fsutil ──
$fsutilOut = ''
try {
    $fsutilOut = (& fsutil fsinfo ntfsinfo ${drive}: 2>&1 | Out-String)
} catch {}

if ($fsutilOut) {
    if ($fsutilOut -match 'MFT\s+(?:zone|zone size|size)\s*[:\s]+([\d,]+)\s*(?:bytes|KB|MB|GB)?') {
        $mftSizeBytes = ([long]($matches[1] -replace ',', ''))
    }
    if ($mftSizeBytes -eq 0 -and $fsutilOut -match 'MFT\s+(?:zone|zone size|size)\s*:\s*([\d,]+)') {
        $mftSizeBytes = ([long]($matches[1] -replace ',', ''))
    }
    if ($mftSizeBytes -eq 0 -and $fsutilOut -match '(?:Reserved\s+)?MFT\s+(?:Zone|zone)\s*:\s*\(([\d.]+)\s*(KB|MB|GB)\)') {
        $val = [double]$matches[1]
        $unit = $matches[2]
        switch ($unit) {
            'KB' { $mftSizeBytes = [long]($val * 1024) }
            'MB' { $mftSizeBytes = [long]($val * 1024 * 1024) }
            'GB' { $mftSizeBytes = [long]($val * 1024 * 1024 * 1024) }
        }
    }
    if ($mftSizeBytes -eq 0 -and $fsutilOut -match 'MFT bitmap\s*:\s*([\d,]+)\s*bytes') {
        $mftSizeBytes = [long]($matches[1] -replace ',', '')
    }
}

# ── 7. Total directories ──
$totalDirectories = 0

# ── 8. Ensure minimums ──
if ($totalFileCount -eq 0) { $totalFileCount = 1 }

if ($fragmentedFileCount -gt 0 -and $averageFragmentsPerFile -eq 0) {
    $averageFragmentsPerFile = [Math]::Round($fragmentedFileCount / [Math]::Max(1, $totalFileCount), 2)
}

# ── 9. Build result ──
$result = [ordered]@{
    fragmentsFound          = $fragmentsFound
    fragmentationPercent    = $fragmentationPercent
    fragmentedFileCount     = $fragmentedFileCount
    totalFileCount          = $totalFileCount
    averageFragmentsPerFile = $averageFragmentsPerFile
    mftSizeBytes            = $mftSizeBytes
    pageFileSizeBytes       = $pageFileSizeBytes
    hiberFileSizeBytes      = $hiberFileSizeBytes
    swapFileSizeBytes       = $swapFileSizeBytes
    totalDirectories        = $totalDirectories
}
$result | ConvertTo-Json -Depth 2 -Compress
