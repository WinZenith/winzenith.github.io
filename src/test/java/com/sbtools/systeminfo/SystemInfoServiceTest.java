package com.sbtools.systeminfo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for System Information v3.1 pure logic:
 * tolerant parsing (per-section fault isolation), section-filter JSON,
 * hardened extractJson, timings/collectedAt, and report generation.
 * No PowerShell is launched; no JavaFX toolkit is started.
 */
class SystemInfoServiceTest {

    private static String minimalJson(String... sections) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"version\":\"3.1\",");
        sb.append("\"warnings\":[],");
        for (int i = 0; i < sections.length; i += 2) {
            sb.append("\"").append(sections[i]).append("\":").append(sections[i + 1]).append(",");
        }
        sb.append("\"timings\":{\"cpu\":12,\"totalMs\":120},");
        sb.append("\"collectedAt\":\"2026-09-05T12:00:00Z\"");
        sb.append("}");
        return sb.toString();
    }

    @Test
    void parseTolerantly_fullPayload() throws Exception {
        String json = minimalJson(
                "cpu", "{\"name\":\"Intel i7\",\"manufacturer\":\"Intel\",\"cores\":8,\"logicalCpus\":16,"
                        + "\"baseClockMhz\":3600,\"currentClockMhz\":4200,\"l2CacheKb\":2048,\"l3CacheKb\":30720,"
                        + "\"socket\":\"LGA1700\",\"architecture\":\"x64\",\"stepping\":\"\",\"revision\":\"\",\"voltage\":\"\"}",
                "os", "{\"name\":\"Windows 11\",\"version\":\"10.0\",\"buildNumber\":\"22631\","
                        + "\"architecture\":\"64-bit\",\"installDate\":\"\",\"lastBoot\":\"\",\"computerName\":\"PC\","
                        + "\"windowsDir\":\"C:\\\\Windows\",\"serialNumber\":\"\",\"productKey\":\"\"}",
                "ram", "{\"totalBytes\":17179869184,\"channel\":\"Dual*\",\"sticks\":[]}",
                "gpu", "[]",
                "storage", "{\"disks\":[],\"partitions\":[],\"nvmes\":[]}");
        SystemInfoData data = SystemInfoService.parseTolerantly(json);
        assertEquals("Intel i7", data.cpu().name());
        assertEquals(8, data.cpu().cores());
        assertEquals("3600 MHz", data.cpu().formatBaseClock());
        assertEquals("Windows 11", data.os().name());
        assertEquals("3.1", data.version());
        assertNotNull(data.timings());
        assertEquals(12L, data.timings().get("cpu"));
        assertEquals("2026-09-05T12:00:00Z", data.collectedAt());
        assertTrue(data.warnings().isEmpty());
    }

    @Test
    void parseTolerantly_oneBadSectionDoesNotKillOthers() throws Exception {
        // gpu is a string instead of array -> skipped with warning, cpu/os survive.
        String json = "{\"version\":\"3.1\",\"warnings\":[],"
                + "\"cpu\":{\"name\":\"C\",\"manufacturer\":\"M\",\"cores\":4,\"logicalCpus\":8,"
                + "\"baseClockMhz\":3000,\"currentClockMhz\":3000,\"l2CacheKb\":512,\"l3CacheKb\":8192,"
                + "\"socket\":\"S\",\"architecture\":\"x64\",\"stepping\":\"\",\"revision\":\"\",\"voltage\":\"\"},"
                + "\"gpu\":\"corrupt\","
                + "\"os\":{\"name\":\"Windows 10\",\"version\":\"10.0\",\"buildNumber\":\"19045\","
                + "\"architecture\":\"64-bit\",\"installDate\":\"\",\"lastBoot\":\"\",\"computerName\":\"PC\","
                + "\"windowsDir\":\"C:\\\\Windows\",\"serialNumber\":\"\",\"productKey\":\"\"}}";
        SystemInfoData data = SystemInfoService.parseTolerantly(json);
        assertNotNull(data.cpu());
        assertNotNull(data.os());
        assertNull(data.gpu());
        assertFalse(data.warnings().isEmpty());
        assertTrue(data.warnings().stream().anyMatch(w -> w.contains("gpu")));
    }

    @Test
    void parseTolerantly_legacyV30WithoutTimings() throws Exception {
        String json = "{\"version\":\"3.0\",\"warnings\":[\"Temperatures: No thermal zone data\"],"
                + "\"cpu\":null,\"os\":null,\"ram\":null,\"storage\":null}";
        SystemInfoData data = SystemInfoService.parseTolerantly(json);
        assertEquals("3.0", data.version());
        assertNull(data.timings());
        assertNull(data.collectedAt());
        assertEquals(1, data.warnings().size());
    }

    @Test
    void parseTolerantly_batteryNullIsOkWithoutWarning() throws Exception {
        String json = "{\"version\":\"3.1\",\"warnings\":[],\"battery\":null,"
                + "\"os\":{\"name\":\"Windows 11\",\"version\":\"10.0\",\"buildNumber\":\"1\","
                + "\"architecture\":\"64-bit\",\"installDate\":\"\",\"lastBoot\":\"\",\"computerName\":\"PC\","
                + "\"windowsDir\":\"C:\\\\Windows\",\"serialNumber\":\"\",\"productKey\":\"\"}}";
        SystemInfoData data = SystemInfoService.parseTolerantly(json);
        assertNull(data.battery());
        assertTrue(data.warnings().isEmpty());
    }

    @Test
    void parseTolerantly_rejectsNonObject() {
        assertThrows(java.io.IOException.class, () -> SystemInfoService.parseTolerantly("[]"));
        assertThrows(java.io.IOException.class, () -> SystemInfoService.parseTolerantly("not json"));
    }

    @Test
    void extractJson_stripsPreambleAndBom() {
        String payload = "{\"version\":\"3.1\",\"cpu\":null,\"os\":null}";
        assertEquals(payload, SystemInfoService.extractJson("﻿" + payload, ""));
        assertEquals(payload, SystemInfoService.extractJson(
                "Windows PowerShell transcript start\n" + payload + "\n", ""));
        assertEquals(payload, SystemInfoService.extractJson(
                "line1\nline2\n" + payload, "some stderr"));
    }

    @Test
    void extractJson_returnsRawWhenNoJson() {
        String raw = "no json here";
        assertEquals(raw, SystemInfoService.extractJson(raw, ""));
        assertNull(SystemInfoService.extractJson(null, ""));
    }

    @Test
    void isValidSystemInfoJson_acceptsExpectedKeys() {
        assertTrue(SystemInfoService.isValidSystemInfoJson("{\"version\":\"3.1\"}"));
        assertTrue(SystemInfoService.isValidSystemInfoJson("{\"cpu\":null,\"os\":null}"));
        assertFalse(SystemInfoService.isValidSystemInfoJson("{\"foo\":1}"));
        assertFalse(SystemInfoService.isValidSystemInfoJson("not json"));
        assertFalse(SystemInfoService.isValidSystemInfoJson(""));
        assertFalse(SystemInfoService.isValidSystemInfoJson(null));
    }

    @Test
    void sectionGroups_coverAllKnownSections() {
        List<String> all = SystemInfoService.SECTION_GROUPS.stream()
                .flatMap(g -> g.sections().stream()).toList();
        for (String expected : List.of("cpu", "gpu", "ram", "os", "storage", "motherboard",
                "bios", "others", "networkAdapters", "audioDevices", "battery",
                "temperatures", "usbDevices", "monitors", "printers")) {
            assertTrue(all.contains(expected), "missing section: " + expected);
        }
        // No duplicates across groups (each section queried exactly once).
        assertEquals(all.size(), all.stream().distinct().count());
    }

    // ── Report generator ─────────────────────────────────────────────────

    private static SystemInfoData sampleData() throws Exception {
        String json = minimalJson(
                "cpu", "{\"name\":\"Intel i7-12700\",\"manufacturer\":\"Intel\",\"cores\":12,\"logicalCpus\":20,"
                        + "\"baseClockMhz\":3600,\"currentClockMhz\":4800,\"l2CacheKb\":12288,\"l3CacheKb\":25600,"
                        + "\"socket\":\"LGA1700\",\"architecture\":\"x64\",\"stepping\":\"\",\"revision\":\"\",\"voltage\":\"1.2 V\"}",
                "os", "{\"name\":\"Microsoft Windows 11 Pro\",\"version\":\"10.0.22631\",\"buildNumber\":\"22631\","
                        + "\"architecture\":\"64-bit\",\"installDate\":\"2024-01-01\",\"lastBoot\":\"2026-09-01\","
                        + "\"computerName\":\"TEST-PC\",\"windowsDir\":\"C:\\\\Windows\",\"serialNumber\":\"SN123\",\"productKey\":\"\"}",
                "ram", "{\"totalBytes\":34359738368,\"channel\":\"Dual*\",\"sticks\":["
                        + "{\"capacityBytes\":17179869184,\"speedMhz\":5600,\"manufacturer\":\"Samsung\","
                        + "\"partNumber\":\"M323R2GA3BB0\",\"formFactor\":\"DIMM\",\"memoryType\":\"DDR5\"}]}",
                "gpu", "[{\"name\":\"NVIDIA RTX 4070\",\"manufacturer\":\"NVIDIA\",\"videoProcessor\":\"Ada\","
                        + "\"vramBytes\":12884901888,\"memoryType\":\"GDDR6X\",\"driverVersion\":\"31.0.15.5123\","
                        + "\"driverDate\":\"2024-06-01\",\"resolution\":\"2560x1440\",\"colorDepth\":\"32-bit\",\"status\":\"OK\"}]",
                "storage", "{\"disks\":[{\"model\":\"Samsung 990 Pro\",\"manufacturer\":\"Samsung\","
                        + "\"sizeBytes\":2000398934016,\"mediaType\":\"SSD\",\"interfaceType\":\"NVMe\","
                        + "\"serialNumber\":\"S123\",\"partitions\":2}],"
                        + "\"partitions\":[{\"deviceID\":\"C:\",\"volumeName\":\"System\",\"fsType\":\"NTFS\","
                        + "\"sizeBytes\":1000000000000,\"freeBytes\":400000000000,\"diskIndex\":0}],\"nvmes\":[]}",
                "networkAdapters", "[{\"name\":\"Intel Wi-Fi 6E\",\"manufacturer\":\"Intel\",\"speed\":\"2.4 Gbps\","
                        + "\"macAddress\":\"AA:BB:CC:DD:EE:FF\",\"ipAddresses\":[\"192.168.1.10\"],"
                        + "\"dhcpEnabled\":true,\"adapterType\":\"Wireless\",\"status\":\"Connected\"}]",
                "printers", "[{\"name\":\"OfficeJet <Pro> & Co\",\"driver\":\"OJ Driver\",\"port\":\"USB001\","
                        + "\"status\":\"Ready\",\"shared\":false,\"isDefault\":true}]");
        return SystemInfoService.parseTolerantly(json);
    }

    @Test
    void plainTextReport_containsHeaderAndSections() throws Exception {
        SystemInfoData data = sampleData();
        String txt = SystemInfoReportGenerator.generatePlainTextReport(data, true);
        assertTrue(txt.contains("=== System Information ==="));
        assertTrue(txt.contains("Payload version: 3.1"));
        assertTrue(txt.contains("Running as admin: Yes"));
        assertTrue(txt.contains("Timings (ms):"));
        assertTrue(txt.contains("--- CPU ---"));
        assertTrue(txt.contains("Intel i7-12700"));
        assertTrue(txt.contains("--- Operating System ---"));
        assertTrue(txt.contains("TEST-PC"));
        assertTrue(txt.contains("--- Network Adapters ---"));
        assertTrue(txt.contains("Intel Wi-Fi 6E"));
    }

    @Test
    void plainTextReport_nullSafe() {
        String txt = SystemInfoReportGenerator.generatePlainTextReport(null, null);
        assertTrue(txt.contains("=== System Information ==="));
        assertTrue(txt.contains("(no data)"));
    }

    @Test
    void sectionText_extractsSingleTab() throws Exception {
        SystemInfoData data = sampleData();
        String cpu = SystemInfoReportGenerator.generateSectionText(data, "CPU");
        assertTrue(cpu.contains("--- CPU ---"));
        assertTrue(cpu.contains("Intel i7-12700"));
        assertFalse(cpu.contains("--- Operating System ---"));

        String missing = SystemInfoReportGenerator.generateSectionText(data, "Battery");
        assertTrue(missing.contains("no data"));

        String full = SystemInfoReportGenerator.generateSectionText(data, "Overview");
        assertTrue(full.contains("--- CPU ---"));
        assertTrue(full.contains("--- Operating System ---"));
    }

    @Test
    void htmlReport_escapesAndAnchors() throws Exception {
        SystemInfoData data = sampleData();
        String html = SystemInfoReportGenerator.generateHtmlReport(data, false);
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("href=\"#sec-CPU\""));
        assertTrue(html.contains("id=\"sec-OS\""));
        // Special chars in printer name must be escaped, not raw HTML.
        assertTrue(html.contains("OfficeJet &lt;Pro&gt; &amp; Co"));
        assertFalse(html.contains("OfficeJet <Pro> & Co"));
        assertTrue(html.contains("payload v3.1"));
    }

    @Test
    void htmlReport_nullSafe() {
        String html = SystemInfoReportGenerator.generateHtmlReport(null, null);
        assertTrue(html.contains("(no data)"));
    }

    @Test
    void timings_surviveRoundTrip() throws Exception {
        SystemInfoData data = sampleData();
        assertNotNull(data.timings());
        String json = com.sbtools.util.JsonMapper.mapper().writeValueAsString(data);
        SystemInfoData back = SystemInfoService.parseTolerantly(json);
        assertEquals(data.timings(), back.timings());
        assertEquals(data.collectedAt(), back.collectedAt());
        assertEquals(Map.of("cpu", 12L, "totalMs", 120L), back.timings());
    }

    @Test
    void mergeGroup_skippedNullNeverClobbersRealData() throws Exception {
        var mapper = com.sbtools.util.JsonMapper.mapper();
        var merged = mapper.createObjectNode();
        var warnings = new java.util.ArrayList<String>();
        var timings = new java.util.LinkedHashMap<String, Long>();

        // Owning group delivers real GPU + network data.
        var owner = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                "{\"gpu\":[{\"name\":\"RTX\"}],\"networkAdapters\":[{\"name\":\"WiFi\"}],"
                        + "\"warnings\":[],\"timings\":{\"gpu\":100,\"totalMs\":500}}");
        SystemInfoService.mergeGroup(merged, warnings, timings,
                new SystemInfoService.GroupResult(owner, "3.1", null, Map.of("gpu", 100L, "totalMs", 500L)));
        assertEquals(1, merged.get("gpu").size());

        // Skipped group (v3.1 $null semantics) must not clobber.
        var skipped = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                "{\"gpu\":null,\"networkAdapters\":null,\"warnings\":[],\"timings\":{\"gpu\":0}}");
        SystemInfoService.mergeGroup(merged, warnings, timings,
                new SystemInfoService.GroupResult(skipped, "3.1", null, Map.of("gpu", 0L)));
        assertEquals(1, merged.get("gpu").size());
        assertEquals(1, merged.get("networkAdapters").size());
        // Real timing survives later zero.
        assertEquals(100L, timings.get("gpu"));
    }

    @Test
    void mergeGroup_emptyArrayNeverClobbersNonEmpty() throws Exception {
        var mapper = com.sbtools.util.JsonMapper.mapper();
        var merged = mapper.createObjectNode();
        var warnings = new java.util.ArrayList<String>();
        var timings = new java.util.LinkedHashMap<String, Long>();

        var owner = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                "{\"printers\":[{\"name\":\"P\"}],\"warnings\":[]}");
        SystemInfoService.mergeGroup(merged, warnings, timings,
                new SystemInfoService.GroupResult(owner, "3.1", null, Map.of()));
        // Legacy skipped-as-empty-array (old scripts) must not clobber in any order.
        var legacySkipped = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                "{\"printers\":[],\"warnings\":[]}");
        SystemInfoService.mergeGroup(merged, warnings, timings,
                new SystemInfoService.GroupResult(legacySkipped, "3.0", null, Map.of()));
        assertEquals(1, merged.get("printers").size());
    }
}
