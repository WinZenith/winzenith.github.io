package com.sbtools.drivers;

import java.net.URI;

/** HTTPS + vendor host validation for automatic driver downloads. */
public final class DriverInstallTrust {

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
}
