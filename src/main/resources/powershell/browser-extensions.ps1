param([string]$Browser = "Chrome")
$OutputEncoding = [System.Text.Encoding]::UTF8
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$result = @()

if ($Browser -eq "Chrome" -or $Browser -eq "All") {
    $cp = "$env:LOCALAPPDATA\Google\Chrome\User Data"
    if (Test-Path $cp) {
        Get-ChildItem "$cp\*\Extensions" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $extDir = $_.FullName
            Get-ChildItem $extDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $extId = $_.Name
                $vd = Get-ChildItem $_.FullName -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
                if ($vd) {
                    $mp = Join-Path (Join-Path $_.FullName $vd.Name) "manifest.json"
                    if (Test-Path $mp) {
                        $m = Get-Content $mp -Raw | ConvertFrom-Json
                        $rawName = if ($m.name) { $m.name } else { "Unknown" }
                        $resolvedName = $rawName
                        $rawDesc = if ($m.description) { $m.description } else { "" }
                        $resolvedDesc = $rawDesc
                        if ($rawName -match '^__MSG_(.+)__$') {
                            $key = $matches[1]
                            $locale = if ($m.default_locale) { $m.default_locale } else { "en" }
                            $msgPath = Join-Path (Join-Path $vd.FullName "_locales") (Join-Path $locale "messages.json")
                            if (Test-Path $msgPath) { try { $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json; if ($msgs.$key -and $msgs.$key.message) { $resolvedName = $msgs.$key.message } } catch {} }
                        }
                        if ($rawDesc -match '^__MSG_(.+)__$') {
                            $key = $matches[1]
                            $locale = if ($m.default_locale) { $m.default_locale } else { "en" }
                            $msgPath = Join-Path (Join-Path $vd.FullName "_locales") (Join-Path $locale "messages.json")
                            if (Test-Path $msgPath) { try { $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json; if ($msgs.$key -and $msgs.$key.message) { $resolvedDesc = $msgs.$key.message } } catch {} }
                        }
                        $result += [PSCustomObject]@{
                            id = $extId
                            name = $resolvedName
                            version = if ($m.version) { $m.version } else { "" }
                            description = $resolvedDesc
                            enabled = $true
                            browser = "Chrome"
                            path = $extDir
                            installTime = $_.CreationTime.ToString("yyyy-MM-dd HH:mm:ss")
                            permissions = if ($m.permissions) { ($m.permissions -join ", ") } else { "" }
                        }
                    }
                }
            }
        }
    }
}

if ($Browser -eq "Edge" -or $Browser -eq "All") {
    $cp = "$env:LOCALAPPDATA\Microsoft\Edge\User Data"
    if (Test-Path $cp) {
        Get-ChildItem "$cp\*\Extensions" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $extDir = $_.FullName
            Get-ChildItem $extDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $extId = $_.Name
                $vd = Get-ChildItem $_.FullName -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
                if ($vd) {
                    $mp = Join-Path (Join-Path $_.FullName $vd.Name) "manifest.json"
                    if (Test-Path $mp) {
                        $m = Get-Content $mp -Raw | ConvertFrom-Json
                        $rawName = if ($m.name) { $m.name } else { "Unknown" }
                        $resolvedName = $rawName
                        $rawDesc = if ($m.description) { $m.description } else { "" }
                        $resolvedDesc = $rawDesc
                        if ($rawName -match '^__MSG_(.+)__$') {
                            $key = $matches[1]
                            $locale = if ($m.default_locale) { $m.default_locale } else { "en" }
                            $msgPath = Join-Path (Join-Path $vd.FullName "_locales") (Join-Path $locale "messages.json")
                            if (Test-Path $msgPath) { try { $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json; if ($msgs.$key -and $msgs.$key.message) { $resolvedName = $msgs.$key.message } } catch {} }
                        }
                        if ($rawDesc -match '^__MSG_(.+)__$') {
                            $key = $matches[1]
                            $locale = if ($m.default_locale) { $m.default_locale } else { "en" }
                            $msgPath = Join-Path (Join-Path $vd.FullName "_locales") (Join-Path $locale "messages.json")
                            if (Test-Path $msgPath) { try { $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json; if ($msgs.$key -and $msgs.$key.message) { $resolvedDesc = $msgs.$key.message } } catch {} }
                        }
                        $result += [PSCustomObject]@{
                            id = $extId
                            name = $resolvedName
                            version = if ($m.version) { $m.version } else { "" }
                            description = $resolvedDesc
                            enabled = $true
                            browser = "Edge"
                            path = $extDir
                            installTime = $_.CreationTime.ToString("yyyy-MM-dd HH:mm:ss")
                            permissions = if ($m.permissions) { ($m.permissions -join ", ") } else { "" }
                        }
                    }
                }
            }
        }
    }
}

