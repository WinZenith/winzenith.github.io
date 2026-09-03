package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class EmptyFoldersCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.EMPTY_FOLDERS; }

    @Override
    public void scan(CleanupRow row) {
        int count = 0;
        for (Path root : getRoots()) {
            if (root != null && Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root, 3)) {
                    count += (int) walk.filter(Files::isDirectory)
                            .filter(p -> !p.equals(root))
                            .filter(CleanerUtils::isEmptyDirectory).count();
                } catch (Exception ignored) {}
            }
        }
        row.setItemCount(count);
        row.setSizeOrCountText(count + " empty folder" + (count == 1 ? "" : "s"));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        for (Path root : getRoots()) {
            if (token != null && token.isCancelled()) break;
            if (root != null && Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root, 3)) {
                    List<Path> emptyDirs = walk.filter(Files::isDirectory)
                            .filter(p -> !p.equals(root))
                            .filter(CleanerUtils::isEmptyDirectory)
                            .sorted(Comparator.reverseOrder())
                            .toList();
                    for (Path dir : emptyDirs) {
                        if (token != null && token.isCancelled()) break;
                        try { Files.deleteIfExists(dir); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private List<Path> getRoots() {
        List<Path> roots = new ArrayList<>();
        CleanerUtils.addEnvPath(roots, "TEMP");
        return roots;
    }
}
