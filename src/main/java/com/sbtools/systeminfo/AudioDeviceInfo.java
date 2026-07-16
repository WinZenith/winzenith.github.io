package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AudioDeviceInfo(
        @JsonProperty("name") String name,
        @JsonProperty("manufacturer") String manufacturer,
        @JsonProperty("status") String status,
        @JsonProperty("deviceId") String deviceId
) {
}
