param(
    [string]$Browser = "Chrome",
    [string]$Action = "Scan",
    [string]$ProfilePath = "",
    [string]$ExtId = "",
    [string]$Enable = "",
    [string]$UserDataPath = "",
    [string]$Engine = "",
    [string]$BrowserLabel = ""
)
$OutputEncoding = [System.Text.Encoding]::UTF8
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$result = @()

$illegalFilenameChars = '[\\/:*?"<>|]'

function Get-ChromiumExtState {
    param(
        [string]$ProfileDir,
        [string]$ExtensionId
    )
    # Check Secure Preferences first (modern Chromium), then Preferences (legacy)
    $filesToCheck = @(
        (Join-Path $ProfileDir "Secure Preferences"),
        (Join-Path $ProfileDir "Preferences")
    )
    foreach ($prefsFile in $filesToCheck) {
        if (-not (Test-Path $prefsFile)) { continue }
        try {
            $prefs = Get-Content $prefsFile -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
            if ($prefs.extensions -and $prefs.extensions.settings) {
                $extSettings = $prefs.extensions.settings.PSObject.Properties[$ExtensionId]
                if ($extSettings -and $extSettings.Value) {
                    $val = $extSettings.Value
                    # Handle disable_reasons as array (Chrome new) or integer (Edge/old)
                    $disableReasons = $null
                    if ($val.PSObject.Properties['disable_reasons']) { $disableReasons = $val.disable_reasons }
                    $isDisabled = $false
                    if ($null -ne $disableReasons) {
                        if ($disableReasons -is [Array]) {
                            if ($disableReasons.Count -gt 0) { $isDisabled = $true }
                        } elseif ($disableReasons -is [System.Collections.IList]) {
                            if ($disableReasons.Count -gt 0) { $isDisabled = $true }
                        } else {
                            try { if ([int]$disableReasons -ne 0) { $isDisabled = $true } } catch { }
                        }
                    }
                    if ($isDisabled) { return $false }

                    $state = $null
                    if ($val.PSObject.Properties['state']) { $state = $val.state }
                    if ($null -ne $state) {
                        try { return ([int]$state -eq 1) } catch {
                            if ($state -eq 1 -or $state -eq "1" -or $state -eq $true) { return $true } else { return $false }
                        }
                    }
                    # No state but disable_reasons present and not disabled => enabled
                    if ($null -ne $disableReasons) {
                        if ($disableReasons -is [Array] -and $disableReasons.Count -eq 0) { return $true }
                        try { if ([int]$disableReasons -eq 0) { return $true } } catch { }
                    }
                    # If state missing and no disable_reasons, assume enabled (installed)
                    return $true
                }
            }
        } catch { }
    }
    return $null
}

function Resolve-LocaleMessage {
    param(
        [string]$VersionDir,
        [string]$Key,
        [string]$DefaultLocale
    )
    $localesBase = Join-Path $VersionDir "_locales"
    if (-not (Test-Path $localesBase)) { return $null }
    $candidates = @()
    if ($DefaultLocale) { $candidates += $DefaultLocale }
    if ($DefaultLocale -and $DefaultLocale -ne "en") { $candidates += "en" }
    if (-not $candidates.Contains("en")) { $candidates += "en" }
    # Add first available locale as last fallback
    try {
        $first = Get-ChildItem $localesBase -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($first -and -not $candidates.Contains($first.Name)) { $candidates += $first.Name }
    } catch { }
    foreach ($loc in $candidates) {
        $msgPath = Join-Path (Join-Path $localesBase $loc) "messages.json"
        if (Test-Path $msgPath) {
            try {
                $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json
                if ($msgs.$Key -and $msgs.$Key.message) { return $msgs.$Key.message }
            } catch { }
        }
    }
    return $null
}

function Get-ChromiumManifestPermissions {
    param([object]$Manifest)
    $collected = @()
    $seen = @{}
    $addItems = {
        param([object]$Items)
        if ($null -eq $Items) { return }
        foreach ($it in @($Items)) {
            if ($null -eq $it) { continue }
            $s = ""
            try {
                if ($it -is [string]) { $s = $it }
                else { $s = "$it" }
            } catch { continue }
            $s = $s.Trim()
            if ($s -eq "" -or $seen.ContainsKey($s)) { continue }
            $seen[$s] = $true
            $collected += $s
        }
    }
    try { &$addItems $Manifest.permissions } catch { }
    try { &$addItems $Manifest.host_permissions } catch { }
    try { &$addItems $Manifest.optional_permissions } catch { }
    try { &$addItems $Manifest.optional_host_permissions } catch { }
    try {
        if ($Manifest.content_scripts) {
            foreach ($cs in @($Manifest.content_scripts)) {
                try { &$addItems $cs.matches } catch { }
            }
        }
    } catch { }
    try {
        if ($Manifest.externally_connectable -and $Manifest.externally_connectable.matches) {
            &$addItems $Manifest.externally_connectable.matches
        }
    } catch { }
    return ($collected -join ", ")
}

