package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BiosInfo(
        @JsonProperty("manufacturer") String manufacturer,
        @JsonProperty("version") String version,
        @JsonProperty("releaseDate") String releaseDate,
        @JsonProperty("smbiosMajor") int smbiosMajor,
        @JsonProperty("smbiosMinor") int smbiosMinor
) {
    public String formatSmbios() {
        if (smbiosMajor == 0) return "";
        return smbiosMajor + "." + smbiosMinor;
    }
}
