param()

try {
    $flushOut = ipconfig /flushdns 2>&1
    $ok = ($LASTEXITCODE -eq 0)
    try { Clear-DnsClientCache -ErrorAction SilentlyContinue 2>&1 | Out-Null } catch {}
    $msg = if ($ok) { "DNS cache flushed successfully." } else { "ipconfig /flushdns failed (exit $LASTEXITCODE): $flushOut" }
    $output = @{ success = $ok; message = $msg }
    ConvertTo-Json -Compress $output
    if (-not $ok) { exit 1 }
} catch {
    $output = @{ success = $false; message = $_.Exception.Message }
    ConvertTo-Json -Compress $output
}
