package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StorageInfo(
        @JsonProperty("disks") List<Disk> disks,
        @JsonProperty("partitions") List<Partition> partitions,
        @JsonProperty("nvmes") List<Nvme> nvmes
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Disk(
            @JsonProperty("model") String model,
            @JsonProperty("manufacturer") String manufacturer,
            @JsonProperty("sizeBytes") long sizeBytes,
            @JsonProperty("mediType") String mediType,
            @JsonProperty("interfaceType") String interfaceType,
            @JsonProperty("serialNumber") String serialNumber,
            @JsonProperty("partitions") int partitions
    ) {
        public String formatSize() {
            if (sizeBytes <= 0) return "";
            double tb = sizeBytes / (1024.0 * 1024 * 1024 * 1024);
            if (tb >= 1) return String.format("%.2f TB", tb);
            double gb = sizeBytes / (1024.0 * 1024 * 1024);
            return String.format("%.1f GB", gb);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Partition(
            @JsonProperty("deviceID") String deviceID,
            @JsonProperty("volumeName") String volumeName,
            @JsonProperty("fsType") String fsType,
            @JsonProperty("sizeBytes") long sizeBytes,
            @JsonProperty("freeBytes") long freeBytes
    ) {
        public String formatSize() {
            if (sizeBytes <= 0) return "";
            double gb = sizeBytes / (1024.0 * 1024 * 1024);
            return String.format("%.1f GB", gb);
        }

        public String formatFree() {
            if (freeBytes <= 0) return "";
            double gb = freeBytes / (1024.0 * 1024 * 1024);
            return String.format("%.1f GB", gb);
        }

        public String formatUsed() {
            if (sizeBytes <= 0 || freeBytes <= 0) return "";
            double used = (sizeBytes - freeBytes) / (1024.0 * 1024 * 1024);
            return String.format("%.1f GB", used);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Nvme(
            @JsonProperty("serialNumber") String serialNumber,
            @JsonProperty("mediaType") String mediaType,
            @JsonProperty("busType") String busType
    ) {
    }
}
