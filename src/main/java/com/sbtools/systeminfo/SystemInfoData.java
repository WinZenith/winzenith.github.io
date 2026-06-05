package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SystemInfoData(
        @JsonProperty("cpu") CpuInfo cpu,
        @JsonProperty("gpu") java.util.List<GpuInfo> gpu,
        @JsonProperty("ram") RamInfo ram,
        @JsonProperty("os") OsInfo os,
        @JsonProperty("storage") StorageInfo storage,
        @JsonProperty("motherboard") MotherboardInfo motherboard,
        @JsonProperty("bios") BiosInfo bios,
        @JsonProperty("others") java.util.List<OtherDevice> others
) {
}