function Scan-ChromiumExtensions {
    param(
        [string]$BrowserName,
        [string]$ExtensionsDir
    )
    $entries = @()
    if (-not (Test-Path $ExtensionsDir)) { return $entries }

    $profileDir = Split-Path $ExtensionsDir -Parent

    Get-ChildItem $ExtensionsDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $extId = $_.Name
        # Pick newest version directory: consistent padded string for reliable descending sort
        $vd = Get-ChildItem $_.FullName -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -ne 'metadata' -and -not $_.Name.StartsWith('.') -and $_.Name -ne 'Temp' } |
            Sort-Object {
                $n = $_.Name
                try {
                    $v = [version]$n
                    $parts = @($v.Major, $v.Minor, $v.Build, $v.Revision) | Where-Object { $_ -ge 0 }
                    ($parts | ForEach-Object { "{0:D8}" -f $_ }) -join '.'
                } catch {
                    $parts = $n -split '\.'
                    ($parts | ForEach-Object {
                        $numStr = $_ -replace '[^0-9]',''
                        $num = 0
                        try { $num = [int]$numStr } catch { $num = 0 }
                        "{0:D8}" -f $num
                    }) -join '.'
                }
            } -Descending |
            Select-Object -First 1
        if ($vd) {
            $mp = Join-Path (Join-Path $_.FullName $vd.Name) "manifest.json"
            if (Test-Path $mp) {
                try {
                    $m = Get-Content $mp -Raw | ConvertFrom-Json
                    $rawName = if ($m.name) { $m.name } else { "Unknown" }
                    $resolvedName = $rawName
                    $rawDesc = if ($m.description) { $m.description } else { "" }
                    $resolvedDesc = $rawDesc
                    if ($rawName -match '^__MSG_(.+)__$') {
                        $key = $matches[1]
                        $defLoc = if ($m.default_locale) { $m.default_locale } else { "en" }
                        $resolved = Resolve-LocaleMessage -VersionDir $vd.FullName -Key $key -DefaultLocale $defLoc
                        if ($resolved) { $resolvedName = $resolved } else {
                            [Console]::Error.WriteLine("Failed to resolve locale message for ${key} (tried locales $defLoc, en)")
                        }
                    }
                    if ($rawDesc -match '^__MSG_(.+)__$') {
                        $key = $matches[1]
                        $defLoc = if ($m.default_locale) { $m.default_locale } else { "en" }
                        $resolved = Resolve-LocaleMessage -VersionDir $vd.FullName -Key $key -DefaultLocale $defLoc
                        if ($resolved) { $resolvedDesc = $resolved } else {
                            [Console]::Error.WriteLine("Failed to resolve locale desc for ${key}")
                        }
                    }

                    $enabled = Get-ChromiumExtState -ProfileDir $profileDir -ExtensionId $extId
                    if ($null -eq $enabled) { $enabled = $true }
                    $profName = ""
                    try { $profName = Split-Path $profileDir -Leaf } catch { $profName = "" }

                    $entries += [PSCustomObject]@{
                        id = $extId
                        name = $resolvedName
                        version = if ($m.version) { $m.version } else { "" }
                        description = $resolvedDesc
                        enabled = $enabled
                        browser = $BrowserName
                        path = $ExtensionsDir
                        profilePath = $profileDir
                        profileName = $profName
                        installTime = $_.CreationTime.ToString("yyyy-MM-dd HH:mm:ss")
                        permissions = Get-ChromiumManifestPermissions -Manifest $m
                    }
                } catch {
                    [Console]::Error.WriteLine("Failed to parse Chromium manifest for ${extId}: $($_.Exception.Message)")
                }
            }
        }
    }
    return $entries
}

function Scan-ChromiumProfileBrowser {
    param(
        [string]$BrowserName,
        [string]$UserDataPath
    )
    $entries = @()
    if (-not (Test-Path $UserDataPath)) { return $entries }
    Get-ChildItem "$UserDataPath\*\Extensions" -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $entries += Scan-ChromiumExtensions -BrowserName $BrowserName -ExtensionsDir $_.FullName
    }
    return $entries
}

function Scan-ChromiumSingleProfileBrowser {
    param(
        [string]$BrowserName,
        [string]$ExtensionsPath
    )
    $entries = @()
    if (-not (Test-Path $ExtensionsPath)) { return $entries }
    $entries = Scan-ChromiumExtensions -BrowserName $BrowserName -ExtensionsDir $ExtensionsPath
    return $entries
}

function Test-FileLock {
    param([string]$Path)
    # Best-effort only: Chromium/Firefox typically hold Preferences with shared read access,
    # so an exclusive-open probe often succeeds even while the browser is running.
    # Callers must ALSO check the running-process list and treat "browser running" as a
    # hard block (close + retry). A passed lock check alone does NOT prove it is safe to write.
    # Returns true if file is locked (browser running)
    try {
        $fs = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        $fs.Close()
        $fs.Dispose()
        return $false
    } catch {
        return $true
    }
}

