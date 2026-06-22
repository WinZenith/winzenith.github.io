param([string]$Browser = "Chrome")
$OutputEncoding = [System.Text.Encoding]::UTF8
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$result = @()

function Scan-ChromiumExtensions {
    param(
        [string]$BrowserName,
        [string]$ExtensionsDir
    )
    $entries = @()
    if (-not (Test-Path $ExtensionsDir)) { return $entries }
    Get-ChildItem $ExtensionsDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
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
