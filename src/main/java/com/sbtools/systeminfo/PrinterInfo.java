package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PrinterInfo(
        @JsonProperty("name") String name,
        @JsonProperty("driver") String driver,
        @JsonProperty("port") String port,
        @JsonProperty("status") String status,
        @JsonProperty("shared") boolean shared,
        @JsonProperty("isDefault") boolean isDefault
) {
}