function Toggle-ChromiumExtension {
    param(
        [string]$ProfileDir,
        [string]$ExtensionId,
        [bool]$Enable
    )
    $secureFile = Join-Path $ProfileDir "Secure Preferences"
    $prefsFile = Join-Path $ProfileDir "Preferences"
    $candidateFiles = @()
    if (Test-Path $secureFile) { $candidateFiles += $secureFile }
    if (Test-Path $prefsFile) { $candidateFiles += $prefsFile }
    if ($candidateFiles.Count -eq 0) {
        [Console]::Error.WriteLine("Preferences file not found: $prefsFile and $secureFile")
        return $false
    }

    $anyUpdated = $false
    $overallSuccess = $true

    foreach ($targetFile in $candidateFiles) {
        # Check lock for each file
        $lockedRetries = 3
        $isLocked = $false
        for ($i = 0; $i -lt $lockedRetries; $i++) {
            if (-not (Test-FileLock -Path $targetFile)) { $isLocked = $false; break }
            $isLocked = $true
            if ($i -eq $lockedRetries - 1) {
                [Console]::Error.WriteLine("File is locked (browser may be running): $targetFile")
                $overallSuccess = $false
                continue
            }
            Start-Sleep -Milliseconds 400
        }
        if ($isLocked) { continue }

        try {
            $raw = Get-Content -LiteralPath $targetFile -Raw -ErrorAction Stop
            $prefs = $raw | ConvertFrom-Json -ErrorAction Stop
            if (-not ($prefs.extensions -and $prefs.extensions.settings)) {
                # No settings in this file, skip but not fail
                continue
            }
            $extProp = $prefs.extensions.settings.PSObject.Properties[$ExtensionId]
            if (-not $extProp -or -not $extProp.Value) {
                # Extension not in this file, try next file
                continue
            }
            $extSettings = $extProp.Value
            $newState = 0
            if ($Enable) { $newState = 1 }
            if ($extSettings.PSObject.Properties['state']) {
                $extSettings.state = $newState
            } else {
                $extSettings | Add-Member -NotePropertyName state -NotePropertyValue $newState -Force
            }

            # Handle disable_reasons: array (Chrome) vs integer (Edge)
            $hasDR = $extSettings.PSObject.Properties['disable_reasons']
            if ($Enable) {
                if ($hasDR) {
                    $dr = $extSettings.disable_reasons
                    if ($dr -is [Array] -or $dr -is [System.Collections.IList]) {
                        $new = @($dr | Where-Object { $_ -ne 1 -and "$_" -ne "1" })
                        $extSettings.disable_reasons = @($new)
                    } else {
                        try { $cur = [int]$dr } catch { $cur = 0 }
                        $extSettings.disable_reasons = ($cur -band (-bnot 1))
                    }
                } else {
                    # No disable_reasons present and we are enabling: nothing to add (remains enabled)
                    # Keep file clean - don't add unnecessary property
                }
            } else {
                if ($hasDR) {
                    $dr = $extSettings.disable_reasons
                    if ($dr -is [Array] -or $dr -is [System.Collections.IList]) {
                        $list = @($dr)
                        $found = $false
                        foreach ($v in $list) { if ("$v" -eq "1") { $found = $true; break } }
                        if (-not $found) { $list += 1 }
                        $extSettings.disable_reasons = @($list | Select-Object -Unique)
                    } else {
                        try { $cur = [int]$dr } catch { $cur = 0 }
                        $extSettings.disable_reasons = ($cur -bor 1)
                    }
                } else {
                    # Add disable_reasons with type matching majority in this file
                    $arrayCount = 0; $intCount = 0
                    try {
                        foreach ($pp in $prefs.extensions.settings.PSObject.Properties) {
                            $dp = $pp.Value.PSObject.Properties['disable_reasons']
                            if ($dp) {
                                if ($dp.Value -is [Array]) { $arrayCount++ } else { $intCount++ }
                            }
                        }
                    } catch { }
                    $useArray = $arrayCount -gt $intCount
                    if ($useArray) { $extSettings | Add-Member -NotePropertyName disable_reasons -NotePropertyValue @(1) -Force }
                    else { $extSettings | Add-Member -NotePropertyName disable_reasons -NotePropertyValue 1 -Force }
                }
            }

            # For Secure Preferences, remove MAC to avoid HMAC mismatch; for Preferences, no MAC handling
            $isSecure = $targetFile -like "*Secure Preferences"
            if ($isSecure) {
                try {
                    if ($prefs.protection -and $prefs.protection.macs -and $prefs.protection.macs.extensions -and $prefs.protection.macs.extensions.settings) {
                        $macs = $prefs.protection.macs.extensions.settings
                        if ($macs.PSObject.Properties[$ExtensionId]) {
                            $null = $macs.PSObject.Properties.Remove($ExtensionId)
                        }
                    }
                    # Also clear protection for super_mac if present (some versions)
                    # Keep other macs intact
                } catch { }
            }

            $json = $prefs | ConvertTo-Json -Depth 100 -Compress
            $bakFile = "$targetFile.bak.$([DateTime]::Now.ToString('yyyyMMdd-HHmmss-fff'))"
            try { Copy-Item -LiteralPath $targetFile -Destination $bakFile -Force -ErrorAction SilentlyContinue } catch { }
            $tmpFile = "$targetFile.tmp"
            $utf8NoBom = New-Object System.Text.UTF8Encoding $false
            try {
                [System.IO.File]::WriteAllText($tmpFile, $json, $utf8NoBom)
                Move-Item -LiteralPath $tmpFile -Destination $targetFile -Force
                # Integrity check: written file must still parse as JSON and retain our edit
                try {
                    $verifyRaw = Get-Content -LiteralPath $targetFile -Raw -ErrorAction Stop
                    $verifyPrefs = $verifyRaw | ConvertFrom-Json -ErrorAction Stop
                    $verifyProp = $verifyPrefs.extensions.settings.PSObject.Properties[$ExtensionId]
                    if (-not $verifyProp -or -not $verifyProp.Value) { throw "extension entry missing after write" }
                    $verifyState = $null
                    if ($verifyProp.Value.PSObject.Properties['state']) { $verifyState = $verifyProp.Value.state }
                    if ($null -ne $verifyState -and ([string]$verifyState -ne [string]$newState)) {
                        throw "state mismatch after write (expected $newState, got $verifyState)"
                    }
                } catch {
                    [Console]::Error.WriteLine("Write verification failed for $targetFile : $($_.Exception.Message). Restoring backup.")
                    try {
                        if (Test-Path $bakFile) { Copy-Item -LiteralPath $bakFile -Destination $targetFile -Force }
                    } catch { }
                    throw
                }
                try {
                    $filter = if ($isSecure) { "Secure Preferences.bak.*" } else { "Preferences.bak.*" }
                    Get-ChildItem (Split-Path $targetFile -Parent) -File -Filter $filter -ErrorAction SilentlyContinue |
                        Sort-Object LastWriteTime -Descending | Select-Object -Skip 3 | Remove-Item -Force -ErrorAction SilentlyContinue
                } catch { }
            } catch {
                [Console]::Error.WriteLine("Atomic write failed for $targetFile, attempting direct write: $($_.Exception.Message)")
                try { [System.IO.File]::WriteAllText($targetFile, $json, $utf8NoBom) } catch { throw }
            } finally {
                try { Remove-Item -LiteralPath $tmpFile -Force -ErrorAction SilentlyContinue } catch { }
            }
            $anyUpdated = $true
        } catch {
            [Console]::Error.WriteLine("Failed to toggle in $targetFile : $($_.Exception.Message)")
            $overallSuccess = $false
        }
    }

    if (-not $anyUpdated) {
        # Extension not found in any file - try to report detailed
        [Console]::Error.WriteLine("Extension $ExtensionId not found in Secure Preferences nor Preferences")
        return $false
    }
    if (-not $overallSuccess) {
        # Partial failure (e.g. one file locked, one written) must NOT be reported as success:
        # Chromium would see inconsistent Preferences vs Secure Preferences and revert.
        [Console]::Error.WriteLine("Partial toggle failure for $ExtensionId (some profile files could not be updated). Close the browser and retry.")
        return $false
    }
    # Verify-after-write: re-read effective state; Chrome may have rewritten the file concurrently.
    try {
        $verified = Get-ChromiumExtState -ProfileDir $ProfileDir -ExtensionId $ExtensionId
        if ($null -ne $verified -and $verified -ne $Enable) {
            [Console]::Error.WriteLine("Toggle verification failed for $ExtensionId (expected enabled=$Enable, read enabled=$verified). Browser may have overwritten the change; close it and retry.")
            return $false
        }
    } catch { }
    return $true
}

