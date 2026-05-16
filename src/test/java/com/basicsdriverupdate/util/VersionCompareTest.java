package com.basicsdriverupdate.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionCompareTest {

    @Test
    void detectsOlderVersion() {
        assertTrue(VersionCompare.isOlder("31.0.15.3623", "32.0.15.6094"));
        assertFalse(VersionCompare.isOlder("32.0.15.6094", "31.0.15.3623"));
    }
}
