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
    private static volatile Path sharedDir;

    private PowerShellScripts() {
    }

    public static Path resolve(String scriptFileName) throws IOException {
        Path cached = CACHE.get(scriptFileName);
        if (cached != null && Files.exists(cached)) return cached;
        synchronized (PowerShellScripts.class) {
            cached = CACHE.get(scriptFileName);
            if (cached != null && Files.exists(cached)) return cached;
            Path extracted = extract(scriptFileName);
            Path existing = CACHE.putIfAbsent(scriptFileName, extracted);
            return existing != null ? existing : extracted;
        }
    }

    private static synchronized Path getSharedDir() throws IOException {
        if (sharedDir != null && Files.isDirectory(sharedDir)) return sharedDir;
        Path dir = AppPaths.scriptBaseDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // Portable dir not writable — fallback to LOCALAPPDATA
            Path fallback = AppPaths.localAppData().resolve("ps-scripts");
            Files.createDirectories(fallback);
            dir = fallback;
        }
        // Cleanup stale temp dirs from old versions (bsd-scripts-*)
        try {
            Path tmpBase = Path.of(System.getProperty("java.io.tmpdir"));
            try (var stream = Files.list(tmpBase)) {
                stream.filter(p -> p.getFileName().toString().startsWith("bsd-scripts-"))
                      .forEach(p -> {
                          try {
                              // Only delete if older than 1 day to avoid deleting actively used dirs
                              var age = java.time.Duration.between(
                                      Files.getLastModifiedTime(p).toInstant(), java.time.Instant.now());
                              if (age.toHours() > 24) {
                                  Files.walkFileTree(p, new java.nio.file.SimpleFileVisitor<>() {
                                      @Override
                                      public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                                          Files.deleteIfExists(file);
                                          return java.nio.file.FileVisitResult.CONTINUE;
                                      }
                                      @Override
                                      public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                                          Files.deleteIfExists(d);
                                          return java.nio.file.FileVisitResult.CONTINUE;
                                      }
                                  });
                              }
                          } catch (Exception ignored) {}
                      });
            }
        } catch (Exception ignored) {}
        sharedDir = dir;
        return dir;
    }

    private static Path extract(String scriptFileName) throws IOException {
        String resource = "/powershell/" + scriptFileName;
        try (InputStream in = PowerShellScripts.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing resource: " + resource);
            }
            Path dir = getSharedDir();
            Path script = dir.resolve(scriptFileName);
            // Write atomically via unique temp file then move to avoid concurrent collisions
            Path tmp = Files.createTempFile(dir, "." + scriptFileName + ".", ".tmp");
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, script, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, script, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
            return script;
        } catch (IOException e) {
            throw new IOException("Failed to extract " + scriptFileName, e);
        }
    }
}