function Get-FirefoxAddonPermissions {
    param([object]$Addon)
    $collected = @()
    $seen = @{}
    $addItems = {
        param([object]$Items)
        if ($null -eq $Items) { return }
        foreach ($it in @($Items)) {
            if ($null -eq $it) { continue }
            $s = ""
            try {
                if ($it -is [string]) { $s = $it }
                elseif ($it.PSObject -and $it.PSObject.Properties['origin']) { $s = [string]$it.origin }
                elseif ($it.PSObject -and $it.PSObject.Properties['pattern']) { $s = [string]$it.pattern }
                else { $s = "$it" }
            } catch { continue }
            $s = $s.Trim()
            if ($s -eq "" -or $seen.ContainsKey($s)) { continue }
            $seen[$s] = $true
            $collected += $s
        }
    }
    try { &$addItems $Addon.permissions } catch { }
    try { &$addItems $Addon.origins } catch { }
    try { &$addItems $Addon.hostPermissions } catch { }
    try { &$addItems $Addon.optionalPermissions } catch { }
    try {
        if ($Addon.userPermissions) {
            &$addItems $Addon.userPermissions.permissions
            &$addItems $Addon.userPermissions.origins
        }
    } catch { }
    return ($collected -join ", ")
}

function Scan-FirefoxExtensions {
    param(
        [string]$ProfileDir,
        [string]$BrowserName = "Firefox"
    )
    $entries = @()
    $extJson = Join-Path $ProfileDir "extensions.json"
    if (-not (Test-Path $extJson)) { return $entries }
    try {
        $json = Get-Content $extJson -Raw | ConvertFrom-Json
        $addons = $json.addons
        if ($null -eq $addons) {
            [Console]::Error.WriteLine("No addons array found in extensions.json for profile: $ProfileDir")
            return $entries
        }
        $extensionsPath = Join-Path $ProfileDir "extensions"
        foreach ($addon in $addons) {
            $addonId = $addon.id
            if (-not $addonId) { continue }
            $addonId = $addonId -replace $illegalFilenameChars, '_'
            $isDisabled = $false
            if ($null -ne $addon.disabled) { $isDisabled = [bool]$addon.disabled }
            $isInstalled = $true
            if ($null -ne $addon.appDisabled) { $isInstalled = -not [bool]$addon.appDisabled }
            # Also respect userDisabled / softDisabled if present
            if ($null -ne $addon.userDisabled -and $addon.userDisabled) { $isDisabled = $true }
            if ($null -ne $addon.softDisabled -and $addon.softDisabled) { $isDisabled = $true }
            if ($null -ne $addon.embedderDisabled -and $addon.embedderDisabled) { $isDisabled = $true }

            $ffProfName = ""
            try { $ffProfName = Split-Path $ProfileDir -Leaf } catch { $ffProfName = "" }
            $entries += [PSCustomObject]@{
                id = $addonId
                name = if ($addon.defaultLocale -and $addon.defaultLocale.name) { $addon.defaultLocale.name } else { if ($addon.name) { $addon.name } else { $addonId } }
                version = if ($addon.version) { $addon.version } else { "" }
                description = if ($addon.defaultLocale -and $addon.defaultLocale.description) { $addon.defaultLocale.description } else { if ($addon.description) { $addon.description } else { "" } }
                enabled = (-not $isDisabled) -and $isInstalled
                browser = $BrowserName
                path = $extensionsPath
                profilePath = $ProfileDir
                profileName = $ffProfName
                installTime = if ($addon.installDate) {
                    try { [DateTimeOffset]::FromUnixTimeMilliseconds([long]$addon.installDate).ToString("yyyy-MM-dd HH:mm:ss") } catch { "$($addon.installDate)" }
                } else { "" }
                permissions = Get-FirefoxAddonPermissions -Addon $addon
            }
        }
    } catch {
        [Console]::Error.WriteLine("Failed to parse Firefox extensions.json for profile: $($_.Exception.Message)")
    }
    return $entries
}

