package com.sbtools.startup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StartupServiceTest {

    @Test
    void parseTaskPrincipal_extractsUserAndLogonType() {
        String xml = "<Task><Principals><Principal><UserId>DOMAIN\\user</UserId>"
                + "<LogonType>Password</LogonType></Principal></Principals></Task>";
        String[] p = StartupService.parseTaskPrincipal(xml);
        assertEquals("DOMAIN\\user", p[0]);
        assertEquals("Password", p[1]);
    }

    @Test
    void toggleStatus_criticalServiceThrowsWithoutConsent() {
        StartupService service = new StartupService();
        StartupItem critical = new StartupItem("RpcSs", "Microsoft", "C:\\x.exe", true,
                "Start Type: Automatic", "", "", "", StartupItemType.SERVICE, "Automatic");
        assertThrows(SecurityException.class, () -> service.toggleStatus(critical));
    }
}
