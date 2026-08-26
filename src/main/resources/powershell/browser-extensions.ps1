param(
    [string]$Browser = "Chrome",
    [string]$Action = "Scan",
    [string]$ProfilePath = "",
    [string]$ExtId = "",
    [string]$Enable = ""
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

                    $entries += [PSCustomObject]@{
                        id = $extId
                        name = $resolvedName
                        version = if ($m.version) { $m.version } else { "" }
                        description = $resolvedDesc
                        enabled = $enabled
                        browser = $BrowserName
                        path = $ExtensionsDir
                        profilePath = $profileDir
                        installTime = $_.CreationTime.ToString("yyyy-MM-dd HH:mm:ss")
                        permissions = if ($m.permissions) { ($m.permissions -join ", ") } else { "" }
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
            try {
                [System.IO.File]::WriteAllText($tmpFile, $json, [System.Text.Encoding]::UTF8)
                Move-Item -LiteralPath $tmpFile -Destination $targetFile -Force
                try {
                    $filter = if ($isSecure) { "Secure Preferences.bak.*" } else { "Preferences.bak.*" }
                    Get-ChildItem (Split-Path $targetFile -Parent) -File -Filter $filter -ErrorAction SilentlyContinue |
                        Sort-Object LastWriteTime -Descending | Select-Object -Skip 3 | Remove-Item -Force -ErrorAction SilentlyContinue
                } catch { }
            } catch {
                [Console]::Error.WriteLine("Atomic write failed for $targetFile, attempting direct write: $($_.Exception.Message)")
                try { [System.IO.File]::WriteAllText($targetFile, $json, [System.Text.Encoding]::UTF8) } catch { throw }
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
    return $overallSuccess -or $anyUpdated
}

function Scan-FirefoxExtensions {
    param(
        [string]$ProfileDir
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

            $entries += [PSCustomObject]@{
                id = $addonId
                name = if ($addon.defaultLocale -and $addon.defaultLocale.name) { $addon.defaultLocale.name } else { if ($addon.name) { $addon.name } else { $addonId } }
                version = if ($addon.version) { $addon.version } else { "" }
                description = if ($addon.defaultLocale -and $addon.defaultLocale.description) { $addon.defaultLocale.description } else { if ($addon.description) { $addon.description } else { "" } }
                enabled = (-not $isDisabled) -and $isInstalled
                browser = "Firefox"
                path = $extensionsPath
                profilePath = $ProfileDir
                installTime = if ($addon.installDate) {
                    try { [DateTimeOffset]::FromUnixTimeMilliseconds([long]$addon.installDate).ToString("yyyy-MM-dd HH:mm:ss") } catch { "$($addon.installDate)" }
                } else { "" }
                permissions = if ($addon.permissions) { ($addon.permissions -join ", ") } else { "" }
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
                } else {
                    $addon.disabled = $true
                    if ($null -ne $addon.userDisabled) { $addon.userDisabled = $true }
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
        try {
            [System.IO.File]::WriteAllText($tmpFile, $jsonText, [System.Text.Encoding]::UTF8)
            Move-Item -LiteralPath $tmpFile -Destination $extJson -Force
            try {
                Get-ChildItem (Split-Path $extJson -Parent) -File -Filter "extensions.json.bak.*" -ErrorAction SilentlyContinue |
                    Sort-Object LastWriteTime -Descending | Select-Object -Skip 3 | Remove-Item -Force -ErrorAction SilentlyContinue
            } catch { }
        } catch {
            [Console]::Error.WriteLine("Atomic write failed, attempting direct write: $($_.Exception.Message)")
            try { [System.IO.File]::WriteAllText($extJson, $jsonText, [System.Text.Encoding]::UTF8) } catch { throw }
        } finally {
            try { Remove-Item -LiteralPath $tmpFile -Force -ErrorAction SilentlyContinue } catch { }
        }
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
    $ffProfiles = "$env:APPDATA\Mozilla\Firefox\Profiles"
    $isFirefox = $ProfilePath.StartsWith($ffProfiles, [StringComparison]::OrdinalIgnoreCase)
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

if ($Browser -eq "All" -or $Browser -eq "Firefox") {
    $ffProfiles = "$env:APPDATA\Mozilla\Firefox\Profiles"
    if (Test-Path $ffProfiles) {
        Get-ChildItem $ffProfiles -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $result += Scan-FirefoxExtensions -ProfileDir $_.FullName
        }
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
