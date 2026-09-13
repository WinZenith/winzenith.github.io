package com.sbtools.drivers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DriverInstallTrustTest {

    @Test
    void httpsVendorHostAccepted() {
        assertTrue(DriverInstallTrust.isTrustedHttpsUrl("https://www.nvidia.com/foo.exe", "Nvidia"));
        assertTrue(DriverInstallTrust.isTrustedHttpsUrl("https://download.nvidia.com/foo", "Nvidia"));
    }

    @Test
    void httpRejected() {
        assertFalse(DriverInstallTrust.isTrustedHttpsUrl("http://www.nvidia.com/foo.exe", "Nvidia"));
    }

    @Test
    void lookalikeRejected() {
        assertFalse(DriverInstallTrust.isTrustedHttpsUrl("https://notamd.com/x.exe", "AMD"));
        assertFalse(DriverInstallTrust.isTrustedHttpsUrl("https://amd.com.evil.test/x.exe", "AMD"));
    }
}
