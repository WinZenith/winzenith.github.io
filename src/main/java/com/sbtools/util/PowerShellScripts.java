package com.sbtools.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PowerShellScripts {

    private static final Map<String, Path> CACHE = new ConcurrentHashMap<>();

    private PowerShellScripts() {
    }

    public static Path resolve(String scriptFileName) throws IOException {
        Path cached = CACHE.get(scriptFileName);
        if (cached != null) return cached;
        Path extracted = extract(scriptFileName);
        Path existing = CACHE.putIfAbsent(scriptFileName, extracted);
        return existing != null ? existing : extracted;
    }

    private static Path extract(String scriptFileName) throws IOException {
        String resource = "/powershell/" + scriptFileName;
        try (InputStream in = PowerShellScripts.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing resource: " + resource);
            }
            Path dir = Files.createTempDirectory("bsd-scripts-");
            Path script = dir.resolve(scriptFileName);
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
            script.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return script;
        } catch (IOException e) {
            throw new IOException("Failed to extract " + scriptFileName, e);
        }
    }
}
