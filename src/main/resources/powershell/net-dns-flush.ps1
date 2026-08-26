param()

try {
    $null = ipconfig /flushdns 2>&1
    try { Clear-DnsClientCache -ErrorAction SilentlyContinue 2>&1 | Out-Null } catch {}
    $output = @{ success = $true; message = "DNS cache flushed successfully." }
    ConvertTo-Json -Compress $output
} catch {
    $output = @{ success = $false; message = $_.Exception.Message }
    ConvertTo-Json -Compress $output
}
