package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sbtools.util.DataSizeFormatter;

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
            return DataSizeFormatter.formatBytesRounded(capacityBytes);
        }

        public String formatSpeed() {
            return DataSizeFormatter.formatMhz(speedMhz);
        }
    }

    /** False for the WMI-failure shell (0 bytes and no sticks). */
    public boolean reported() {
        if (totalBytes > 0) return true;
        if (sticks == null) return false;
        for (RamStick stick : sticks) {
            if (stick != null && stick.capacityBytes() > 0) return true;
        }
        return false;
    }

    public String formatTotal() {
        if (totalBytes <= 0) return "";
        return DataSizeFormatter.formatBytes(totalBytes);
    }
}
