package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

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
        @JsonProperty("networkAdapters") List<NetworkAdapterInfo> networkAdapters,
        @JsonProperty("audioDevices") List<AudioDeviceInfo> audioDevices,
        @JsonProperty("battery") BatteryInfo battery,
        @JsonProperty("temperatures") List<TemperatureInfo> temperatures,
        @JsonProperty("usbDevices") List<UsbDeviceInfo> usbDevices,
        @JsonProperty("monitors") List<MonitorInfo> monitors,
        @JsonProperty("printers") List<PrinterInfo> printers,
        @JsonProperty("version") String version,
        @JsonProperty("warnings") List<String> warnings,
        @JsonProperty("timings") Map<String, Long> timings,
        @JsonProperty("collectedAt") String collectedAt
) {
    /** Backward-compatible constructor for callers/tests using the v3.0 shape (no timings). */
    public SystemInfoData(
            CpuInfo cpu,
            List<GpuInfo> gpu,
            RamInfo ram,
            OsInfo os,
            StorageInfo storage,
            MotherboardInfo motherboard,
            BiosInfo bios,
            List<OtherDevice> others,
            List<NetworkAdapterInfo> networkAdapters,
            List<AudioDeviceInfo> audioDevices,
            BatteryInfo battery,
            List<TemperatureInfo> temperatures,
            List<UsbDeviceInfo> usbDevices,
            List<MonitorInfo> monitors,
            List<PrinterInfo> printers,
            String version,
            List<String> warnings) {
        this(cpu, gpu, ram, os, storage, motherboard, bios, others,
                networkAdapters, audioDevices, battery, temperatures, usbDevices, monitors,
                printers, version, warnings, null, null);
    }
}
