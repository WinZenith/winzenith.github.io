package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class MemoryDumpsCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.MEMORY_DUMPS; }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "WINDIR", "Minidump");
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    var matched = files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".dmp")).toList();
                    for (Path f : matched) { totalSize += f.toFile().length(); itemCount++; }
                } catch (Exception ignored) {}
            }
        }
        String sysdrive = CleanerUtils.safeEnv("SYSTEMDRIVE");
        if (sysdrive != null) {
            String rootDrive = sysdrive.endsWith("\\") ? sysdrive : sysdrive + "\\";
            for (String name : new String[]{"memory.dmp", "SWA.DMP"}) {
                Path dump = Paths.get(rootDrive, name);
                if (Files.isRegularFile(dump)) { totalSize += dump.toFile().length(); itemCount++; }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "WINDIR", "Minidump");
        String sysdrive = CleanerUtils.safeEnv("SYSTEMDRIVE");
        if (sysdrive != null) {
            String rootDrive = sysdrive.endsWith("\\") ? sysdrive : sysdrive + "\\";
            for (String name : new String[]{"memory.dmp", "SWA.DMP"}) {
                if (token != null && token.isCancelled()) break;
                Path dump = Paths.get(rootDrive, name);
                if (Files.isRegularFile(dump)) { long size = dump.toFile().length(); CleanerUtils.deletePermanently(dump, token); if (!Files.exists(dump)) cleaned += size; }
            }
        }
        for (Path dir : dirs) {
            if (token != null && token.isCancelled()) break;
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (token != null && token.isCancelled()) break;
                        if (Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".dmp")) {
                            long size = Files.size(f); CleanerUtils.deletePermanently(f, token); if (!Files.exists(f)) cleaned += size;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }
}