function Toggle-FirefoxExtension {
    param(
        [string]$ProfileDir,
        [string]$ExtensionId,
        [bool]$Enable
    )
    $extJson = Join-Path $ProfileDir "extensions.json"
    if (-not (Test-Path $extJson)) {
        [Console]::Error.WriteLine("extensions.json not found: $extJson")
        return $false
    }
    # Check for file lock (Firefox running)
    $lockedRetries = 3
    for ($i = 0; $i -lt $lockedRetries; $i++) {
        if (-not (Test-FileLock -Path $extJson)) { break }
        if ($i -eq $lockedRetries - 1) {
            [Console]::Error.WriteLine("extensions.json is locked (Firefox may be running): $extJson")
            return $false
        }
        Start-Sleep -Milliseconds 400
    }
    try {
        $raw = Get-Content $extJson -Raw
        $json = $raw | ConvertFrom-Json
        $addons = $json.addons
        if ($null -eq $addons) {
            [Console]::Error.WriteLine("No addons array in extensions.json")
            return $false
        }
        $found = $false
        foreach ($addon in $addons) {
            if ($addon.id -and ($addon.id -replace $illegalFilenameChars, '_') -eq $ExtensionId) {
                if ($Enable) {
                    $addon.disabled = $false
                    $addon.appDisabled = $false
                    if ($null -ne $addon.userDisabled) { $addon.userDisabled = $false }
                    if ($null -ne $addon.softDisabled) { $addon.softDisabled = $false }
                    if ($null -ne $addon.embedderDisabled) { $addon.embedderDisabled = $false }
                    if ($addon.PSObject.Properties['visible']) { $addon.visible = $true }
                    if ($addon.PSObject.Properties['active']) { $addon.active = $true }
                } else {
                    $addon.disabled = $true
                    if ($null -ne $addon.userDisabled) { $addon.userDisabled = $true }
                    if ($addon.PSObject.Properties['active']) { $addon.active = $false }
                }
                $found = $true
                break
            }
        }
        if (-not $found) {
            [Console]::Error.WriteLine("Extension $ExtensionId not found in extensions.json")
            return $false
        }
        $jsonText = $json | ConvertTo-Json -Depth 100 -Compress
        # Atomic write with backup
        $bakFile = "$extJson.bak.$([DateTime]::Now.ToString('yyyyMMdd-HHmmss-fff'))"
        try { Copy-Item -LiteralPath $extJson -Destination $bakFile -Force -ErrorAction SilentlyContinue } catch { }
        $tmpFile = "$extJson.tmp"
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        try {
            [System.IO.File]::WriteAllText($tmpFile, $jsonText, $utf8NoBom)
            Move-Item -LiteralPath $tmpFile -Destination $extJson -Force
            try {
                Get-ChildItem (Split-Path $extJson -Parent) -File -Filter "extensions.json.bak.*" -ErrorAction SilentlyContinue |
                    Sort-Object LastWriteTime -Descending | Select-Object -Skip 3 | Remove-Item -Force -ErrorAction SilentlyContinue
            } catch { }
        } catch {
            [Console]::Error.WriteLine("Atomic write failed, attempting direct write: $($_.Exception.Message)")
            try { [System.IO.File]::WriteAllText($extJson, $jsonText, $utf8NoBom) } catch { throw }
        } finally {
            try { Remove-Item -LiteralPath $tmpFile -Force -ErrorAction SilentlyContinue } catch { }
        }
        # Verify-after-write: re-read and confirm flags match the request.
        # Without this, Firefox may silently overwrite extensions.json from its
        # startup cache on next launch and the UI would report false success.
        try {
            $verifyJson = Get-Content -LiteralPath $extJson -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
            $verifyFound = $false
            foreach ($va in @($verifyJson.addons)) {
                if ($va.id -and ($va.id -replace $illegalFilenameChars, '_') -eq $ExtensionId) {
                    $verifyFound = $true
                    $vDisabled = $false
                    if ($null -ne $va.disabled) { $vDisabled = [bool]$va.disabled }
                    if ($null -ne $va.userDisabled -and $va.userDisabled) { $vDisabled = $true }
                    if ($Enable -and $vDisabled) {
                        [Console]::Error.WriteLine("Firefox toggle verification failed: extension still disabled after write. Close Firefox and retry.")
                        return $false
                    }
                    if ((-not $Enable) -and (-not $vDisabled)) {
                        [Console]::Error.WriteLine("Firefox toggle verification failed: extension still enabled after write. Close Firefox and retry.")
                        return $false
                    }
                    break
                }
            }
            if (-not $verifyFound) {
                [Console]::Error.WriteLine("Firefox toggle verification failed: extension missing after write.")
                return $false
            }
        } catch {
            [Console]::Error.WriteLine("Firefox toggle verification failed: $($_.Exception.Message)")
            return $false
        }
        # Invalidate Firefox startup cache so it rebuilds from the edited
        # extensions.json on next launch instead of restoring the old state.
        # Best-effort only; failures here do not fail the toggle.
        try {
            $cacheLz4 = Join-Path $ProfileDir "addonStartup.json.lz4"
            if (Test-Path -LiteralPath $cacheLz4) { Remove-Item -LiteralPath $cacheLz4 -Force -ErrorAction SilentlyContinue }
            $cacheJson = Join-Path $ProfileDir "addonStartup.json"
            if (Test-Path -LiteralPath $cacheJson) { Remove-Item -LiteralPath $cacheJson -Force -ErrorAction SilentlyContinue }
        } catch { }
        return $true
    } catch {
        [Console]::Error.WriteLine("Failed to toggle Firefox extension: $($_.Exception.Message)")
        return $false
    }
}

