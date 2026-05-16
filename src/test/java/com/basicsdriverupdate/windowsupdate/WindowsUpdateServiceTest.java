package com.basicsdriverupdate.windowsupdate;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsUpdateServiceTest {

    @Test
    void parseOsUpdatesFixture() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/fixtures/wu-os-sample.json"));
        List<OsUpdateEntry> list = WindowsUpdateService.parseOsUpdates(json);
        assertEquals(1, list.size());
        assertTrue(list.get(0).title().contains("Cumulative"));
        assertEquals("KB5037771", list.get(0).kbArticle());
    }
}
