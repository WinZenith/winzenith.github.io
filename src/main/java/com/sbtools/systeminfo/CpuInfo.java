package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sbtools.util.DataSizeFormatter;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CpuInfo(
        @JsonProperty("name") String name,
        @JsonProperty("manufacturer") String manufacturer,
        @JsonProperty("cores") int cores,
        @JsonProperty("logicalCpus") int logicalCpus,
        @JsonProperty("baseClockMhz") int baseClockMhz,
        @JsonProperty("currentClockMhz") int currentClockMhz,
        @JsonProperty("l2CacheKb") int l2CacheKb,
        @JsonProperty("l3CacheKb") int l3CacheKb,
        @JsonProperty("socket") String socket,
        @JsonProperty("architecture") String architecture,
        @JsonProperty("stepping") String stepping,
        @JsonProperty("revision") String revision,
        @JsonProperty("voltage") String voltage
) {
    public String formatBaseClock() {
        return DataSizeFormatter.formatMhz(baseClockMhz);
    }

    public String formatCurrentClock() {
        return DataSizeFormatter.formatMhz(currentClockMhz);
    }

    public String formatL2Cache() {
        return DataSizeFormatter.formatKb(l2CacheKb);
    }

    public String formatL3Cache() {
        return DataSizeFormatter.formatKb(l3CacheKb);
    }
}