# --- Toggle Action ---

if ($Action -eq "Toggle") {
    if ([string]::IsNullOrEmpty($ProfilePath) -or [string]::IsNullOrEmpty($ExtId) -or [string]::IsNullOrEmpty($Enable)) {
        [Console]::Error.WriteLine("Toggle requires -ProfilePath, -ExtId, and -Enable parameters")
        exit 1
    }
    $enableFlag = [bool]::Parse($Enable)
    # Detect engine by profile content first (handles custom Firefox profile paths
    # outside ...\Profiles via profiles.ini IsRelative=0), fall back to path prefix.
    $extJsonProbe = Join-Path $ProfilePath "extensions.json"
    $secureProbe = Join-Path $ProfilePath "Secure Preferences"
    $prefsProbe = Join-Path $ProfilePath "Preferences"
    $isFirefox = $false
    try {
        if (Test-Path -LiteralPath $extJsonProbe) { $isFirefox = $true }
        elseif ((Test-Path -LiteralPath $secureProbe) -or (Test-Path -LiteralPath $prefsProbe)) { $isFirefox = $false }
        else {
            $ffProfiles = "$env:APPDATA\Mozilla\Firefox\Profiles"
            $isFirefox = $ProfilePath.StartsWith($ffProfiles, [StringComparison]::OrdinalIgnoreCase)
        }
    } catch {
        $ffProfiles = "$env:APPDATA\Mozilla\Firefox\Profiles"
        $isFirefox = $ProfilePath.StartsWith($ffProfiles, [StringComparison]::OrdinalIgnoreCase)
    }
    if ($isFirefox) {
        $ok = Toggle-FirefoxExtension -ProfileDir $ProfilePath -ExtensionId $ExtId -Enable $enableFlag
        if ($ok) { Write-Output "true" } else { Write-Output "false" }
    } else {
        $ok = Toggle-ChromiumExtension -ProfileDir $ProfilePath -ExtensionId $ExtId -Enable $enableFlag
        if ($ok) { Write-Output "true" } else { Write-Output "false" }
    }
    exit 0
}

