package com.sbtools.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionCompareTest {

    @Test
    void nvidiaGoldenPairs() {
        assertEquals(0, VersionCompare.compare("32.0.15.8157", "581.57"));
        assertEquals(0, VersionCompare.compare("31.0.15.3623", "536.23"));
        assertEquals(0, VersionCompare.compare("30.0.14.7212", "472.12"));
    }

    @Test
    void nvidiaOrdering() {
        assertTrue(VersionCompare.isOlder("536.23", "581.57"));
        assertTrue(VersionCompare.isNewer("581.57", "536.23"));
    }
}
