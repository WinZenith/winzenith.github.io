package com.sbtools.backup;

/** Rollback coverage must not claim a merge undid a partial registry import. */
public final class RegistryRollbackChecks {
    public static void main(String[] args) {
        check(RegistryBackupSafety.rollbackCoverageSufficient(true, false, true),
                "snapshot can replace an existing key");
        check(RegistryBackupSafety.rollbackCoverageSufficient(false, true, true),
                "snapshot can replace a key whose presence is unknown");
        check(!RegistryBackupSafety.rollbackCoverageSufficient(true, false, false),
                "existing key without a snapshot cannot be undone");
        check(!RegistryBackupSafety.rollbackCoverageSufficient(false, true, false),
                "unknown presence without a snapshot is not safe to delete");
        check(RegistryBackupSafety.rollbackCoverageSufficient(false, false, false),
                "confirmed-absent key is undone by delete");

        String name = RegistryBackupSafety.preRestoreFileName(
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run");
        check("pre-restore_HKLM_SOFTWARE_Microsoft_Windows_CurrentVersion_Run.reg".equals(name), name);
        check(name.equals(RegistryBackupSafety.preRestoreFileName(
                "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run")),
                "long hive name must match HKLM snapshot file");

        String undone = RegistryBackupSafety.partialImportFailureMessage("second.reg", 1, true);
        check(undone.contains("removed and, where a pre-restore export existed, replaced"), undone);
        check(!undone.contains("re-applied"), "undone message must not say re-applied: " + undone);

        String stuck = RegistryBackupSafety.partialImportFailureMessage("second.reg", 1, false);
        check(stuck.contains("could not undo"), stuck);
        check(!stuck.contains("replaced from that export"), "failed rollback must not claim restore: " + stuck);
        check(!stuck.contains("re-applied"), "failed rollback must not say re-applied: " + stuck);
        System.out.println("ok");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
