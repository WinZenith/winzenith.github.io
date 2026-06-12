package com.sbtools.netoptimizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OptimizationPresetTest {

    @Test
    void allPresetsHaveNames() {
        for (OptimizationPreset p : OptimizationPreset.values()) {
            assertNotNull(p.getDisplayName());
            assertFalse(p.getDisplayName().isBlank());
        }
    }

    @Test
    void allPresetsHaveDescriptions() {
        for (OptimizationPreset p : OptimizationPreset.values()) {
            assertNotNull(p.getDescription());
            assertFalse(p.getDescription().isBlank());
        }
    }

    @Test
    void presetCount() {
        assertEquals(4, OptimizationPreset.values().length);
    }

    @Test
    void valueOfRoundtrip() {
        for (OptimizationPreset p : OptimizationPreset.values()) {
            assertEquals(p, OptimizationPreset.valueOf(p.name()));
        }
    }
}
