package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OtherDevice(
        @JsonProperty("name") String name,
        @JsonProperty("deviceClass") String deviceClass,
        @JsonProperty("manufacturer") String manufacturer,
        @JsonProperty("deviceId") String deviceId,
        @JsonProperty("status") String status
) {
}
