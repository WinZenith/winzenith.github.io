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
            $prefs = Get-Content -LiteralPath $prefsFile -Raw -Encoding UTF8 -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
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
                $msgs = Get-Content -LiteralPath $msgPath -Raw -Encoding UTF8 | ConvertFrom-Json
                if ($msgs.$Key -and $msgs.$Key.message) { return $msgs.$Key.message }
            } catch { }
        }
    }
    return $null
}

function Get-ChromiumManifestPermissions {
    param([object]$Manifest)
    $collected = New-Object System.Collections.ArrayList
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
            [void]$collected.Add($s)
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

function Get-ChromiumPolicyKey {
    param([string]$BrowserName)
    # Registry policy location for ExtensionInstallForcelist per browser.
    # Returns "" when unknown (caller falls back to Preferences markers only).
    switch -Wildcard ($BrowserName) {
        "Chrome*" { return "Google\Chrome" }
        "Edge*"   { return "Microsoft\Edge" }
        "Brave*"  { return "BraveSoftware\Brave" }
        default   { return "" }
    }
}

function Get-ChromiumManagedInfo {
    param(
        [string]$BrowserName,
        [string]$ProfileDir
    )
    # Collects force-installed (policy) ids from HKLM/HKCU ExtensionInstallForcelist
    # plus default-installed markers from Preferences, once per profile scan.
    $forced = @{}
    $defaultInstalled = @{}
    $policyKey = Get-ChromiumPolicyKey -BrowserName $BrowserName
    if ($policyKey -ne "") {
        foreach ($hive in @("HKLM:", "HKCU:")) {
            $key = Join-Path $hive ("SOFTWARE\Policies\" + $policyKey + "\ExtensionInstallForcelist")
            try {
                $props = Get-ItemProperty -LiteralPath $key -ErrorAction Stop
                foreach ($pn in @($props.PSObject.Properties.Name)) {
                    if ($pn -eq "PSPath" -or $pn -eq "PSParentPath" -or $pn -eq "PSChildName" -or $pn -eq "PSDrive" -or $pn -eq "PSProvider") { continue }
                    try {
                        $val = [string]$props.$pn
                        if ([string]::IsNullOrWhiteSpace($val)) { continue }
                        $idPart = ($val -split ';')[0].Trim().ToLowerInvariant()
                        if ($idPart -ne "") { $forced[$idPart] = $true }
                    } catch { }
                }
            } catch { }
        }
    }
    foreach ($prefsFile in @((Join-Path $ProfileDir "Secure Preferences"), (Join-Path $ProfileDir "Preferences"))) {
        if (-not (Test-Path -LiteralPath $prefsFile)) { continue }
        try {
            $prefs = Get-Content -LiteralPath $prefsFile -Raw -Encoding UTF8 -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
            if ($prefs.extensions -and $prefs.extensions.settings) {
                foreach ($pp in @($prefs.extensions.settings.PSObject.Properties)) {
                    try {
                        $v = $pp.Value
                        if ($null -ne $v -and $v.PSObject.Properties['was_installed_by_default'] -and [bool]$v.was_installed_by_default) {
                            $defaultInstalled[$pp.Name.ToLowerInvariant()] = $true
                        }
                    } catch { }
                }
            }
        } catch { }
    }
    return [PSCustomObject]@{ ForcedIds = $forced; DefaultIds = $defaultInstalled }
}

function Scan-ChromiumExtensions {
    param(
        [string]$BrowserName,
        [string]$ExtensionsDir
    )
    $entries = @()
    if (-not (Test-Path $ExtensionsDir)) { return $entries }

    $profileDir = Split-Path $ExtensionsDir -Parent
    $managedInfo = Get-ChromiumManagedInfo -BrowserName $BrowserName -ProfileDir $profileDir

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
                    $m = Get-Content -LiteralPath $mp -Raw -Encoding UTF8 | ConvertFrom-Json
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

                    $profName = ""
                    try { $profName = Split-Path $profileDir -Leaf } catch { $profName = "" }

                    # Policy/default-installed extensions cannot be disabled via
                    # Preferences edits (browser re-enforces on launch), so flag
                    # them instead of letting the UI report false success.
                    $isManaged = $false
                    $installSource = ""
                    try {
                        $idKey = $extId.ToLowerInvariant()
                        if ($managedInfo.ForcedIds.ContainsKey($idKey)) {
                            $isManaged = $true
                            $installSource = "policy"
                        } elseif ($managedInfo.DefaultIds.ContainsKey($idKey)) {
                            $isManaged = $true
                            $installSource = "default"
                        }
                    } catch { }

                    if ($null -eq $enabled) {
                        $enabled = $false
                        if (-not $isManaged) { $installSource = "orphaned" }
                    }

                    $entries += [PSCustomObject]@{
                        id = $extId
                        name = $resolvedName
                        version = if ($m.version) { $m.version } else { "" }
                        description = $resolvedDesc
                        enabled = $enabled
                        managed = $isManaged
                        installSource = $installSource
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

# Toggle lives in Java (BrowserProfileToggle). Scan-only from here.
if ($Action -eq "Toggle") {
    [Console]::Error.WriteLine("Toggle is implemented in Java (BrowserProfileToggle); this script is scan-only.")
    Write-Output "false"
    exit 1
}

function Get-FirefoxAddonPermissions {
    param([object]$Addon)
    $collected = New-Object System.Collections.ArrayList
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
            [void]$collected.Add($s)
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
        $json = Get-Content -LiteralPath $extJson -Raw -Encoding UTF8 | ConvertFrom-Json
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
            # System/built-in add-ons (resource://, distribution-bundled) cannot
            # be disabled via extensions.json edits — flag instead of false success.
            $ffManaged = $false
            $ffSource = ""
            try {
                if (($null -ne $addon.isSystem -and [bool]$addon.isSystem) -or ($null -ne $addon.isBuiltin -and [bool]$addon.isBuiltin)) {
                    $ffManaged = $true
                    $ffSource = "system"
                } else {
                    $rootUri = ""
                    try { if ($null -ne $addon.rootURI) { $rootUri = [string]$addon.rootURI } } catch { }
                    if ($rootUri.StartsWith("resource://", [StringComparison]::OrdinalIgnoreCase) `
                        -or $rootUri.StartsWith("chrome://", [StringComparison]::OrdinalIgnoreCase)) {
                        $ffManaged = $true
                        $ffSource = "system"
                    }
                }
                # appDisabled / softDisabled are Firefox's blocklist or
                # incompatibility flags, not the user's checkbox.
                if (-not $ffManaged) {
                    $browserBlocked = $false
                    if (-not $isInstalled) { $browserBlocked = $true }
                    if ($null -ne $addon.softDisabled -and [bool]$addon.softDisabled) { $browserBlocked = $true }
                    if ($browserBlocked) {
                        $ffManaged = $true
                        $ffSource = "blocked"
                    }
                }
            } catch { }
            $entries += [PSCustomObject]@{
                id = $addonId
                name = if ($addon.defaultLocale -and $addon.defaultLocale.name) { $addon.defaultLocale.name } else { if ($addon.name) { $addon.name } else { $addonId } }
                version = if ($addon.version) { $addon.version } else { "" }
                description = if ($addon.defaultLocale -and $addon.defaultLocale.description) { $addon.defaultLocale.description } else { if ($addon.description) { $addon.description } else { "" } }
                enabled = (-not $isDisabled) -and $isInstalled
                managed = $ffManaged
                installSource = $ffSource
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
            foreach ($line in (Get-Content -LiteralPath $ini -Encoding UTF8 -ErrorAction Stop)) {
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
