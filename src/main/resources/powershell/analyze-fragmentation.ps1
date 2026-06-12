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
$optOut = & Optimize-Volume -DriveLetter $drive -Analyze 4>$null 2>&1
foreach ($line in $optOut) {
    if ($line -isnot [string]) { continue }
    if ($line -match 'Total fragmented space\s*:\s*([\d,]+)\s*(KB|MB|GB|Bytes)') {
        if ($fragmentsFound -eq 0) {
            $val = $matches[1] -replace ',', ''
            $unit = $matches[2]
            switch ($unit) {
                'Bytes' { $fragmentsFound = [long]$val }
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
    if ($line -match 'File size\s*:\s*([\d,]+)\s*(KB|MB|GB|Bytes)') {
        if ($totalFileCount -eq 0) { $totalFileCount = 1 }
    }
}

# ── 2. Secondary: defrag.exe /A fallback (provides more detail on older Windows) ──
$defragOut = & defrag ${drive}: /A 2>&1 | Out-String
if ($defragOut) {
    # Parse "Total fragmented space" alternative format
    if ($fragmentsFound -eq 0 -and $defragOut -match 'Fragmented space\s*:\s*([\d,]+)\s*(bytes|KB|MB|GB)') {
        $val = $matches[1] -replace ',', ''
        $unit = $matches[2]
        switch ($unit) {
            'bytes' { $fragmentsFound = [long]$val }
            'KB'    { $fragmentsFound = [long]$val * 1024 }
            'MB'    { $fragmentsFound = [long]$val * 1024 * 1024 }
            'GB'    { $fragmentsFound = [long]$val * 1024 * 1024 * 1024 }
        }
    }
    if ($fragmentationPercent -eq 0 -and $defragOut -match 'Fragmentation\s*:\s*([\d]+)\s*%') {
        $fragmentationPercent = [int]$matches[1]
    }
    if ($fragmentationPercent -eq 0 -and $defragOut -match '\((\d+)\s*%\)') {
        $fragmentationPercent = [int]$matches[1]
    }
}

# ── 3. System files ──
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

# ── 4. MFT size via fsutil ──
$fsutilOut = & fsutil fsinfo ntfsinfo ${drive}: 2>&1 | Out-String
if ($fsutilOut) {
    if ($fsutilOut -match 'MFT\s+(?:zone|zone size|size)\s*[:\s]+([\d,]+)\s*(?:bytes|KB|MB|GB)?') {
        $mftSizeBytes = ([long]($matches[1] -replace ',', ''))
    }
    if ($mftSizeBytes -eq 0 -and $fsutilOut -match 'MFT\s+(?:zone|zone size|size)\s*:\s*([\d,]+)') {
        $mftSizeBytes = ([long]($matches[1] -replace ',', ''))
    }
    # Try "MFT reserved zone" alternate format
    if ($mftSizeBytes -eq 0 -and $fsutilOut -match '(?:Reserved\s+)?MFT\s+(?:Zone|zone)\s*:\s*\(([\d.]+)\s*(KB|MB|GB)\)') {
        $val = [double]$matches[1]
        $unit = $matches[2]
        switch ($unit) {
            'KB' { $mftSizeBytes = [long]($val * 1024) }
            'MB' { $mftSizeBytes = [long]($val * 1024 * 1024) }
            'GB' { $mftSizeBytes = [long]($val * 1024 * 1024 * 1024) }
        }
    }
    # Fallback: Parse "MFT bitmap" allocation
    if ($mftSizeBytes -eq 0 -and $fsutilOut -match 'MFT bitmap\s*:\s*([\d,]+)\s*bytes') {
        $mftSizeBytes = [long]($matches[1] -replace ',', '')
    }
}

# ── 5. Total directories (non-recursive, fast) ──
$totalDirectories = @(Get-ChildItem -Path "$($drive):\" -Directory -ErrorAction SilentlyContinue).Count

# ── 6. Estimate total file count from volume info (avoid slow recursive enumeration) ──
if ($totalFileCount -eq 0) {
    $totalFileCount = @(Get-ChildItem -Path "$($drive):\" -File -ErrorAction SilentlyContinue).Count
}
if ($totalFileCount -eq 0) { $totalFileCount = 1 }

if ($fragmentedFileCount -gt 0) {
    $averageFragmentsPerFile = [Math]::Round($fragmentedFileCount / [Math]::Max(1, $totalFileCount), 2)
}

# ── 7. Build result ──
$result = [ordered]@{
    fragmentsFound        = $fragmentsFound
    fragmentationPercent  = $fragmentationPercent
    fragmentedFileCount   = $fragmentedFileCount
    totalFileCount        = $totalFileCount
    averageFragmentsPerFile = $averageFragmentsPerFile
    mftSizeBytes          = $mftSizeBytes
    pageFileSizeBytes     = $pageFileSizeBytes
    hiberFileSizeBytes    = $hiberFileSizeBytes
    swapFileSizeBytes     = $swapFileSizeBytes
    totalDirectories      = $totalDirectories
    rawOutput             = ($optOut | Out-String)
}
$result | ConvertTo-Json -Depth 2 -Compress
