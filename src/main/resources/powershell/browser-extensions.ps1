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
    $prefsFile = Join-Path $ProfileDir "Preferences"
    if (-not (Test-Path $prefsFile)) { return $null }
    try {
        $prefs = Get-Content $prefsFile -Raw | ConvertFrom-Json
        if ($prefs.extensions -and $prefs.extensions.settings) {
            $extSettings = $prefs.extensions.settings.PSObject.Properties[$ExtensionId]
            if ($extSettings -and $extSettings.Value) {
                $state = $extSettings.Value.state
                if ($null -ne $state) {
                    return ($state -ne 0)
                }
            }
        }
    } catch { }
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
        $vd = Get-ChildItem $_.FullName -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -ne 'metadata' -and -not $_.Name.StartsWith('.') } |
            Sort-Object { $parts = $_.Name -split '\.'; [int[]]($parts | ForEach-Object { try { [int]$_ } catch { 0 } }) } -Descending |
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
                        $locale = if ($m.default_locale) { $m.default_locale } else { "en" }
                        $msgPath = Join-Path (Join-Path $vd.FullName "_locales") (Join-Path $locale "messages.json")
                        if (Test-Path $msgPath) {
                            try {
                                $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json
                                if ($msgs.$key -and $msgs.$key.message) { $resolvedName = $msgs.$key.message }
                            } catch {
                                [Console]::Error.WriteLine("Failed to resolve locale message for ${key}: $($_.Exception.Message)")
                            }
                        }
                    }
                    if ($rawDesc -match '^__MSG_(.+)__$') {
                        $key = $matches[1]
                        $locale = if ($m.default_locale) { $m.default_locale } else { "en" }
                        $msgPath = Join-Path (Join-Path $vd.FullName "_locales") (Join-Path $locale "messages.json")
                        if (Test-Path $msgPath) {
                            try {
                                $msgs = Get-Content $msgPath -Raw | ConvertFrom-Json
                                if ($msgs.$key -and $msgs.$key.message) { $resolvedDesc = $msgs.$key.message }
                            } catch {
                                [Console]::Error.WriteLine("Failed to resolve locale desc for ${key}: $($_.Exception.Message)")
                            }
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

function Toggle-ChromiumExtension {
    param(
        [string]$ProfileDir,
        [string]$ExtensionId,
        [bool]$Enable
    )
    $prefsFile = Join-Path $ProfileDir "Preferences"
    if (-not (Test-Path $prefsFile)) {
        [Console]::Error.WriteLine("Preferences file not found: $prefsFile")
        return $false
    }
    try {
        $raw = Get-Content $prefsFile -Raw
        $prefs = $raw | ConvertFrom-Json
        if (-not ($prefs.extensions -and $prefs.extensions.settings)) {
            [Console]::Error.WriteLine("No extensions.settings in Preferences")
            return $false
        }
        $extProp = $prefs.extensions.settings.PSObject.Properties[$ExtensionId]
        if (-not $extProp -or -not $extProp.Value) {
            [Console]::Error.WriteLine("Extension $ExtensionId not found in Preferences")
            return $false
        }
        $extSettings = $extProp.Value
        if ($Enable) {
            $extSettings.state = 1
            if ($extSettings.PSObject.Properties['disable_reasons']) {
                $extSettings.disable_reasons = 0
            }
        } else {
            $extSettings.state = 0
            $extSettings.disable_reasons = 1
        }
        $json = $prefs | ConvertTo-Json -Depth 100 -Compress
        [System.IO.File]::WriteAllText($prefsFile, $json, [System.Text.Encoding]::UTF8)
        return $true
    } catch {
        [Console]::Error.WriteLine("Failed to toggle Chromium extension: $($_.Exception.Message)")
        return $false
    }
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
        if ($null -eq $addons) { $addons = $json }
        $extensionsPath = Join-Path $ProfileDir "extensions"
        $addons | ForEach-Object {
            $addon = $_
            $addonId = $addon.id
            if (-not $addonId) { return }
            $addonId = $addonId -replace $illegalFilenameChars, '_'
            $isDisabled = $false
            if ($addon.disabled) { $isDisabled = $addon.disabled }
            $isInstalled = $true
            if ($addon.appDisabled) { $isInstalled = -not $addon.appDisabled }

            $hasXpi = $false
            $hasJson = $false
            $hasDir = $false
            if (Test-Path $extensionsPath) {
                $hasXpi = Test-Path (Join-Path $extensionsPath "$addonId.xpi")
                $hasJson = Test-Path (Join-Path $extensionsPath "$addonId.json")
                $hasDir = Test-Path (Join-Path $extensionsPath $addonId)
            }

            $result += [PSCustomObject]@{
                id = $addonId
                name = if ($addon.defaultLocale -and $addon.defaultLocale.name) { $addon.defaultLocale.name } else { $addon.name }
                version = if ($addon.version) { $addon.version } else { "" }
                description = if ($addon.defaultLocale -and $addon.defaultLocale.description) { $addon.defaultLocale.description } else { "" }
                enabled = (-not $isDisabled) -and $isInstalled
                browser = "Firefox"
                path = $extensionsPath
                installTime = if ($addon.installDate) { $addon.installDate } else { "" }
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
                } else {
                    $addon.disabled = $true
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
        [System.IO.File]::WriteAllText($extJson, $jsonText, [System.Text.Encoding]::UTF8)
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
    ConvertTo-Json -Compress $result
}
