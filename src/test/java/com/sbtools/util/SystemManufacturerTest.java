package com.sbtools.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemManufacturerTest {

    @Test
    void detectReturnsNonNull() {
        SystemManufacturer.Manufacturer mfr = SystemManufacturer.get();
        assertNotNull(mfr);
    }

    @Test
    void getNameReturnsNonNull() {
        String name = SystemManufacturer.getName();
        assertNotNull(name);
    }

    @Test
    void manufacturerEnumContainsExpectedValues() {
        SystemManufacturer.Manufacturer[] values = SystemManufacturer.Manufacturer.values();
        assertTrue(values.length >= 5);
        assertNotNull(SystemManufacturer.Manufacturer.LENOVO);
        assertNotNull(SystemManufacturer.Manufacturer.DELL);
        assertNotNull(SystemManufacturer.Manufacturer.HP);
    }
}
