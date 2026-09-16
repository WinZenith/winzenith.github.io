package com.sbtools.drivers;

import com.sbtools.drivers.model.InstalledDriver;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTPS + vendor host validation for automatic driver downloads. */
public final class DriverInstallTrust {

    private static final Pattern HW_TOKEN = Pattern.compile("(?:dev_|pid_)([0-9a-f]{4})");
    private static final Pattern MODEL_TOKEN = Pattern.compile("(rtl|alc|rts)[0-9a-z]{3,6}|ax[0-9]{3}|be2[0-9]{2}");

    private DriverInstallTrust() {
    }

    public static boolean isTrustedHttpsUrl(String url, String source) {
        if (url == null || url.isBlank() || source == null || source.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
            return hostAllowed(host, source);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean hostAllowed(String host, String source) {
        if (host == null || source == null) {
            return false;
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return switch (source) {
            case "Intel" -> matchesDomain(h, "intel.com") || h.equals("downloadmirror.intel.com");
            case "Nvidia" -> matchesDomain(h, "nvidia.com") || matchesDomain(h, "geforce.com")
                    || matchesDomain(h, "nvdlcdn.com");
            case "AMD" -> matchesDomain(h, "amd.com") || matchesDomain(h, "drivers.amd.com");
            case "Realtek" -> matchesDomain(h, "realtek.com") || matchesDomain(h, "realtek.com.tw");
            case "Broadcom" -> matchesDomain(h, "broadcom.com");
            case "Qualcomm" -> matchesDomain(h, "qualcomm.com");
            case "Synaptics" -> matchesDomain(h, "synaptics.com") || matchesDomain(h, "hp.com")
                    || matchesDomain(h, "ftp.hp.com") || matchesDomain(h, "lenovo.com");
            case "Lenovo" -> matchesDomain(h, "lenovo.com") || matchesDomain(h, "lenovo-images.com")
                    || matchesDomain(h, "lenovo.net");
            case "Dell" -> matchesDomain(h, "dell.com") || matchesDomain(h, "dellcdn.com")
                    || matchesDomain(h, "dell-cdn.com");
            case "HP" -> matchesDomain(h, "hp.com") || matchesDomain(h, "hpe.com") || matchesDomain(h, "hp.com.cn");
            case "ASUS" -> matchesDomain(h, "asus.com") || matchesDomain(h, "asusnet.net")
                    || matchesDomain(h, "asus.com.cn");
            case "WindowsUpdate" -> matchesDomain(h, "microsoft.com") || matchesDomain(h, "windowsupdate.com")
                    || matchesDomain(h, "windowsupdate.microsoft.com");
            default -> false;
        };
    }

    static boolean matchesDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /**
     * Picks the URL that best names this device. Token hits win; otherwise a
     * same-category vendor payload. Returns null when nothing fits (manual).
     */
    public static String pickBestDownloadUrl(List<String> urls, InstalledDriver driver) {
        if (urls == null || urls.isEmpty() || driver == null) {
            return null;
        }
        String bestToken = null;
        int bestScore = 0;
        String bestCat = null;
        String deviceCat = category(deviceBlob(driver));
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            if (categoryConflicts(url, driver)) {
                continue;
            }
            int score = tokenScore(url, driver);
            if (score > bestScore) {
                bestScore = score;
                bestToken = url;
            }
            if (bestCat == null && deviceCat != null && deviceCat.equals(category(url))) {
                bestCat = url;
            }
        }
        if (bestToken != null) {
            return bestToken;
        }
        if (bestCat != null && isFamilyInstaller(bestCat)) {
            return bestCat;
        }
        if (bestCat != null && !hasDeviceModelToken(driver)) {
            return bestCat;
        }
        return null;
    }

    static boolean isFamilyInstaller(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String h = url.toLowerCase(Locale.ROOT);
        return h.contains("adrenalin") || h.contains("dec-rdna") || h.contains("amd-software")
                || h.contains("amd_chipset") || h.contains("chipset_drivers")
                || h.contains("geforce") || h.contains("game-ready") || h.contains("dch-desktop")
                || h.contains("gfx_win") || h.contains("igfx_win");
    }

    /** True when the payload is a different device class than the target. */
    public static boolean categoryConflicts(String haystack, InstalledDriver driver) {
        if (haystack == null || haystack.isBlank() || driver == null) {
            return false;
        }
        String payloadCat = category(haystack);
        String deviceCat = category(deviceBlob(driver));
        return payloadCat != null && deviceCat != null && !payloadCat.equals(deviceCat);
    }

    public static boolean hasDeviceModelToken(InstalledDriver driver) {
        return !deviceTokens(driver).isEmpty();
    }

    public static int tokenScore(String haystack, InstalledDriver driver) {
        if (haystack == null || haystack.isBlank() || driver == null) {
            return 0;
        }
        String h = haystack.toLowerCase(Locale.ROOT);
        int n = 0;
        for (String token : deviceTokens(driver)) {
            if (h.contains(token)) {
                n++;
            }
        }
        return n;
    }

    static List<String> deviceTokens(InstalledDriver d) {
        List<String> tokens = new ArrayList<>();
        if (d == null) {
            return tokens;
        }
        String blob = deviceBlob(d).toLowerCase(Locale.ROOT);
        Matcher hw = HW_TOKEN.matcher(blob);
        while (hw.find()) {
            tokens.add(hw.group(1));
        }
        Matcher model = MODEL_TOKEN.matcher(blob);
        while (model.find()) {
            tokens.add(model.group());
        }
        return tokens;
    }

    static String category(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String h = s.toLowerCase(Locale.ROOT);
        if (h.contains("chipset") || h.contains("amd_chipset")) {
            return "chipset";
        }
        if (h.contains("adrenalin") || h.contains("radeon") || h.contains("geforce")
                || h.contains("nvidia") || h.contains("arc-a") || h.contains("iris")
                || h.contains("uhd graphics") || h.contains("graphics") || h.contains("-gpu")
                || h.contains("dec-rdna")) {
            return "graphics";
        }
        if (h.contains("bluetooth") || h.contains("bt-") || h.contains("/bt_")) {
            return "bluetooth";
        }
        if (h.contains("wifi") || h.contains("wi-fi") || h.contains("wireless")
                || h.contains("rtl88") || h.contains("ax2") || h.contains("be20")) {
            return "wifi";
        }
        if (h.contains("alc") || h.contains("audio") || h.contains("codec") || h.contains("sound")) {
            return "audio";
        }
        if (h.contains("ethernet") || h.contains("gbe") || h.contains("rtl81") || h.contains("rtl8125")) {
            return "ethernet";
        }
        if (h.contains("cardreader") || h.contains("card-reader") || h.contains("card reader")
                || h.contains("rts52") || h.contains("rts53")) {
            return "cardreader";
        }
        return null;
    }

    private static String deviceBlob(InstalledDriver d) {
        return (nullToEmpty(d.friendlyName()) + " " + nullToEmpty(d.hardwareIds())
                + " " + nullToEmpty(d.deviceId()) + " " + nullToEmpty(d.provider())).trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