# --- Scan Action ---

# --- Chromium-based browsers with multi-profile support ---

$chromeProfiles = "$env:LOCALAPPDATA\Google\Chrome\User Data"
$chromeCanaryProfiles = "$env:LOCALAPPDATA\Google\Chrome SxS\User Data"
$edgeProfiles = "$env:LOCALAPPDATA\Microsoft\Edge\User Data"
$edgeBetaProfiles = "$env:LOCALAPPDATA\Microsoft\Edge Beta\User Data"
$edgeDevProfiles = "$env:LOCALAPPDATA\Microsoft\Edge Dev\User Data"
$edgeCanaryProfiles = "$env:LOCALAPPDATA\Microsoft\Edge SxS\User Data"
$braveProfiles = "$env:LOCALAPPDATA\BraveSoftware\Brave-Browser\User Data"
$vivaldiProfiles = "$env:LOCALAPPDATA\Vivaldi\User Data"

$chromiumMultiProfileBrowsers = @(
    @{ Name = "Chrome";       Path = $chromeProfiles },
    @{ Name = "Chrome Canary"; Path = $chromeCanaryProfiles },
    @{ Name = "Edge";         Path = $edgeProfiles },
    @{ Name = "Edge Beta";    Path = $edgeBetaProfiles },
    @{ Name = "Edge Dev";     Path = $edgeDevProfiles },
    @{ Name = "Edge Canary";  Path = $edgeCanaryProfiles },
    @{ Name = "Brave";        Path = $braveProfiles },
    @{ Name = "Vivaldi";      Path = $vivaldiProfiles }
)

foreach ($b in $chromiumMultiProfileBrowsers) {
    if ($Browser -eq "All" -or $Browser -eq $b.Name) {
        $result += Scan-ChromiumProfileBrowser -BrowserName $b.Name -UserDataPath $b.Path
    }
}

# --- Chromium-based browsers with single profile (Opera, Opera GX) ---

$operaPath = "$env:APPDATA\Opera Software\Opera Stable\Extensions"
$operaGxPath = "$env:APPDATA\Opera Software\Opera GX Stable\Extensions"

if ($Browser -eq "All" -or $Browser -eq "Opera") {
    $result += Scan-ChromiumSingleProfileBrowser -BrowserName "Opera" -ExtensionsPath $operaPath
}

if ($Browser -eq "All" -or $Browser -eq "Opera GX") {
    $result += Scan-ChromiumSingleProfileBrowser -BrowserName "Opera GX" -ExtensionsPath $operaGxPath
}

# --- Firefox ---
# Resolves profile dirs from profiles.ini (covers IsRelative=0 custom locations)
# plus the legacy Profiles\* fallback. Dedupes existing directories only.

