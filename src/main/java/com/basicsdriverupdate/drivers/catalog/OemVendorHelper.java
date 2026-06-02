package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.util.AppLogger;

public enum OemVendorHelper {
    NVIDIA("VEN_10DE", "10DE", "NVIDIA"),
    AMD("VEN_1002", "1002", "AMD"),
    INTEL("VEN_8086", "8086", "Intel"),
    REALTEK("VEN_10EC", "10EC", "Realtek"),
    BROADCOM("VEN_14E4", "14E4", "Broadcom"),
    QUALCOMM("VEN_168C", "168C", "Qualcomm"),
    SYNAPTICS("VEN_06CB", "06CB", "Synaptics");

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

    public static OemVendorHelper detect(InstalledDriver driver) {
        if (driver == null) {
            return null;
        }
        String hw = driver.hardwareIds() != null ? driver.hardwareIds().toUpperCase() : "";
        String name = driver.friendlyName() != null ? driver.friendlyName().toUpperCase() : "";
        String prov = driver.provider() != null ? driver.provider().toUpperCase() : "";
        
        AppLogger.debug("VendorDetect: Driver='" + driver.friendlyName() + "', HW='" + hw + "', Provider='" + prov + "'");
        
        for (OemVendorHelper v : values()) {
            if (hw.contains(v.pciPattern) || hw.contains(v.venId)
                    || name.contains(v.label.toUpperCase()) || prov.contains(v.label.toUpperCase())) {
                AppLogger.debug("VendorDetect: Matched vendor " + v.label() + " for driver " + driver.friendlyName());
                return v;
            }
        }
        AppLogger.debug("VendorDetect: No vendor matched for driver " + driver.friendlyName());
        return null;
    }
}
