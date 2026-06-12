package com.sbtools.netoptimizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OperationResultTest {

    @Test
    void okWithMessage() {
        OperationResult r = OperationResult.ok("done");
        assertTrue(r.success());
        assertEquals("done", r.message());
        assertNull(r.details());
    }

    @Test
    void okWithDetails() {
        OperationResult r = OperationResult.ok("done", "extra info");
        assertTrue(r.success());
        assertEquals("done", r.message());
        assertEquals("extra info", r.details());
    }

    @Test
    void failWithMessage() {
        OperationResult r = OperationResult.fail("error");
        assertFalse(r.success());
        assertEquals("error", r.message());
        assertNull(r.details());
    }

    @Test
    void failWithDetails() {
        OperationResult r = OperationResult.fail("error", "stack trace");
        assertFalse(r.success());
        assertEquals("error", r.message());
        assertEquals("stack trace", r.details());
    }
}
