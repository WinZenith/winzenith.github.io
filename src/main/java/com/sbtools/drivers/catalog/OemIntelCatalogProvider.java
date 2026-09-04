package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.SystemManufacturer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.JsonNode;

public class OemIntelCatalogProvider extends AbstractOemCatalogProvider {

    private static final String DSA_DATA_FEED_URL = "https://dsadata.intel.com/data/en";
    private static final String DSA_DATA_FEED_FALLBACK_URL = "https://dsadata.intel.com/data/en/data.json";
    private static final String DSA_PRODUCT_URL = "https://www.intel.com/content/www/us/en/download/18231/intel-proset-wireless-software-and-drivers-for-it-admins.html";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile JsonNode cachedDsaConfig;
    private volatile long cacheTimestamp;
    private static final long CACHE_TTL_MS = 3600_000;

    private static final Map<String, String> CATEGORY_DSA_NAMES = new HashMap<>();
    private static final Map<String, String> GRAPHICS_DOWNLOAD_PAGES = new HashMap<>();
    static {
        CATEGORY_DSA_NAMES.put("bluetooth", "Bluetooth");
        CATEGORY_DSA_NAMES.put("wifi", "Wi-Fi");
        CATEGORY_DSA_NAMES.put("graphics", "Graphics");
    }

    public OemIntelCatalogProvider() {
        super(OemVendorHelper.INTEL);
    }

    public OemIntelCatalogProvider(DriverCatalogDatabase catalogDatabase) {
        super(OemVendorHelper.INTEL, catalogDatabase);
    }

    @Override
    public String id() {
        return "Intel";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("Intel: Fetching latest version for " + driver.friendlyName());

        String[] info = resolveDriverInfo(driver);
        if (info != null && info[0] != null) {
            AppLogger.info("Intel: Latest version for " + driver.friendlyName() + " is " + info[0]);
            return info[0];
        }

        // No hardcoded/category fallback: offering the first config in a
        // category (or a stale hardcoded version) installs the wrong driver.
        AppLogger.warning("Intel: Could not determine latest version for " + driver.friendlyName());
        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        String category = detectCategory(driver);
        return switch (category) {
            case "bluetooth" -> "https://www.intel.com/content/www/us/en/download/18649/intel-wireless-bluetooth-for-windows-10-and-windows-11.html";
            case "wifi" -> DSA_PRODUCT_URL;
            case "graphics" -> "https://www.intel.com/content/www/us/en/support/products/126790/graphics/processor-graphics/intel-uhd-graphics-family/intel-uhd-graphics-630.html";
            default -> "https://www.intel.com/content/www/us/en/download-center/home.html";
        };
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        AppLogger.info("Intel: Resolving direct download URL for " + driver.friendlyName());

        String[] info = resolveDriverInfo(driver);
        if (info != null && info.length >= 2 && info[1] != null) {
            AppLogger.info("Intel: Resolved direct download URL: " + info[1]);
            return info[1];
        }

        AppLogger.info("Intel: Trying OEM support site fallback");
        return tryOemDownloadFallback(driver);
    }

    private String tryOemDownloadFallback(InstalledDriver driver) {
        SystemManufacturer.Manufacturer mfr = SystemManufacturer.get();
        if (mfr == SystemManufacturer.Manufacturer.GENERIC) {
            AppLogger.debug("Intel: Unknown manufacturer, skipping OEM fallback");
            return null;
        }

        AppLogger.info("Intel: System manufacturer is " + mfr + ", trying OEM support site");
        return tryOemSearchPage(mfr, driver);
    }

    private String tryOemSearchPage(SystemManufacturer.Manufacturer mfr, InstalledDriver driver) {
        String deviceName = driver.friendlyName() != null ? driver.friendlyName() : "";
        String searchQuery = deviceName.replaceAll("[^a-zA-Z0-9 ]", "").trim().replace(" ", "+");
        if (searchQuery.isEmpty()) return null;

        String searchUrl = switch (mfr) {
            case LENOVO -> "https://pcsupport.lenovo.com/us/en/api/v4/downloads/drivers?q=" + searchQuery + "&type=managed";
            case DELL -> "https://www.dell.com/support/driver/en-us/ips/api/driverlist/getdriversbyproduct?productcode=" + searchQuery;
            case HP -> "https://support.hp.com/us-en/driversapi?keyword=" + searchQuery;
            default -> null;
        };

        if (searchUrl == null) return null;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(searchUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "application/json, text/html, */*")
                    .GET()
                    .build();

            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return null;
            }

