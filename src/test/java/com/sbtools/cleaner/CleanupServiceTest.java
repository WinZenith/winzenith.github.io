package com.sbtools.cleaner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CleanupServiceTest {

    @Test
    void categoriesExcludingFiltersIgnored() {
        List<CleanupCategory> active = CleanupService.categoriesExcluding(
                List.of(CleanupCategory.CACHE.name(), "NOT_A_CATEGORY"));
        assertFalse(active.contains(CleanupCategory.CACHE));
        assertEquals(CleanupCategory.values().length - 1, active.size());
    }
}
