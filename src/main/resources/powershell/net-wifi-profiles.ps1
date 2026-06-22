$output = netsh wlan show profiles 2>$null
$profiles = @()

if ($output) {
    foreach ($line in $output) {
        if ($line -match '^\s*All User Profile\s*:\s*(.+)$') {
            $profiles += $Matches[1].Trim()
        }
    }
}

ConvertTo-Json -Compress $profiles