if ($Browser -eq "Brave" -or $Browser -eq "All") {
    $cp = "$env:LOCALAPPDATA\BraveSoftware\Brave-Browser\User Data"
    if (Test-Path $cp) {
        Get-ChildItem "$cp\*\Extensions" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $extDir = $_.FullName
            Get-ChildItem $extDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $extId = $_.Name
                $vd = Get-ChildItem $_.FullName -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
                if ($vd) {
                    $mp = Join-Path (Join-Path $_.FullName $vd.Name) "manifest.json"
                    if (Test-Path $mp) {
                        $m = Get-Content $mp -Raw | ConvertFrom-Json
                        $rawName = if ($m.name) { $m.name } else { "Unknown" }
                        $resolvedName = $rawName
                        $rawDesc = if ($m.description) { $m.description } else { "" }
                        $resolvedDesc = $rawDesc
                        if ($rawName -match '^__MSG_(.+)__$') {
                            $key = $matches[1]
                            $locale = if ($m.default_locale) { $m.default_locale } else { "en" }
                            $msgPath = Join-Path (Join-Path $vd.FullName "_locales") (Join-Path $locale "messages.json")
                            if (Test-Path $msgPath) { try { $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json; if ($msgs.$key -and $msgs.$key.message) { $resolvedName = $msgs.$key.message } } catch {} }
                        }
                        if ($rawDesc -match '^__MSG_(.+)__$') {
                            $key = $matches[1]
                            $locale = if ($m.default_locale) { $m.default_locale } else { "en" }
                            $msgPath = Join-Path (Join-Path $vd.FullName "_locales") (Join-Path $locale "messages.json")
                            if (Test-Path $msgPath) { try { $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json; if ($msgs.$key -and $msgs.$key.message) { $resolvedDesc = $msgs.$key.message } } catch {} }
                        }
                        $result += [PSCustomObject]@{
                            id = $extId
                            name = $resolvedName
                            version = if ($m.version) { $m.version } else { "" }
                            description = $resolvedDesc
                            enabled = $true
                            browser = "Brave"
                            path = $extDir
                            installTime = $_.CreationTime.ToString("yyyy-MM-dd HH:mm:ss")
                            permissions = if ($m.permissions) { ($m.permissions -join ", ") } else { "" }
                        }
                    }
                }
            }
        }
    }
}

if ($Browser -eq "Opera" -or $Browser -eq "All") {
    $extDir = "$env:APPDATA\Opera Software\Opera Stable\Extensions"
    if (Test-Path $extDir) {
        Get-ChildItem $extDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $extId = $_.Name
            $vd = Get-ChildItem $_.FullName -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($vd) {
                $mp = Join-Path (Join-Path $_.FullName $vd.Name) "manifest.json"
                if (Test-Path $mp) {
                    $m = Get-Content $mp -Raw | ConvertFrom-Json
                    $rawName = if ($m.name) { $m.name } else { "Unknown" }
                    $resolvedName = $rawName
                    $rawDesc = if ($m.description) { $m.description } else { "" }
                    $resolvedDesc = $rawDesc
                    if ($rawName -match '^__MSG_(.+)__$') {
                        $key = $matches[1]
                        $locale = if ($m.default_locale) { $m.default_locale } else { "en" }
                        $msgPath = Join-Path (Join-Path $vd.FullName "_locales") (Join-Path $locale "messages.json")
                        if (Test-Path $msgPath) { try { $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json; if ($msgs.$key -and $msgs.$key.message) { $resolvedName = $msgs.$key.message } } catch {} }
                    }
                    if ($rawDesc -match '^__MSG_(.+)__$') {
                        $key = $matches[1]
                        $locale = if ($m.default_locale) { $m.default_locale } else { "en" }
                        $msgPath = Join-Path (Join-Path $vd.FullName "_locales") (Join-Path $locale "messages.json")
                        if (Test-Path $msgPath) { try { $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json; if ($msgs.$key -and $msgs.$key.message) { $resolvedDesc = $msgs.$key.message } } catch {} }
                    }
                    $result += [PSCustomObject]@{
                        id = $extId
                        name = $resolvedName
                        version = if ($m.version) { $m.version } else { "" }
                        description = $resolvedDesc
                        enabled = $true
                        browser = "Opera"
                        path = $extDir
                        installTime = $_.CreationTime.ToString("yyyy-MM-dd HH:mm:ss")
                        permissions = if ($m.permissions) { ($m.permissions -join ", ") } else { "" }
                    }
                }
            }
        }
    }
}

