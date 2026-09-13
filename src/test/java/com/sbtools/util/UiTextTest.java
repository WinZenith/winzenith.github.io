package com.sbtools.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UiTextTest {

    @Test
    void sentenceCaseExamples() {
        assertEquals("System information", UiText.label("System Information"));
        assertEquals("System cleanup", UiText.label("System cleanup"));
        assertEquals("Flush DNS cache", UiText.label("Flush DNS Cache"));
        assertEquals("Backup/rollback", UiText.label("Backup/Rollback"));
        assertEquals("REBOOT", UiText.label("REBOOT"));
    }

    @Test
    void idempotent() {
        assertEquals("Disk tools", UiText.label("Disk tools"));
        assertEquals("Network adapters", UiText.label("Network adapters"));
    }

    @Test
    void preservesAcronymsAndBrands() {
        assertEquals("Windows update cleanup", UiText.label("Windows Update Cleanup"));
        assertEquals("Wi-Fi", UiText.label("Wi-Fi"));
        assertEquals("iTunes backups", UiText.label("iTunes Backups"));
    }

    @Test
    void skipsLongOrMultiline() {
        String longLine = "A".repeat(81);
        assertEquals(longLine, UiText.label(longLine));
        assertEquals("Line one\nLine two", UiText.label("Line one\nLine two"));
    }

    @Test
    void nullAndEmpty() {
        assertNull(UiText.label(null));
        assertEquals("", UiText.label(""));
    }
}
