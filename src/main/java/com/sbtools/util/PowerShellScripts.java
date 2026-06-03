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
        return CACHE.computeIfAbsent(scriptFileName, PowerShellScripts::extract);
    }

    private static Path extract(String scriptFileName) {
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
            throw new IllegalStateException("Failed to extract " + scriptFileName, e);
        }
    }
}
