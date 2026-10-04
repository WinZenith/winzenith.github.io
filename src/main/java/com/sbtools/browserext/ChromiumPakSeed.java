package com.sbtools.browserext;

import com.sbtools.util.AppLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Locates Chromium {@code resources.pak} seeds for Secure Preferences MACs. */
final class ChromiumPakSeed {

    private ChromiumPakSeed() {
    }

    static List<byte[]> candidatesNearProfile(Path profileDir) {
        Set<String> seen = new LinkedHashSet<>();
        List<byte[]> out = new ArrayList<>();
        addSeed(out, seen, ChromiumPrefMac.DEFAULT_SEED);
        if (profileDir == null) return out;
        Path userData = profileDir.getParent();
        if (userData == null) return out;
        String norm = userData.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        List<Path> pakPaths = new ArrayList<>();
        if (norm.contains("google\\chrome")) {
            pakPaths.addAll(globPaks(localAppData().resolve("Google/Chrome/Application")));
        } else if (norm.contains("microsoft\\edge")) {
            pakPaths.addAll(globPaks(programFiles().resolve("Microsoft/Edge/Application")));
            pakPaths.addAll(globPaks(programFilesX86().resolve("Microsoft/Edge/Application")));
        } else if (norm.contains("brave")) {
            pakPaths.addAll(globPaks(localAppData().resolve("BraveSoftware/Brave-Browser/Application")));
        } else if (norm.contains("vivaldi")) {
            pakPaths.addAll(globPaks(localAppData().resolve("Vivaldi/Application")));
        }
        for (Path pak : pakPaths) {
            byte[] seed = readSeedFromPak(pak);
            if (seed != null) addSeed(out, seen, seed);
        }
        return out;
    }

    private static void addSeed(List<byte[]> out, Set<String> seen, byte[] seed) {
        if (seed == null || seed.length == 0) return;
        String key = java.util.Arrays.toString(seed);
        if (seen.add(key)) out.add(seed);
    }

    private static List<Path> globPaks(Path applicationDir) {
        List<Path> out = new ArrayList<>();
        if (applicationDir == null || !Files.isDirectory(applicationDir)) return out;
        try (Stream<Path> versions = Files.list(applicationDir)) {
            versions.filter(Files::isDirectory).forEach(ver -> {
                Path pak = ver.resolve("resources.pak");
                if (Files.isRegularFile(pak)) out.add(pak);
            });
        } catch (IOException ignored) {
        }
        return out;
    }

    /**
     * Grit pak v5: first resource payload is the 32-byte pref hash seed on Windows Chrome builds.
     */
    static byte[] readSeedFromPak(Path pak) {
        if (pak == null || !Files.isRegularFile(pak)) return null;
        try {
            byte[] data = Files.readAllBytes(pak);
            if (data.length < 16) return null;
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int version = buf.getInt(0);
            if (version != 5) return null;
            int resourceCount = buf.getShort(5) & 0xFFFF;
            if (resourceCount <= 0) return null;
            int indexOffset = 8;
            int firstResourceOffset = -1;
            for (int i = 0; i < resourceCount; i++) {
                int entry = indexOffset + i * 6;
                if (entry + 6 > data.length) return null;
                int resourceOffset = readU24(data, entry + 2);
                if (i == 0) firstResourceOffset = resourceOffset;
            }
            if (firstResourceOffset < 0 || firstResourceOffset + 32 > data.length) return null;
            byte[] seed = new byte[32];
            System.arraycopy(data, firstResourceOffset, seed, 0, 32);
            return seed;
        } catch (Exception e) {
            AppLogger.warning("Failed to read pak seed from " + pak + ": " + e.getMessage());
            return null;
        }
    }

    private static int readU24(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | ((data[offset + 2] & 0xFF) << 16);
    }

    private static Path localAppData() {
        String v = System.getenv("LOCALAPPDATA");
        return v != null ? Path.of(v) : Path.of("");
    }

    private static Path programFiles() {
        String v = System.getenv("ProgramFiles");
        return v != null ? Path.of(v) : Path.of("");
    }

    private static Path programFilesX86() {
        String v = System.getenv("ProgramFiles(x86)");
        return v != null ? Path.of(v) : Path.of("");
    }
}
