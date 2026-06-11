param([string]$DriveLetter, [string]$Mode)
$drive = $DriveLetter -replace ':', ''

$currentPhase = 0
$totalPhases = 0

function EmitProgress {
    param([int]$pct)
    $prog = [ordered]@{ progress = $pct }
    $prog | ConvertTo-Json -Compress
}

function EmitResult {
    param([bool]$ok, [string]$msg)
    $res = [ordered]@{ success = $ok; message = $msg }
    $res | ConvertTo-Json -Compress
}

function SelectAction {
    param([string]$m)
    switch ($m.ToUpper()) {
        'TRIM'       { return @{ Cmd = "Optimize-Volume -DriveLetter $drive -ReTrim -Verbose"; Phases = 1 } }
        'FAST'       { return @{ Cmd = "Optimize-Volume -DriveLetter $drive -Defrag -GaringFlags 1 -Verbose"; Phases = 2 } }
        'FULL'       { return @{ Cmd = "Optimize-Volume -DriveLetter $drive -Defrag -Verbose"; Phases = 3 } }
        'FREE_SPACE' { return @{ Cmd = "Optimize-Volume -DriveLetter $drive -FreeSpace -Verbose"; Phases = 1 } }
        default { throw "Unknown mode: $m" }
    }
}

try {
    $action = SelectAction $Mode
    $totalPhases = $action.Phases

    # Emit 0% to start
    EmitProgress 0

    $sb = [scriptblock]::Create($action.Cmd)
    & $sb *>&1 | ForEach-Object {
        $msg = ""
        if ($_ -is [string]) {
            $msg = $_
        } elseif ($_.Message) {
            $msg = $_.Message
        } else {
            $msg = "$_"
        }

        # Detect phase start
        if ($msg -match 'phase (\d+) of (\d+)') {
            $currentPhase = [int]$matches[1]
            $totalPhases  = [int]$matches[2]
            $basePct = [math]::Max(0, (($currentPhase - 1) * 100) / $totalPhases)
            EmitProgress $basePct
        }

        # Detect sub-progress within a phase
        if ($msg -match '(\d+)%\s*complete') {
            $pct = [int]$matches[1]
            if ($totalPhases -gt 0 -and $currentPhase -gt 0) {
                $overallPct = [math]::Round((($currentPhase - 1) * 100) / $totalPhases + ($pct / $totalPhases))
            } else {
                $overallPct = $pct
            }
            EmitProgress $overallPct
        }
    }

    EmitProgress 100
    EmitResult $true "Operation completed successfully."
} catch {
    EmitResult $false $_.Exception.Message
}