            String body = resp.body();

            Pattern jsonUrlPattern = Pattern.compile("\"(?:download[Uu]rl|Url|url)\"\\s*:\\s*\"(https?://[^\"]+\\.(?:exe|zip|msi))\"", Pattern.CASE_INSENSITIVE);
            Matcher m = jsonUrlPattern.matcher(body);
            if (m.find()) {
                String url = m.group(1);
                AppLogger.info("Intel: Found download URL in OEM JSON response: " + url);
                return url;
            }

            Pattern hrefPattern = Pattern.compile("href\\s*=\\s*\"(https?://[^\"]+\\.(?:exe|zip|msi))\"", Pattern.CASE_INSENSITIVE);
            m = hrefPattern.matcher(body);
            while (m.find()) {
                String url = m.group(1);
                if (url.contains("intel") || url.contains("gfx") || url.contains("driver") || url.contains(".exe")) {
                    AppLogger.info("Intel: Found download URL in OEM HTML response: " + url);
                    return url;
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Intel: OEM search failed for " + mfr + ": " + e.getMessage());
        }
        return null;
    }

    private String[] resolveDriverInfo(InstalledDriver driver) {
        JsonNode configs = getDsaConfigurations();
        if (configs == null || !configs.isArray()) {
            AppLogger.warning("Intel: DSA configurations not available");
            return null;
        }

        String category = detectCategory(driver);
        String targetCategory = CATEGORY_DSA_NAMES.get(category);
        if (targetCategory == null) {
            AppLogger.debug("Intel: No DSA category mapping for " + category + " (" + driver.friendlyName() + ")");
            return null;
        }

        String[] categoryFallback = null;

        for (JsonNode config : configs) {
            JsonNode components = config.get("Components");
            if (components == null || !components.isArray()) continue;

            for (JsonNode component : components) {
                String compCategory = getTextField(component, "Category");
                if (compCategory == null || !compCategory.equalsIgnoreCase(targetCategory)) continue;

                String version = getTextField(config, "Version");
                if (version == null) {
                    version = getTextField(component, "Version");
                }

                JsonNode detectionValues = component.get("DetectionValues");
                if (detectionValues != null && detectionValues.isArray()) {
                    for (JsonNode dv : detectionValues) {
                        String detectionValue = dv.asText("");
                        if (detectionValue.isEmpty()) continue;

                        if (matchesDriver(driver, detectionValue)) {
                            String downloadUrl = extractDownloadUrl(config);
                            if (downloadUrl != null) {
                                AppLogger.info("Intel: Matched driver via DSA feed: " + detectionValue
                                        + ", version=" + version + ", url=" + downloadUrl);
                                return new String[]{version, downloadUrl};
                            }
                        }
                    }
                }

                // No category fallback: only DetectionValues HW matches may
                // produce a candidate (wrong-device risk otherwise).
            }
        }

        // No fallback — require an exact DetectionValues match.

        AppLogger.debug("Intel: No DSA match found for " + driver.friendlyName());
        return null;
    }

    private boolean matchesDriver(InstalledDriver driver, String detectionValue) {
        if (detectionValue == null || detectionValue.isEmpty()) return false;

        String hwId = driver.hardwareIds() != null ? driver.hardwareIds().toUpperCase() : "";
        String devId = driver.deviceId() != null ? driver.deviceId().toUpperCase() : "";
        String dv = detectionValue.toUpperCase();

        if (hwId.contains(dv) || devId.contains(dv)) {
            return true;
        }

        String devPart = extractDevId(dv);
        if (devPart != null && (hwId.contains(devPart) || devId.contains(devPart))) {
            return true;
        }

        String friendlyName = driver.friendlyName() != null ? driver.friendlyName().toUpperCase() : "";
        if (!friendlyName.isEmpty() && !dv.isEmpty()) {
            if (friendlyName.contains(dv) || dv.contains(friendlyName)) {
                return true;
            }
            String namePart = extractNamePart(friendlyName);
            String dvPart = extractNamePart(dv);
            if (namePart != null && dvPart != null && namePart.equals(dvPart)) {
                return true;
            }
        }

        return false;
    }

