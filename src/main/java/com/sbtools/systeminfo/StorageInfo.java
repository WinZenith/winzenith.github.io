package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sbtools.util.DataSizeFormatter;

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
            return DataSizeFormatter.formatBytes(sizeBytes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Partition(
            @JsonProperty("deviceID") String deviceID,
            @JsonProperty("volumeName") String volumeName,
            @JsonProperty("fsType") String fsType,
            @JsonProperty("sizeBytes") long sizeBytes,
            @JsonProperty("freeBytes") long freeBytes,
            @JsonProperty("diskIndex") int diskIndex
    ) {
        public String formatSize() {
            return DataSizeFormatter.formatBytes(sizeBytes);
        }

        public String formatFree() {
            return DataSizeFormatter.formatBytes(freeBytes);
        }

        public String formatUsed() {
            if (sizeBytes <= 0 || freeBytes < 0) return "";
            return DataSizeFormatter.formatBytes(sizeBytes - freeBytes);
        }

        public double usagePercent() {
            if (sizeBytes <= 0) return 0;
            return (double) (sizeBytes - freeBytes) / sizeBytes * 100;
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
