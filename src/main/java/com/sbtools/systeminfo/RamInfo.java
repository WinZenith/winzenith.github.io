package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RamInfo(
        @JsonProperty("totalBytes") long totalBytes,
        @JsonProperty("channel") String channel,
        @JsonProperty("sticks") List<RamStick> sticks
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RamStick(
            @JsonProperty("capacityBytes") long capacityBytes,
            @JsonProperty("speedMhz") int speedMhz,
            @JsonProperty("manufacturer") String manufacturer,
            @JsonProperty("partNumber") String partNumber,
            @JsonProperty("formFactor") String formFactor,
            @JsonProperty("memoryType") String memoryType
    ) {
        public String formatCapacity() {
            if (capacityBytes <= 0) return "";
            double gb = capacityBytes / (1024.0 * 1024 * 1024);
            if (gb >= 1) return String.format("%.0f GB", gb);
            double mb = capacityBytes / (1024.0 * 1024);
            return String.format("%.0f MB", mb);
        }

        public String formatSpeed() {
            return speedMhz > 0 ? speedMhz + " MHz" : "";
        }
    }

    public String formatTotal() {
        if (totalBytes <= 0) return "";
        double gb = totalBytes / (1024.0 * 1024 * 1024);
        return String.format("%.1f GB", gb);
    }
}
