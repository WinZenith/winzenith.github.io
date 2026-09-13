package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

public enum OemVendorHelper {
    NVIDIA("VEN_10DE", "10DE", "NVIDIA"),
    AMD("VEN_1002", "1002", "AMD"),
    INTEL("VEN_8086", "8086", "Intel"),
    REALTEK("VEN_10EC", "10EC", "Realtek"),
    BROADCOM("VEN_14E4", "14E4", "Broadcom"),
    QUALCOMM("VEN_168C", "168C", "Qualcomm"),
    SYNAPTICS("VEN_06CB", "06CB", "Synaptics"),
    LENOVO("LEN", "LENOVO", "Lenovo"),
    DELL("DELL", "DELL", "Dell"),
    HP("HPQ", "HEWLETT", "HP"),
    ASUS("ASUS", "ATK", "ASUS");

    private final String pciPattern;
    private final String venId;
    private final String label;

    OemVendorHelper(String pciPattern, String venId, String label) {
        this.pciPattern = pciPattern;
        this.venId = venId;
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Label match with word-boundary guard for short labels: "HP" must not
     * match "PHILIPS"/"PHP", "AMD" must not match longer-word substrings.
     * Labels longer than 4 chars use plain contains (low collision risk).
     */
    static boolean matchesLabel(String haystackUpper, String label) {
        if (haystackUpper == null || haystackUpper.isBlank() || label == null || label.isBlank()) return false;
        String needle = label.toUpperCase();
        // Vendor spelling variant: ASUSTeK has no ASUS word boundary.
        if ("ASUS".equals(needle) && haystackUpper.contains("ASUSTEK")) return true;
        if (needle.length() > 4) return haystackUpper.contains(needle);
        return haystackUpper.matches("(?s).*\\b" + java.util.regex.Pattern.quote(needle) + "\\b.*");
    }

    public static OemVendorHelper detect(InstalledDriver driver) {
        if (driver == null) {
            return null;
        }
        String hw = driver.hardwareIds() != null ? driver.hardwareIds().toUpperCase() : "";
        String name = driver.friendlyName() != null ? driver.friendlyName().toUpperCase() : "";
        String prov = driver.provider() != null ? driver.provider().toUpperCase() : "";

        AppLogger.debug("VendorDetect: Driver='" + driver.friendlyName() + "', HW='" + hw + "', Provider='" + prov + "'");

        for (OemVendorHelper v : values()) {
            // HW tag match: VEN_xxxx tokens are already exact (prefix
            // included); ACPI tags (HPQ/LEN/DELL/ASUS) must also match here —
            // requiring startsWith("VEN_") skipped those providers entirely,
            // so HPQ/Lenovo/Dell/ASUS devices with generic names were never
            // detected and their providers never ran (missed updates).
            boolean hwMatch = v.pciPattern != null && !v.pciPattern.isBlank() && hw.contains(v.pciPattern);
            // venId (e.g. 1002) must appear as VEN_1002 or DEV_1002 or isolated, not arbitrary substring.
            boolean venIdMatch = false;
            if (v.venId != null && v.venId.length() == 4) {
                String venToken = "VEN_" + v.venId;
                String devToken = "DEV_" + v.venId;
                venIdMatch = hw.contains(venToken) || hw.contains(devToken);
                // For name/provider, require word boundary for 4-digit ID
                if (!venIdMatch) {
                    venIdMatch = name.matches(".*\\b" + v.venId + "\\b.*") || prov.matches(".*\\b" + v.venId + "\\b.*");
                }
            } else if (v.venId != null) {
                venIdMatch = hw.contains(v.venId) || name.contains(v.venId) || prov.contains(v.venId);
            }
            boolean nameMatch = matchesLabel(name, v.label);
            boolean provMatch = matchesLabel(prov, v.label.toUpperCase());
            if (hwMatch || venIdMatch || nameMatch || provMatch) {
                AppLogger.debug("VendorDetect: Matched vendor " + v.label() + " for driver " + driver.friendlyName());
                return v;
            }
        }
        AppLogger.debug("VendorDetect: No vendor matched for driver " + driver.friendlyName());
        return null;
    }
}
