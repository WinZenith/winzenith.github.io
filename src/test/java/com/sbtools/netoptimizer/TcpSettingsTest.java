package com.sbtools.netoptimizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TcpSettingsTest {

    @Test
    void parseEmpty() {
        TcpSettings s = TcpSettings.parse(null);
        assertTrue(s.settings().isEmpty());
    }

    @Test
    void parseBlank() {
        TcpSettings s = TcpSettings.parse("  \n  ");
        assertTrue(s.settings().isEmpty());
    }

    @Test
    void parseSingleSetting() {
        TcpSettings s = TcpSettings.parse("TCP Global Parameters:\n    Receive-Side Scaling State          : enabled");
        assertEquals("enabled", s.get("Receive-Side Scaling State"));
    }

    @Test
    void parseMultipleSettings() {
        String input = """
                TCP Global Parameters
                ----------------------------------------------
                Receive-Side Scaling State          : enabled
                Chimney Offload State                : disabled
                Auto-Tuning Level                    : normal
                """;
        TcpSettings s = TcpSettings.parse(input);
        assertEquals("enabled", s.get("Receive-Side Scaling State"));
        assertEquals("disabled", s.get("Chimney Offload State"));
        assertEquals("normal", s.get("Auto-Tuning Level"));
    }

    @Test
    void getMissingKeyReturnsNA() {
        TcpSettings s = TcpSettings.parse("key: value");
        assertEquals("N/A", s.get("nonexistent"));
    }
}
