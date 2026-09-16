package com.sbtools.systeminfo;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Resolves the System Information export target. Writes to the FileChooser
 * path when the user already typed a known extension; otherwise appends the
 * filter extension and flags overwrite when that rewritten path already exists.
 */
public final class SystemInfoExportPath {

    public record Resolution(File target, String extension, boolean needsOverwriteConfirm) {}

    private SystemInfoExportPath() {}

    public static Resolution resolve(File chooserFile, String filterExtension) {
        if (chooserFile == null) {
            return null;
        }
        String typedExt = typedExportExt(chooserFile.getName());
        if (typedExt != null) {
            return new Resolution(chooserFile, typedExt, false);
        }
        String ext = normalizeExt(filterExtension);
        if (ext == null) {
            ext = ".txt";
        }
        String base = chooserFile.getName();
        if (base == null || base.isBlank()) {
            base = "system-info";
        }
        File parent = chooserFile.getParentFile();
        File target = parent != null ? new File(parent, base + ext) : new File(base + ext);
        boolean confirm = target.exists() && !samePath(target, chooserFile);
        return new Resolution(target, ext, confirm);
    }

    static String typedExportExt(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return ".json";
        }
        if (lower.endsWith(".html")) {
            return ".html";
        }
        if (lower.endsWith(".txt")) {
            return ".txt";
        }
        return null;
    }

    static String normalizeExt(String filterExtension) {
        if (filterExtension == null || filterExtension.isBlank()) {
            return null;
        }
        String e = filterExtension.trim().replace("*", "");
        if (!e.startsWith(".")) {
            e = "." + e;
        }
        String lower = e.toLowerCase(Locale.ROOT);
        if (".json".equals(lower) || ".html".equals(lower) || ".txt".equals(lower)) {
            return lower;
        }
        return null;
    }

    static boolean samePath(File a, File b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }
}
