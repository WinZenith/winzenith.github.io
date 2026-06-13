package com.sbtools.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionCompareTest {

    @Test
    void testEqualVersions() {
        assertEquals(0, VersionCompare.compare("1.0.0", "1.0.0"));
        assertEquals(0, VersionCompare.compare("24.12.1", "24.12.1"));
    }

    @Test
    void testOlderVersion() {
        assertTrue(VersionCompare.isOlder("1.0.0", "2.0.0"));
        assertTrue(VersionCompare.isOlder("1.0.0", "1.1.0"));
        assertTrue(VersionCompare.isOlder("1.0.0", "1.0.1"));
        assertTrue(VersionCompare.isOlder("24.11.1", "24.12.1"));
    }

    @Test
    void testNewerVersion() {
        assertFalse(VersionCompare.isOlder("2.0.0", "1.0.0"));
        assertFalse(VersionCompare.isOlder("1.1.0", "1.0.0"));
        assertFalse(VersionCompare.isOlder("1.0.1", "1.0.0"));
    }

    @Test
    void testVersionWithSuffixes() {
        assertFalse(VersionCompare.isOlder("551.23", "551.23-hotfix"));
        assertFalse(VersionCompare.isOlder("24.40.0", "24.40.0-GA"));
        assertEquals(0, VersionCompare.compare("1.0.0-beta", "1.0.0"));
        assertTrue(VersionCompare.isOlder("24.40.0", "24.41.0-hotfix"));
    }

    @Test
    void testNullAndBlank() {
        assertEquals(0, VersionCompare.compare(null, null));
        assertEquals(0, VersionCompare.compare("", ""));
        assertTrue(VersionCompare.isOlder(null, "1.0.0"));
        assertFalse(VersionCompare.isOlder("1.0.0", null));
    }

    @Test
    void testDifferentLengths() {
        assertTrue(VersionCompare.isOlder("1.0", "1.0.0"));
        assertTrue(VersionCompare.isOlder("1.0.0", "1.0.0.0"));
    }

    @Test
    void testNvidiaStyleVersions() {
        assertTrue(VersionCompare.isOlder("31.0.101.5120", "32.0.101.5120"));
        assertTrue(VersionCompare.isOlder("31.0.101.5120", "31.1.101.5120"));
    }
}
