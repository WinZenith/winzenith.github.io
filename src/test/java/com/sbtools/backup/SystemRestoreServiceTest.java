package com.sbtools.backup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemRestoreServiceTest {

    @Test
    void parseCsvLineNormal() {
        SystemRestoreRow row = SystemRestoreService.parseCsvLine(
                "\"Driver install\",\"2025-06-01 12:00:00\",0,42");
        assertNotNull(row);
        assertEquals(42, row.sequenceNumber());
        assertEquals("Driver install", row.descriptionProperty().get());
        assertEquals("2025-06-01 12:00:00", row.creationTimeProperty().get());
    }

    @Test
    void parseCsvLineWithCommasInDescription() {
        SystemRestoreRow row = SystemRestoreService.parseCsvLine(
                "\"Driver update, NVIDIA, version 5.0\",\"2025-06-01 12:00:00\",0,55");
        assertNotNull(row);
        assertEquals(55, row.sequenceNumber());
        assertEquals("Driver update, NVIDIA, version 5.0", row.descriptionProperty().get());
    }

    @Test
    void parseCsvLineEmptyReturnsNull() {
        assertNull(SystemRestoreService.parseCsvLine(""));
    }

    @Test
    void parseCsvLineTooFewFieldsReturnsNull() {
        assertNull(SystemRestoreService.parseCsvLine("\"just description\",\"date\""));
    }

    @Test
    void parseCsvLineInvalidNumberReturnsNull() {
        assertNull(SystemRestoreService.parseCsvLine(
                "\"desc\",\"date\",notanumber,42"));
    }

    @Test
    void parseCsvLineEventTypeValues() {
        SystemRestoreRow row0 = SystemRestoreService.parseCsvLine(
                "\"test\",\"2025-01-01\",0,1");
        assertNotNull(row0);
        assertEquals("Application Install", row0.eventTypeProperty().get());

        SystemRestoreRow row1 = SystemRestoreService.parseCsvLine(
                "\"test\",\"2025-01-01\",1,2");
        assertNotNull(row1);
        assertEquals("Application Uninstall", row1.eventTypeProperty().get());

        SystemRestoreRow row12 = SystemRestoreService.parseCsvLine(
                "\"test\",\"2025-01-01\",12,3");
        assertNotNull(row12);
        assertEquals("Modify Settings", row12.eventTypeProperty().get());
    }

    @Test
    void unquoteHandlesQuotedStrings() {
        assertEquals("hello", SystemRestoreService.unquote("\"hello\""));
        assertEquals("with spaces", SystemRestoreService.unquote("\"with spaces\""));
    }

    @Test
    void unquoteHandlesUnquotedStrings() {
        assertEquals("plain", SystemRestoreService.unquote("plain"));
        assertEquals("trimmed", SystemRestoreService.unquote("  trimmed  "));
    }

    @Test
    void unquoteHandlesNullAndEmpty() {
        assertEquals("", SystemRestoreService.unquote(null));
        assertEquals("", SystemRestoreService.unquote(""));
    }
}
