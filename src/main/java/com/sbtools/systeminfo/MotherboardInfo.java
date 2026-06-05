package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MotherboardInfo(
        @JsonProperty("manufacturer") String manufacturer,
        @JsonProperty("model") String model,
        @JsonProperty("serialNumber") String serialNumber,
        @JsonProperty("version") String version,
        @JsonProperty("chipset") String chipset,
        @JsonProperty("southbridge") String southbridge
) {
}
