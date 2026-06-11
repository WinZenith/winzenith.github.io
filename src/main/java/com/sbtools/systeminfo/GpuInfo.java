package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sbtools.util.DataSizeFormatter;

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
        return DataSizeFormatter.formatBytesRounded(vramBytes);
    }
}
