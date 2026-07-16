package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NetworkAdapterInfo(
        @JsonProperty("name") String name,
        @JsonProperty("manufacturer") String manufacturer,
        @JsonProperty("speed") String speed,
        @JsonProperty("macAddress") String macAddress,
        @JsonProperty("ipAddresses") List<String> ipAddresses,
        @JsonProperty("dhcpEnabled") boolean dhcpEnabled,
        @JsonProperty("adapterType") String adapterType,
        @JsonProperty("status") String status
) {
    public String formatIpAddresses() {
        if (ipAddresses == null || ipAddresses.isEmpty()) return "";
        return String.join(", ", ipAddresses);
    }
}
