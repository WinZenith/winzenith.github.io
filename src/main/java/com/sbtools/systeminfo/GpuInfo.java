package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GpuInfo(
        @JsonProperty("name") String name,
        @JsonProperty("manufacturer") String manufacturer,
        @JsonProperty("videoProcessor") String videoProcessor,
        @JsonProperty("vramBytes") long vramBytes,
        @JsonProperty("memoryType") String memoryType,
        @JsonProperty("driverVersion") String driverVersion,
        @JsonProperty("driverDate") String driverDate,
        @JsonProperty("resolution") String resolution,
        @JsonProperty("colorDepth") String colorDepth,
        @JsonProperty("status") String status
) {
    public String formatVram() {
        if (vramBytes <= 0) return "";
        double gb = vramBytes / (1024.0 * 1024 * 1024);
        if (gb >= 1) return String.format("%.0f GB", gb);
        double mb = vramBytes / (1024.0 * 1024);
        return String.format("%.0f MB", mb);
    }
}
