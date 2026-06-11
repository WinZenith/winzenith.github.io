param()

try {
    ipconfig /flushdns 2>&1 | Out-Null
    Clear-DnsClientCache -ErrorAction SilentlyContinue 2>&1 | Out-Null
    Write-Output '{"success": true, "message": "DNS cache flushed successfully."}'
} catch {
    Write-Output ('{"success": false, "message": "' + $_.Exception.Message.Replace('"', '""') + '"}')
}
