package com.sbtools.cleaner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CleanerRegistryTest {

    @Test
    void everyCategoryHasMatchingCleaner() {
        assertEquals(40, CleanupCategory.values().length);
        for (CleanupCategory cat : CleanupCategory.values()) {
            CleanerExtension ext = CleanerRegistry.get(cat);
            assertNotNull(ext, cat.name());
            assertEquals(cat, ext.getCategory());
        }
        assertEquals(40, CleanerRegistry.all().size());
    }
}
