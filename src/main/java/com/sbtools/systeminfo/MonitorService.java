package com.sbtools.systeminfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbtools.util.AppLogger;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MonitorService {

    private static final long POLL_INTERVAL_MS = 1000;
    private static final long TIMEOUT_SECONDS = 30;

    private final ProcessRunner processRunner = new ProcessRunner(TIMEOUT_SECONDS);
    private volatile boolean running = false;
    private Thread monitorThread;

    private static final String PS_SCRIPT =
        "$cpu = (Get-CimInstance Win32_PerfFormattedData_PerfOS_Processor | Where-Object Name -eq '_Total').PercentProcessorTime; " +
        "$ramTotal = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory; " +
        "$ramFree = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1024; " +
        "$disks = Get-CimInstance Win32_PerfFormattedData_PerfDisk_LogicalDisk | Where-Object Name -ne '_Total' | Select-Object Name, DiskReadBytesPersec, DiskWriteBytesPersec; " +
        "$net = Get-CimInstance Win32_PerfFormattedData_Tcpip_NetworkInterface | Where-Object Name -notmatch 'isatap|Teredo|6to4' | Select-Object Name, BytesReceivedPersec, BytesSentPersec; " +
        "$cpuTemp = @(Get-CimInstance -Namespace root/wmi -Class MSAcpi_ThermalZoneTemperature | Select-Object -First 1 CurrentTemperature); " +
        "$cpuTempVal = if ($cpuTemp) { [math]::Round(($cpuTemp.CurrentTemperature / 10) - 273.15, 1) } else { -1 }; " +
        "$gpu = Get-CimInstance Win32_VideoController | Select-Object -First 1 Name, AdapterRAM; " +
        "ConvertTo-Json -Compress -InputObject @{ " +
            "cpuUsage = $cpu; " +
            "cpuTempCelsius = $cpuTempVal; " +
            "ramTotalBytes = $ramTotal; " +
            "ramUsedBytes = $ramTotal - $ramFree; " +
            "disks = @($disks | ForEach-Object { @{ deviceId = $_.Name; readBytesPerSec = [long]$_.DiskReadBytesPersec; writeBytesPerSec = [long]$_.DiskWriteBytesPersec } }); " +
            "network = @($net | ForEach-Object { @{ name = $_.Name; downloadBytesPerSec = [long]$_.BytesReceivedPersec; uploadBytesPerSec = [long]$_.BytesSentPersec } }); " +
            "gpuUsage = 0; " +
            "gpuTempCelsius = -1; " +
            "gpuVramUsed = 0; " +
            "gpuVramTotal = if ($gpu) { [long]$gpu.AdapterRAM } else { 0 } " +
        "}";

    public void start(Consumer<MonitorData> onUpdate) {
        if (running) return;
        running = true;
        monitorThread = new Thread(() -> {
            while (running) {
                try {
                    MonitorData data = pollOnce();
                    if (data != null && onUpdate != null) {
                        onUpdate.accept(data);
                    }
                } catch (Exception e) {
                    AppLogger.error("Monitor poll failed", e);
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "system-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    public void stop() {
        running = false;
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    private MonitorData pollOnce() throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("powershell");
        cmd.add("-NoProfile");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-Command");
        cmd.add(PS_SCRIPT);

        ProcessResult result = processRunner.run(cmd);
        if (!result.success()) {
            throw new IOException("Monitor query failed: " + result.combinedOutput());
        }

        String json = result.stdout();
        if (json == null || json.isBlank()) {
            throw new IOException("Monitor query returned empty output");
        }

        return parseMonitorData(json);
    }

    private MonitorData parseMonitorData(String json) throws IOException {
        JsonNode root = JsonMapper.parseTree(json);

        double cpuUsage = root.path("cpuUsage").asDouble(0);
        double cpuTempCelsius = root.path("cpuTempCelsius").asDouble(-1);
        double ramTotalBytes = root.path("ramTotalBytes").asDouble(0);
        double ramUsedBytes = root.path("ramUsedBytes").asDouble(0);
        double gpuUsage = root.path("gpuUsage").asDouble(0);
        double gpuTempCelsius = root.path("gpuTempCelsius").asDouble(-1);
        double gpuVramUsed = root.path("gpuVramUsed").asDouble(0);
        double gpuVramTotal = root.path("gpuVramTotal").asDouble(0);

        List<DiskIoData> diskIO = new ArrayList<>();
        JsonNode disks = root.path("disks");
        if (disks.isArray()) {
            for (JsonNode d : disks) {
                diskIO.add(new DiskIoData(
                    d.path("deviceId").asText(""),
                    d.path("readBytesPerSec").asDouble(0),
                    d.path("writeBytesPerSec").asDouble(0)
                ));
            }
        }

        List<NetworkData> network = new ArrayList<>();
        JsonNode net = root.path("network");
        if (net.isArray()) {
            for (JsonNode n : net) {
                network.add(new NetworkData(
                    n.path("name").asText(""),
                    n.path("downloadBytesPerSec").asDouble(0),
                    n.path("uploadBytesPerSec").asDouble(0)
                ));
            }
        }

        return new MonitorData(cpuUsage, cpuTempCelsius, gpuUsage, gpuTempCelsius,
            gpuVramUsed, gpuVramTotal, ramUsedBytes, ramTotalBytes, diskIO, network);
    }
}