function Get-FirefoxProfileDirs {
    $dirs = New-Object System.Collections.ArrayList
    $seen = @{}
    $ffBase = Join-Path $env:APPDATA "Mozilla\Firefox"
    $candidates = New-Object System.Collections.ArrayList
    $ini = Join-Path $ffBase "profiles.ini"
    if (Test-Path -LiteralPath $ini) {
        try {
            $curPath = $null
            $curRelative = "1"
            foreach ($line in (Get-Content -LiteralPath $ini -ErrorAction Stop)) {
                $t = $line.Trim()
                if ($t.StartsWith("[") -and $t.EndsWith("]")) {
                    if ($null -ne $curPath) {
                        if ($curRelative -eq "0") { [void]$candidates.Add($curPath) }
                        else { [void]$candidates.Add((Join-Path $ffBase $curPath)) }
                    }
                    $curPath = $null
                    $curRelative = "1"
                } elseif ($t -match '^(?i)Path\s*=\s*(.+)$') {
                    $curPath = $matches[1].Trim()
                } elseif ($t -match '^(?i)IsRelative\s*=\s*([01])') {
                    $curRelative = $matches[1]
                }
            }
            if ($null -ne $curPath) {
                if ($curRelative -eq "0") { [void]$candidates.Add($curPath) }
                else { [void]$candidates.Add((Join-Path $ffBase $curPath)) }
            }
        } catch { }
    }
    $ffProfiles = Join-Path $ffBase "Profiles"
    if (Test-Path -LiteralPath $ffProfiles) {
        try {
            Get-ChildItem -LiteralPath $ffProfiles -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                [void]$candidates.Add($_.FullName)
            }
        } catch { }
    }
    foreach ($c in $candidates) {
        if ([string]::IsNullOrWhiteSpace($c)) { continue }
        try { $full = [System.IO.Path]::GetFullPath($c) } catch { continue }
        $key = $full.ToLowerInvariant()
        if ($seen.ContainsKey($key)) { continue }
        $seen[$key] = $true
        if (Test-Path -LiteralPath $full -PathType Container) { [void]$dirs.Add($full) }
    }
    return @($dirs)
}

if ($Browser -eq "All" -or $Browser -eq "Firefox") {
    foreach ($profDir in (Get-FirefoxProfileDirs)) {
        try {
            $result += Scan-FirefoxExtensions -ProfileDir $profDir -BrowserName "Firefox"
        } catch { }
    }
}

# --- Pluggable override: explicit UserDataPath from browser-catalog.json ---
# Additive only: when Java passes -UserDataPath (custom/extra browser not
# hardcoded above), scan it with the requested engine. Built-in browsers keep
# using the hardcoded paths above so existing behavior is unchanged.
if (-not [string]::IsNullOrWhiteSpace($UserDataPath)) {
    $customLabel = $BrowserLabel
    if ([string]::IsNullOrWhiteSpace($customLabel)) { $customLabel = $Browser }
    $customEngine = "$Engine".Trim().ToLowerInvariant()
    try {
        # Expand %ENV% tokens Java may have forwarded verbatim.
        $expandedCustom = $UserDataPath
        foreach ($ev in @("LOCALAPPDATA", "APPDATA", "ProgramFiles", "USERPROFILE")) {
            try {
                $val = [Environment]::GetEnvironmentVariable($ev)
                if ($val) { $expandedCustom = $expandedCustom.Replace("%$ev%", $val) }
            } catch { }
        }
        try {
            $pf86 = ${env:ProgramFiles(x86)}
            if ($pf86) { $expandedCustom = $expandedCustom.Replace("%ProgramFiles(x86)%", $pf86) }
        } catch { }
        if ($customEngine -eq "firefox") {
            if (Test-Path -LiteralPath $expandedCustom -PathType Container) {
                # UserData may point at Profiles dir or a single profile dir.
                if (Test-Path -LiteralPath (Join-Path $expandedCustom "extensions.json")) {
                    $result += Scan-FirefoxExtensions -ProfileDir $expandedCustom -BrowserName $customLabel
                } else {
                    Get-ChildItem -LiteralPath $expandedCustom -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                        $ej = Join-Path $_.FullName "extensions.json"
                        if (Test-Path -LiteralPath $ej) {
                            try { $result += Scan-FirefoxExtensions -ProfileDir $_.FullName -BrowserName $customLabel } catch { }
                        }
                    }
                }
            }
        } elseif ($customEngine -eq "chromium-single") {
            $extDir = Join-Path $expandedCustom "Extensions"
            # userData may already be the profile dir (contains Extensions) or the Extensions dir itself.
            if ($expandedCustom -like "*\Extensions" -and (Test-Path -LiteralPath $expandedCustom -PathType Container)) {
                $extDir = $expandedCustom
            }
            $result += Scan-ChromiumSingleProfileBrowser -BrowserName $customLabel -ExtensionsPath $extDir
        } else {
            # Default: chromium-multi (User Data with *\Extensions per profile).
            if (Test-Path -LiteralPath $expandedCustom -PathType Container) {
                $result += Scan-ChromiumProfileBrowser -BrowserName $customLabel -UserDataPath $expandedCustom
            }
        }
    } catch {
        [Console]::Error.WriteLine("Custom UserDataPath scan failed for ${customLabel}: $($_.Exception.Message)")
    }
}

if ($result.Count -eq 0) {
    Write-Output "[]"
} else {
    # Use Depth 10 and force array output even for single element (PS 5.1 unwraps 1-element arrays)
    $jsonOut = ConvertTo-Json -Compress -Depth 10 -InputObject @($result)
    if ($result.Count -eq 1 -and $jsonOut.TrimStart().StartsWith("{")) {
        $jsonOut = "[" + $jsonOut + "]"
    }
    Write-Output $jsonOut
}
