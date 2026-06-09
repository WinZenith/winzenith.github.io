param([string]$DriveLetter)
$ErrorActionPreference = 'SilentlyContinue'
$output = & Optimize-Volume -DriveLetter $DriveLetter -Analyze 2>&1 4>$null
$fragmentsFound = 0
$fragmentationPercent = 0
foreach ($line in $output) {
    if ($line -isnot [string]) { continue }
    if ($line -match 'Total fragmented space\s*:\s*([\d,]+)\s*(KB|MB|GB|Bytes)') {
        $val = $matches[1] -replace ',', ''
        $unit = $matches[2]
        switch ($unit) {
            'Bytes' { $fragmentsFound = [long]$val }
            'KB'    { $fragmentsFound = [long]$val * 1024 }
            'MB'    { $fragmentsFound = [long]$val * 1024 * 1024 }
            'GB'    { $fragmentsFound = [long]$val * 1024 * 1024 * 1024 }
        }
    }
    if ($line -match 'Fragmentation percentage\s*:\s*([\d]+)') {
        $fragmentationPercent = [int]$matches[1]
    }
    if ($line -match 'Fragmentation\s*:\s*([\d]+)\s*%') {
        $fragmentationPercent = [int]$matches[1]
    }
}
$result = [ordered]@{
    fragmentsFound      = $fragmentsFound
    fragmentationPercent = $fragmentationPercent
    rawOutput           = ($output | Out-String)
}
$result | ConvertTo-Json -Depth 2 -Compress
