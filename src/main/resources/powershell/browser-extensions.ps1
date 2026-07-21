param([string]$Browser = "Chrome")
$OutputEncoding = [System.Text.Encoding]::UTF8
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$result = @()

$illegalFilenameChars = '[\\/:*?"<>|]'

function Scan-ChromiumExtensions {
    param(
        [string]$BrowserName,
        [string]$ExtensionsDir
    )
    $entries = @()
    if (-not (Test-Path $ExtensionsDir)) { return $entries }
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
                    $disabledFile = Join-Path (Join-Path $_.FullName $vd.Name) "Disabled"
                    $entries += [PSCustomObject]@{
                        id = $extId
                        name = $resolvedName
                        version = if ($m.version) { $m.version } else { "" }
                        description = $resolvedDesc
                        enabled = -not (Test-Path $disabledFile)
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
            $extJson = Join-Path $_.FullName "extensions.json"
            if (Test-Path $extJson) {
                try {
                    $json = Get-Content $extJson -Raw | ConvertFrom-Json
                    $addons = $json.addons
                    if ($null -eq $addons) { $addons = $json }
                    $extensionsPath = Join-Path $_.FullName "extensions"
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
                    [Console]::Error.WriteLine("Failed to parse Firefox extensions.json for profile $($_.Name): $($_.Exception.Message)")
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
