package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SystemInfoData(
        @JsonProperty("cpu") CpuInfo cpu,
        @JsonProperty("gpu") List<GpuInfo> gpu,
        @JsonProperty("ram") RamInfo ram,
        @JsonProperty("os") OsInfo os,
        @JsonProperty("storage") StorageInfo storage,
        @JsonProperty("motherboard") MotherboardInfo motherboard,
        @JsonProperty("bios") BiosInfo bios,
        @JsonProperty("others") List<OtherDevice> others,
        @JsonProperty("version") String version,
        @JsonProperty("warnings") List<String> warnings
) {
}
