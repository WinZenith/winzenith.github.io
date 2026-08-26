$output = netsh wlan show profiles 2>$null
$profiles = @()

if ($output) {
    foreach ($line in $output) {
        # Locale-independent: profile lines are indented and contain " : " separator, e.g.
        # English: "    All User Profile     : My WiFi"
        # German:  "    Profil für alle Benutzer   : Mein WLAN"
        # Use indented colon pattern to avoid header lines like "Profiles on interface ..."
        if ($line -match '^\s+[^:]+:\s*(.+)$') {
            $candidate = $Matches[1].Trim()
            # Skip placeholder entries like <None> / <Keine>
            if ($candidate -and $candidate -notmatch '^<.*>$' -and $candidate -ne "") {
                # Exclude non-profile indented lines that happen to contain colon but are not profiles?
                # Profile lines are the only indented lines with a value that is a plausible SSID;
                # headers have no value or are filtered above. Keep all candidates as profiles.
                $profiles += $candidate
            }
        }
    }
    # Fallback: if still empty try alternative parsing via wildcard Wlan API (for systems where netsh output format differs)
    if ($profiles.Count -eq 0) {
        try {
            $wlanProfiles = netsh wlan show profiles 2>$null | Select-String -Pattern ':\s*(.+)$'
            foreach ($m in $wlanProfiles) {
                $c = $m.Matches[0].Groups[1].Value.Trim()
                if ($c -and $c -notmatch '^<.*>$' -and $c -notmatch 'interface|Schnittstelle|Interface' -and $m.Line -match '^\s+') {
                    if ($profiles -notcontains $c) { $profiles += $c }
                }
            }
        } catch {}
    }
}

ConvertTo-Json -Compress $profiles
