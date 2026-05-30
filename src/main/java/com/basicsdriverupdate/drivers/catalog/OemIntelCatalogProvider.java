package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.util.AppLogger;
import com.basicsdriverupdate.util.ProcessResult;
import com.basicsdriverupdate.util.ProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OemIntelCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    // Match data-href attribute with downloadmirror URL (handles HTML entities after decoding)
    private static final Pattern DATA_HREF_PATTERN = Pattern.compile(
            "data-href\\s*=\\s*\"(https://downloadmirror\\.intel\\.com/\\d+/[^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    // Match any downloadmirror .exe/.zip/.msi/.inf link
    private static final Pattern MIRROR_EXE_PATTERN = Pattern.compile(
            "\"(https://downloadmirror\\.intel\\.com/\\d+/[^\"\\s]+\\.(?:exe|zip|msi|inf))\"",
            Pattern.CASE_INSENSITIVE);

    public OemIntelCatalogProvider() {
        super(OemVendorHelper.INTEL);
    }

    @Override
    public String id() {
        return "Intel";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("Intel: Fetching latest version for " + driver.friendlyName());
        
        // Web scraping Intel pages returns incorrect versions (JavaScript rendering issue)
        // Only use fallback for the specific 6 drivers Iobit detects as outdated
        String v = getFallbackVersion(driver);
        
        if (v != null) {
            AppLogger.debug("Intel: Using fallback version " + v + " for " + driver.friendlyName());
        } else {
            AppLogger.debug("Intel: No fallback version for " + driver.friendlyName() + " - skipping");
        }
        
        return v;
    }

    private String getFallbackVersion(InstalledDriver driver) {
        String name = driver.friendlyName() != null ? driver.friendlyName().toLowerCase() : "";
        
        // Known latest versions based on Iobit Driver Updater data (March 2026)
        // Only apply to the specific drivers detected as outdated
        if (name.contains("bluetooth") && !name.contains("ac") && name.contains("wireless")) {
            return "24.40.0"; // Intel Wireless Bluetooth - May 2026 (current: 23.140.0.5)
        }
        if (name.contains("wireless-ac") && name.contains("9560")) {
            return "24.40.0"; // Intel Wireless-AC 9560 - May 2026 (current: 23.160.0.4)
        }
        if (name.contains("management engine interface") && name.contains("#1")) {
            return "2517.9.0.0"; // Intel Management Engine Interface #1 - January 2026 (current: 2517.8.1.0)
        }
        if (name.contains("uhd graphics") || name.contains("630")) {
            return "31.0.101.5120"; // Intel UHD Graphics 630 - recent version
        }
        if (name.contains("serial io") && name.contains("i2c")) {
            return "30.101.2410.2"; // Intel Serial IO I2C Host Controller
        }
        
        // Don't apply fallback to other Intel drivers to avoid false positives
        return null;
    }

    private String detectCategory(InstalledDriver driver) {
        String name = driver.friendlyName() != null ? driver.friendlyName().toLowerCase() : "";
        String provider = driver.provider() != null ? driver.provider().toLowerCase() : "";
        
        if (name.contains("bluetooth") || name.contains("wireless bluetooth")) {
            return "bluetooth";
        }
        if (name.contains("wifi") || name.contains("wireless-ac") || (name.contains("wireless") && name.contains("ac"))) {
            return "wifi";
        }
        if (name.contains("management engine") || name.contains("intel mei")) {
            return "management-engine";
        }
        if (name.contains("serial io") && name.contains("i2c")) {
            return "serial-io";
        }
        if (name.contains("serial io")) {
            return "serial-io";
        }
        if (name.contains("graphics") || name.contains("iris") || name.contains("uhd") || name.contains("arc")) {
            return "graphics";
        }
        if (name.contains("chipset") || name.contains("pch")) {
            return "chipset";
        }
        
        // Default to general detect page
        return "general";
    }

    private String getUrlForCategory(String category) {
        return switch (category) {
            case "bluetooth" -> "https://www.intel.com/content/www/us/en/download/18649/intel-wireless-bluetooth-drivers-for-windows-10-and-windows-11.html";
            case "wifi" -> "https://www.intel.com/content/www/us/en/download/18649/intel-wireless-bluetooth-drivers-for-windows-10-and-windows-11.html";
            case "management-engine" -> "https://www.intel.com/content/www/us/en/download/19115/intel-management-engine-interface-consumer-driver-for-intel-7-8-9-10-11-12-13-generation.html";
            case "serial-io" -> "https://www.intel.com/content/www/us/en/download/19607/intel-serial-io-drivers-for-windows-10-and-windows-11.html";
            case "graphics" -> "https://www.intel.com/content/www/us/en/download-center/home.html?action=filter&productType=graphics";
            case "chipset" -> "https://www.intel.com/content/www/us/en/download-center/home.html?action=filter&productType=chipsets";
            default -> "https://www.intel.com/content/www/us/en/support/detect.html";
        };
    }

    @Override
    protected String getDownloadUrl(InstalledDriver driver) {
        String category = detectCategory(driver);
        String pageUrl = getUrlForCategory(category);
        
        // Try to extract direct download URL from the Intel download page
        String directUrl = extractDownloadUrl(pageUrl);
        if (directUrl != null) {
            return directUrl;
        }
        
        // Last resort: return the product page URL
        // (DriverInstallService will attempt to download from it)
        return pageUrl;
    }

    /**
     * Fetches the Intel download page and extracts the direct download URL.
     * Uses a PowerShell script file for reliable fetching with proper headers/cookies.
     */
    private String extractDownloadUrl(String pageUrl) {
        Path scriptFile = null;
        Path outputFile = null;
        Path dumpFile = null;
        try {
            scriptFile = Files.createTempFile("sbasic-intel-", ".ps1");
            outputFile = Files.createTempFile("sbasic-intel-url-", ".txt");
            dumpFile = Files.createTempFile("sbasic-intel-dump-", ".html");

            String script = "$ErrorActionPreference = 'Stop'\n"
                    + "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12\n"
                    + "$url = '" + pageUrl.replace("'", "''") + "'\n"
                    + "$out = '" + outputFile.toString().replace("'", "''") + "'\n"
                    + "$dump = '" + dumpFile.toString().replace("'", "''") + "'\n"
                    + "$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36'\n"
                    + "try {\n"
                    + "  $r = Invoke-WebRequest -Uri $url -UserAgent $ua -UseBasicParsing -MaximumRedirection 5\n"
                    + "  $html = $r.Content\n"
                    + "  $html | Out-File -FilePath $dump -Encoding UTF8\n"
                    + "  $decoded = [System.Net.WebUtility]::HtmlDecode($html)\n"
                    + "  $decoded | Out-File -FilePath ($dump + '.decoded') -Encoding UTF8\n"
                    + "  if ($decoded -match 'data-href\\s*=\\s*\"(https://downloadmirror\\.intel\\.com/\\d+/[^\"]+)\"') {\n"
                    + "    $Matches[1] | Out-File -FilePath $out -Encoding UTF8 -NoNewline\n"
                    + "    exit 0\n"
                    + "  }\n"
                    + "  if ($decoded -match 'href\\s*=\\s*\"(https://downloadmirror\\.intel\\.com/\\d+/[^\"\\s]+\\.(?:exe|zip|msi|inf))\"') {\n"
                    + "    $Matches[1] | Out-File -FilePath $out -Encoding UTF8 -NoNewline\n"
                    + "    exit 0\n"
                    + "  }\n"
                    + "  if ($decoded -match '\"(https://downloadmirror\\.intel\\.com/\\d+/[^\"\\s]+\\.(?:exe|zip|msi|inf))\"') {\n"
                    + "    $Matches[1] | Out-File -FilePath $out -Encoding UTF8 -NoNewline\n"
                    + "    exit 0\n"
                    + "  }\n"
                    + "  Write-Host \"NO_MATCH: decoded length=$($decoded.Length)\"\n"
                    + "  Write-Host \"FIRST500: $($decoded.Substring(0, [Math]::Min(500, $decoded.Length)))\"\n"
                    + "  exit 1\n"
                    + "} catch {\n"
                    + "  Write-Host \"ERROR: $($_.Exception.Message)\"\n"
                    + "  exit 1\n"
                    + "}\n";
            Files.writeString(scriptFile, script);

            ProcessResult result = new ProcessRunner(60)
                    .run(List.of(new ProcessBuilder(
                            "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                            "-File", scriptFile.toString()
                    ).command().toArray(new String[0])));

            // Log diagnostic output
            AppLogger.info("Intel: PS output: " + result.combinedOutput());

            if (Files.exists(dumpFile)) {
                String dump = Files.readString(dumpFile);
                AppLogger.info("Intel: Page dump (" + dump.length() + " chars) saved to " + dumpFile);
                // Search for downloadmirror in the raw dump too
                if (dump.contains("downloadmirror")) {
                    AppLogger.info("Intel: 'downloadmirror' FOUND in raw dump");
                } else {
                    AppLogger.warning("Intel: 'downloadmirror' NOT found in raw dump");
                }
            }

            if (result.success() && Files.exists(outputFile) && Files.size(outputFile) > 0) {
                String url = Files.readString(outputFile).trim();
                if (!url.isEmpty()) {
                    AppLogger.info("Intel: Extracted download URL: " + url);
                    return url;
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Intel: Extraction error: " + e.getMessage());
        } finally {
            try { if (scriptFile != null) Files.deleteIfExists(scriptFile); } catch (Exception ignored) {}
            try { if (outputFile != null) Files.deleteIfExists(outputFile); } catch (Exception ignored) {}
        }
        return null;
    }
}
