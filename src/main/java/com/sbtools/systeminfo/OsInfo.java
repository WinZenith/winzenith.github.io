package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OsInfo(
        @JsonProperty("name") String name,
        @JsonProperty("version") String version,
        @JsonProperty("buildNumber") String buildNumber,
        @JsonProperty("architecture") String architecture,
        @JsonProperty("installDate") String installDate,
        @JsonProperty("lastBoot") String lastBoot,
        @JsonProperty("computerName") String computerName,
        @JsonProperty("windowsDir") String windowsDir,
        @JsonProperty("serialNumber") String serialNumber,
        @JsonProperty("productKey") String productKey
) {
}
