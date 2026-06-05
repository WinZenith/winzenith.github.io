package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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
        return baseClockMhz > 0 ? baseClockMhz + " MHz" : "";
    }

    public String formatCurrentClock() {
        return currentClockMhz > 0 ? currentClockMhz + " MHz" : "";
    }

    public String formatL2Cache() {
        if (l2CacheKb <= 0) return "";
        if (l2CacheKb >= 1024) return String.format("%.1f MB", l2CacheKb / 1024.0);
        return l2CacheKb + " KB";
    }

    public String formatL3Cache() {
        if (l3CacheKb <= 0) return "";
        if (l3CacheKb >= 1024) return String.format("%.1f MB", l3CacheKb / 1024.0);
        return l3CacheKb + " KB";
    }
}
