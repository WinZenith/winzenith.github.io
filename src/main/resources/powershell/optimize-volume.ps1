param([string]$DriveLetter, [string]$Mode)
$ErrorActionPreference = 'Continue'
$result = [ordered]@{ success = $false; message = '' }
try {
    $drive = $DriveLetter -replace ':', ''
    $progressId = 1
    switch ($Mode.ToUpper()) {
        'TRIM' {
            $output = Optimize-Volume -DriveLetter $drive -ReTrim -Verbose 2>&1
            $result.success = $true
            $result.message = 'Trim completed successfully.'
        }
        'FAST' {
            $output = Optimize-Volume -DriveLetter $drive -Defrag -GaringFlags 1 -Verbose 2>&1
            $result.success = $true
            $result.message = 'Fast defrag completed successfully.'
        }
        'FULL' {
            $output = Optimize-Volume -DriveLetter $drive -Defrag -Verbose 2>&1
            $result.success = $true
            $result.message = 'Full defrag completed successfully.'
        }
        'FREE_SPACE' {
            $output = Optimize-Volume -DriveLetter $drive -FreeSpace -Verbose 2>&1
            $result.success = $true
            $result.message = 'Free space consolidation completed successfully.'
        }
        default {
            $result.message = "Unknown mode: $Mode"
            $result | ConvertTo-Json -Depth 2 -Compress
            exit 1
        }
    }
    $errors = $output | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] }
    if ($errors) {
        $errMsg = ($errors | Out-String).Trim()
        if ($errMsg) { $result.message = $errMsg; $result.success = $false }
    }
} catch {
    $result.message = $_.Exception.Message
}
$result | ConvertTo-Json -Depth 2 -Compress
