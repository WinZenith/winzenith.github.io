package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.util.AppLogger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OemNvidiaCatalogProvider extends AbstractOemCatalogProvider {

    private static final String PROCESS_FIND_URL = "https://www.nvidia.com/Download/processFind.aspx";
    private static final String LOOKUP_URL = "https://www.nvidia.com/Download/API/lookupValueSearch.aspx";

    private static final Pattern GPU_MODEL_PATTERN = Pattern.compile(
            "(GeForce\\s+(?:RTX|GTX|GT|GT\\s+\\d+|RTX\\s+\\d+|GTX\\s+\\d+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "Version\\s*([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOWNLOAD_LINK_PATTERN = Pattern.compile(
            "href\\s*=\\s*\"([^\"]*download\\.nvidia\\.com[^\"]*\\.(exe|zip))\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCT_PAGE_PATTERN = Pattern.compile(
            "href\\s*=\\s*\"(https?://www\\.nvidia\\.com/Download/[^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern LNK_DWNLD_PATTERN = Pattern.compile(
            "id\\s*=\\s*\"lnkDwnldBtn\"[^>]*href\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static final Map<String, int[]> GPU_PSID_PFID = new HashMap<>();

    static {
        GPU_PSID_PFID.put("GeForce RTX 5090", new int[]{124, 1034});
        GPU_PSID_PFID.put("GeForce RTX 5080", new int[]{124, 1033});
        GPU_PSID_PFID.put("GeForce RTX 5070 Ti", new int[]{124, 1032});
        GPU_PSID_PFID.put("GeForce RTX 5070", new int[]{124, 1031});
        GPU_PSID_PFID.put("GeForce RTX 4090", new int[]{114, 972});
        GPU_PSID_PFID.put("GeForce RTX 4080", new int[]{114, 973});
        GPU_PSID_PFID.put("GeForce RTX 4070 Ti", new int[]{114, 974});
        GPU_PSID_PFID.put("GeForce RTX 4070", new int[]{114, 975});
        GPU_PSID_PFID.put("GeForce RTX 4060 Ti", new int[]{114, 976});
        GPU_PSID_PFID.put("GeForce RTX 4060", new int[]{114, 977});
        GPU_PSID_PFID.put("GeForce RTX 3090 Ti", new int[]{110, 937});
        GPU_PSID_PFID.put("GeForce RTX 3090", new int[]{110, 933});
        GPU_PSID_PFID.put("GeForce RTX 3080 Ti", new int[]{110, 934});
        GPU_PSID_PFID.put("GeForce RTX 3080", new int[]{110, 935});
        GPU_PSID_PFID.put("GeForce RTX 3070 Ti", new int[]{110, 938});
        GPU_PSID_PFID.put("GeForce RTX 3070", new int[]{110, 936});
        GPU_PSID_PFID.put("GeForce RTX 3060 Ti", new int[]{110, 939});
        GPU_PSID_PFID.put("GeForce RTX 3060", new int[]{110, 940});
        GPU_PSID_PFID.put("GeForce RTX 3050", new int[]{110, 941});
        GPU_PSID_PFID.put("GeForce RTX 2080 Ti", new int[]{107, 895});
        GPU_PSID_PFID.put("GeForce RTX 2080 SUPER", new int[]{107, 896});
        GPU_PSID_PFID.put("GeForce RTX 2080", new int[]{107, 894});
        GPU_PSID_PFID.put("GeForce RTX 2070 SUPER", new int[]{107, 897});
        GPU_PSID_PFID.put("GeForce RTX 2070", new int[]{107, 893});
        GPU_PSID_PFID.put("GeForce RTX 2060 SUPER", new int[]{107, 898});
        GPU_PSID_PFID.put("GeForce RTX 2060", new int[]{107, 892});
        GPU_PSID_PFID.put("GeForce GTX 1660 Ti", new int[]{107, 872});
        GPU_PSID_PFID.put("GeForce GTX 1660 SUPER", new int[]{107, 873});
        GPU_PSID_PFID.put("GeForce GTX 1660", new int[]{107, 871});
        GPU_PSID_PFID.put("GeForce GTX 1650 SUPER", new int[]{107, 874});
        GPU_PSID_PFID.put("GeForce GTX 1650", new int[]{107, 815});
        GPU_PSID_PFID.put("GeForce GTX 1080 Ti", new int[]{101, 845});
        GPU_PSID_PFID.put("GeForce GTX 1080", new int[]{101, 816});
        GPU_PSID_PFID.put("GeForce GTX 1070 Ti", new int[]{101, 819});
        GPU_PSID_PFID.put("GeForce GTX 1070", new int[]{101, 818});
        GPU_PSID_PFID.put("GeForce GTX 1060 6GB", new int[]{101, 814});
        GPU_PSID_PFID.put("GeForce GTX 1060 3GB", new int[]{101, 813});
        GPU_PSID_PFID.put("GeForce GTX 1060", new int[]{101, 814});
        GPU_PSID_PFID.put("GeForce GTX 1050 Ti", new int[]{101, 809});
        GPU_PSID_PFID.put("GeForce GTX 1050", new int[]{101, 808});
        GPU_PSID_PFID.put("GeForce GT 1030", new int[]{101, 832});
        GPU_PSID_PFID.put("GeForce GT 730", new int[]{101, 730});
    }

    public OemNvidiaCatalogProvider() {
        super(OemVendorHelper.NVIDIA);
    }

    @Override
    public String id() {
        return "Nvidia";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("NVIDIA: Fetching latest version for " + driver.friendlyName());

        String[] result = resolveDriverInfo(driver);
        if (result != null && result[0] != null) {
            AppLogger.info("NVIDIA: Latest version for " + driver.friendlyName() + " is " + result[0]);
            return result[0];
        }

        AppLogger.warning("NVIDIA: Could not determine latest version for " + driver.friendlyName());
        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        String gpuModel = extractGpuModel(driver);
        if (!"unknown".equalsIgnoreCase(gpuModel)) {
            try {
                return String.format("https://www.nvidia.com/Download/index.aspx?lang=en-us&search=%s",
                        URLEncoder.encode(gpuModel, StandardCharsets.UTF_8.toString()));
            } catch (Exception e) {
                return "https://www.nvidia.com/Download/index.aspx?lang=en-us";
            }
        }
        return "https://www.nvidia.com/Download/index.aspx?lang=en-us";
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        AppLogger.info("NVIDIA: Resolving direct download URL for " + driver.friendlyName());
        try {
            String[] info = resolveDriverInfo(driver);
            if (info != null && info.length >= 2 && info[1] != null) {
                AppLogger.info("NVIDIA: Resolved direct download URL: " + info[1]);
                return info[1];
            }
            AppLogger.warning("NVIDIA: Could not resolve direct download URL for " + driver.friendlyName());
            return null;
        } catch (Exception e) {
            AppLogger.warning("NVIDIA: Error resolving download URL: " + e.getMessage());
            return null;
        }
    }

    private String[] resolveDriverInfo(InstalledDriver driver) {
        int[] psidPfid = lookupPsidPfid(driver);
        if (psidPfid == null) {
            AppLogger.warning("NVIDIA: Could not determine psid/pfid for " + driver.friendlyName());
            return null;
        }

        int psid = psidPfid[0];
        int pfid = psidPfid[1];
        AppLogger.debug("NVIDIA: Using psid=" + psid + ", pfid=" + pfid + " for " + driver.friendlyName());

        String searchUrl = PROCESS_FIND_URL
                + "?psid=" + psid
                + "&pfid=" + pfid
                + "&osid=57"
                + "&lid=1"
                + "&whql=1"
                + "&lang=en-us"
                + "&ctk=0"
                + "&dtcid=1";

        String html = httpGet(searchUrl);
        if (html == null) {
            AppLogger.warning("NVIDIA: processFind.aspx returned null for " + driver.friendlyName());
            return null;
        }

        String version = extractVersionFromSearchResults(html);
        String downloadUrl = extractDownloadUrlFromSearchResults(html);

        if (version == null) {
            AppLogger.warning("NVIDIA: Could not extract version from search results");
            return null;
        }

        if (downloadUrl == null) {
            downloadUrl = followProductPage(html);
        }

        return new String[]{version, downloadUrl};
    }

    private int[] lookupPsidPfid(InstalledDriver driver) {
        String gpuName = normalizeGpuName(extractGpuModel(driver));

        if (gpuName != null) {
            for (Map.Entry<String, int[]> entry : GPU_PSID_PFID.entrySet()) {
                String key = normalizeGpuName(entry.getKey());
                if (key != null && key.contains(gpuName)) {
                    return entry.getValue();
                }
            }
        }

        AppLogger.debug("NVIDIA: GPU not found in local database, querying lookupValueSearch API");
        try {
            String lookupUrl = LOOKUP_URL + "?TypeID=3";
            String xml = httpGet(lookupUrl);
            if (xml == null) return null;

            String lowerGpuName = gpuName != null ? gpuName.toLowerCase() : "";
            Pattern pfidPattern = Pattern.compile(
                    "<Name>([^<]*(?:GTX|RTX|GT)[^<]*)</Name>\\s*<ID>(\\d+)</ID>", Pattern.CASE_INSENSITIVE);
            Matcher m = pfidPattern.matcher(xml);

            while (m.find()) {
                String name = m.group(1).trim();
                String pfidStr = m.group(2);
                String normalizedName = normalizeGpuName(name);
                if (normalizedName != null && lowerGpuName.contains(normalizedName.toLowerCase())) {
                    int pfid = Integer.parseInt(pfidStr);
                    return new int[]{107, pfid};
                }
            }
        } catch (Exception e) {
            AppLogger.warning("NVIDIA: lookupValueSearch failed: " + e.getMessage());
        }

        return null;
    }

    private String normalizeGpuName(String name) {
        if (name == null) return null;
        String n = name.trim();
        n = n.replaceAll("(?i)\\bNVIDIA\\b\\s*", "");
        n = n.replaceAll("(?i)\\bwith Max-Q Design\\b", "");
        n = n.replaceAll("(?i)\\b Laptop\\b", "");
        n = n.replaceAll("(?i)\\bDesktop\\b", "");
        return n.trim();
    }

    private String extractGpuModel(InstalledDriver driver) {
        String name = driver.friendlyName() != null ? driver.friendlyName() : "";
        Matcher m = GPU_MODEL_PATTERN.matcher(name);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "unknown";
    }

    private String extractVersionFromSearchResults(String html) {
        Matcher m = VERSION_PATTERN.matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String extractDownloadUrlFromSearchResults(String html) {
        Matcher m = DOWNLOAD_LINK_PATTERN.matcher(html);
        if (m.find()) {
            String url = m.group(1);
            if (url.startsWith("//")) {
                url = "https:" + url;
            }
            return url;
        }
        return null;
    }

    private String followProductPage(String searchHtml) {
        Matcher m = PRODUCT_PAGE_PATTERN.matcher(searchHtml);
        if (!m.find()) return null;

        String productPageUrl = m.group(1);
        AppLogger.debug("NVIDIA: Fetching product page: " + productPageUrl);
        String productHtml = httpGet(productPageUrl);
        if (productHtml == null) return null;

        Matcher m2 = LNK_DWNLD_PATTERN.matcher(productHtml);
        if (!m2.find()) {
            m2 = Pattern.compile("href\\s*=\\s*\"([^\"]+)\"[^>]*id\\s*=\\s*\"lnkDwnldBtn\"", Pattern.CASE_INSENSITIVE)
                    .matcher(productHtml);
            if (!m2.find()) return null;
        }

        String downloadPageUrl = m2.group(1);
        if (downloadPageUrl.startsWith("/")) {
            downloadPageUrl = "https://www.nvidia.com" + downloadPageUrl;
        }
        AppLogger.debug("NVIDIA: Fetching download page: " + downloadPageUrl);

        String downloadHtml = httpGet(downloadPageUrl);
        if (downloadHtml == null) return null;

        Matcher m3 = DOWNLOAD_LINK_PATTERN.matcher(downloadHtml);
        if (m3.find()) {
            String url = m3.group(1);
            if (url.startsWith("//")) {
                url = "https:" + url;
            }
            return url;
        }

        return null;
    }
}