if ($Browser -eq "Vivaldi" -or $Browser -eq "All") {
    $cp = "$env:LOCALAPPDATA\Vivaldi\User Data"
    if (Test-Path $cp) {
        Get-ChildItem "$cp\*\Extensions" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $extDir = $_.FullName
            Get-ChildItem $extDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $extId = $_.Name
                $vd = Get-ChildItem $_.FullName -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
                if ($vd) {
                    $mp = Join-Path (Join-Path $_.FullName $vd.Name) "manifest.json"
                    if (Test-Path $mp) {
                        $m = Get-Content $mp -Raw | ConvertFrom-Json
                        $rawName = if ($m.name) { $m.name } else { "Unknown" }
                        $resolvedName = $rawName
                        $rawDesc = if ($m.description) { $m.description } else { "" }
                        $resolvedDesc = $rawDesc
                        if ($rawName -match '^__MSG_(.+)__$') {
                            $key = $matches[1]
                            $locale = if ($m.default_locale) { $m.default_locale } else { "en" }
                            $msgPath = Join-Path (Join-Path $vd.FullName "_locales") (Join-Path $locale "messages.json")
                            if (Test-Path $msgPath) { try { $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json; if ($msgs.$key -and $msgs.$key.message) { $resolvedName = $msgs.$key.message } } catch {} }
                        }
                        if ($rawDesc -match '^__MSG_(.+)__$') {
                            $key = $matches[1]
                            $locale = if ($m.default_locale) { $m.default_locale } else { "en" }
                            $msgPath = Join-Path (Join-Path $vd.FullName "_locales") (Join-Path $locale "messages.json")
                            if (Test-Path $msgPath) { try { $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json; if ($msgs.$key -and $msgs.$key.message) { $resolvedDesc = $msgs.$key.message } } catch {} }
                        }
                        $result += [PSCustomObject]@{
                            id = $extId
                            name = $resolvedName
                            version = if ($m.version) { $m.version } else { "" }
                            description = $resolvedDesc
                            enabled = $true
                            browser = "Vivaldi"
                            path = $extDir
                            installTime = $_.CreationTime.ToString("yyyy-MM-dd HH:mm:ss")
                            permissions = if ($m.permissions) { ($m.permissions -join ", ") } else { "" }
                        }
                    }
                }
            }
        }
    }
}

if ($Browser -eq "Firefox" -or $Browser -eq "All") {
    $ffProfiles = "$env:APPDATA\Mozilla\Firefox\Profiles"
    if (Test-Path $ffProfiles) {
        Get-ChildItem $ffProfiles -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $extJson = Join-Path $_.FullName "extensions.json"
            if (Test-Path $extJson) {
                $json = Get-Content $extJson -Raw | ConvertFrom-Json
                $addons = $json.addons
                if (-not $addons) { $addons = $json }
                $addons | ForEach-Object {
                    $addon = $_
                    $result += [PSCustomObject]@{
                        id = if ($addon.id) { $addon.id } else { if ($addon.defaultLocale) { $addon.defaultLocale.name } else { "" } }
                        name = if ($addon.defaultLocale -and $addon.defaultLocale.name) { $addon.defaultLocale.name } else { $addon.name }
                        version = if ($addon.version) { $addon.version } else { "" }
                        description = if ($addon.defaultLocale -and $addon.defaultLocale.description) { $addon.defaultLocale.description } else { "" }
                        enabled = if ($addon.disabled) { -not $addon.disabled } else { $true }
                        browser = "Firefox"
                        path = $_.FullName
                        installTime = if ($addon.installDate) { $addon.installDate } else { "" }
                        permissions = if ($addon.permissions) { ($addon.permissions -join ", ") } else { "" }
                    }
                }
            }
        }
    }
}

if ($result.Count -eq 0) {
    Write-Output "[]"
} else {
    ConvertTo-Json -Compress $result
}
