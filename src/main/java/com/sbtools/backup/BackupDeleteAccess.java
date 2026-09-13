package com.sbtools.backup;

import java.nio.file.Files;
import java.nio.file.Path;

/** Whether backup data can be removed without elevation (user-writable locations). */
public final class BackupDeleteAccess {

    private BackupDeleteAccess() {
    }

    /**
     * True when the current process can delete or replace content at {@code path}
     * (existing directory/file writable, or nearest existing ancestor writable).
     */
    public static boolean isPathDeletableByCurrentUser(Path path) {
        if (path == null) {
            return false;
        }
        try {
            Path norm = path.toAbsolutePath().normalize();
            if (Files.exists(norm)) {
                return Files.isWritable(norm);
            }
            Path parent = norm.getParent();
            while (parent != null && !Files.exists(parent)) {
                parent = parent.getParent();
            }
            return parent != null && Files.isWritable(parent);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isFileWritableByCurrentUser(Path file) {
        if (file == null) {
            return false;
        }
        try {
            Path norm = file.toAbsolutePath().normalize();
            if (Files.exists(norm)) {
                return Files.isWritable(norm);
            }
            Path parent = norm.getParent();
            return parent != null && Files.isWritable(parent);
        } catch (Exception e) {
            return false;
        }
    }
}