    private String extractNamePart(String s) {
        Pattern p = Pattern.compile("(WIRELESS[- ]?AC[- ]?\\d+|BLUETOOTH[- ]?\\d+|UHD[- ]?GRAPHICS[- ]?\\d+|I\\d+G\\d+)");
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1).replaceAll("[\\s-]+", "") : null;
    }

    private String extractDevId(String detectionValue) {
        Pattern p = Pattern.compile("VEN_[0-9A-F]{4}&DEV_([0-9A-F]{4})", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(detectionValue);
        if (m.find()) {
            return "DEV_" + m.group(1);
        }
        return null;
    }

    private String extractDownloadUrl(JsonNode config) {
        JsonNode files = config.get("Files");
        if (files == null || !files.isArray()) return null;

        for (JsonNode file : files) {
            JsonNode urlNode = file.get("Url");
            if (urlNode == null) urlNode = file.get("url");
            if (urlNode == null) continue;

            String url = urlNode.asText("");
            if (url.isEmpty()) continue;

            if (url.contains("downloadmirror.intel.com")) {
                continue;
            }

            if (url.endsWith(".exe") || url.endsWith(".zip")) {
                if (!url.startsWith("http")) {
                    url = "https://" + url;
                }
                return url;
            }
        }

        return null;
    }

    private JsonNode getDsaConfigurations() {
        long now = System.currentTimeMillis();
        if (cachedDsaConfig != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedDsaConfig;
        }

        JsonNode result = fetchDsaFeed(DSA_DATA_FEED_URL, true);
        if (result == null) {
            AppLogger.warning("Intel: Primary DSA feed failed, trying fallback URL");
            result = fetchDsaFeed(DSA_DATA_FEED_FALLBACK_URL, false);
        }
        if (result == null) {
            AppLogger.warning("Intel: All DSA feed URLs failed. Legacy web scraping will be used as fallback.");
        }
        return result;
    }

    private JsonNode fetchDsaFeed(String url, boolean extractFromZip) {
        try {
            AppLogger.info("Intel: Downloading DSA data feed from " + url);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET()
                    .build();

            HttpResponse<byte[]> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                AppLogger.warning("Intel: DSA data feed returned HTTP " + resp.statusCode() + " from " + url);
                return null;
            }

            String jsonContent;
            if (extractFromZip) {
                jsonContent = extractJsonFromZip(resp.body());
            } else {
                jsonContent = new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (jsonContent == null) {
                AppLogger.warning("Intel: Could not parse DSA data from " + url);
                return null;
            }

            JsonNode root = JsonMapper.parseTree(jsonContent);
            cachedDsaConfig = root;
            cacheTimestamp = System.currentTimeMillis();
            AppLogger.info("Intel: Loaded DSA configurations (" + root.size() + " entries) from " + url);
            return root;
        } catch (Exception e) {
            AppLogger.warning("Intel: Error loading DSA data feed from " + url + ": " + e.getMessage());
            return null;
        }
    }

    private String extractJsonFromZip(byte[] zipBytes) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("software-configurations.json".equals(entry.getName())) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    return baos.toString("UTF-8");
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Intel: Error extracting JSON from ZIP: " + e.getMessage());
        }
        return null;
    }

    private String detectCategory(InstalledDriver driver) {
        String name = driver.friendlyName() != null ? driver.friendlyName().toLowerCase() : "";

        if (name.contains("bluetooth") || name.contains("wireless bluetooth")) return "bluetooth";
        if (name.contains("wifi") || name.contains("wireless-ac") || name.contains("wireless")) return "wifi";
        if (name.contains("management engine") || name.contains("intel mei")) return "management-engine";
        if (name.contains("serial io")) return "serial-io";
        if (name.contains("graphics") || name.contains("iris") || name.contains("uhd") || name.contains("arc")) return "graphics";
        if (name.contains("chipset") || name.contains("pch")) return "chipset";
        return "general";
    }

    private String getTextField(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        String text = v.asText("").trim();
        return text.isEmpty() ? null : text;
    }
}
