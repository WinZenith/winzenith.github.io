package com.sbtools.netoptimizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdapterStatisticsTest {

    @Test
    void emptyFactoryMethod() {
        AdapterStatistics s = AdapterStatistics.empty("Wi-Fi");
        assertEquals("Wi-Fi", s.adapterName());
        assertEquals(0, s.bytesSent());
        assertEquals(0, s.bytesReceived());
        assertEquals(0, s.errorPackets());
    }

    @Test
    void formatBytesBytes() {
        assertEquals("512 B", AdapterStatistics.formatBytes(512));
        assertEquals("0 B", AdapterStatistics.formatBytes(0));
        assertEquals("1023 B", AdapterStatistics.formatBytes(1023));
    }

    @Test
    void formatBytesKilobytes() {
        assertEquals("1.0 KB", AdapterStatistics.formatBytes(1024));
        assertEquals("1.5 KB", AdapterStatistics.formatBytes(1536));
    }

    @Test
    void formatBytesMegabytes() {
        assertEquals("1.0 MB", AdapterStatistics.formatBytes(1024 * 1024));
        assertEquals("2.5 MB", AdapterStatistics.formatBytes((long) (2.5 * 1024 * 1024)));
    }

    @Test
    void formatBytesGigabytes() {
        assertEquals("1.00 GB", AdapterStatistics.formatBytes(1024L * 1024 * 1024));
    }

    @Test
    void fullConstructor() {
        AdapterStatistics s = new AdapterStatistics(
                "Ethernet", 1000L, 2000L, 10L, 20L, 1L, 2L, 3L, 4L, 5L, 6L);
        assertEquals("Ethernet", s.adapterName());
        assertEquals(1000L, s.bytesSent());
        assertEquals(2000L, s.bytesReceived());
        assertEquals(10L, s.unicastPacketsSent());
        assertEquals(20L, s.unicastPacketsReceived());
        assertEquals(1L, s.multicastPacketsSent());
        assertEquals(2L, s.multicastPacketsReceived());
        assertEquals(3L, s.broadcastPacketsSent());
        assertEquals(4L, s.broadcastPacketsReceived());
        assertEquals(5L, s.discardedPackets());
        assertEquals(6L, s.errorPackets());
    }
}
