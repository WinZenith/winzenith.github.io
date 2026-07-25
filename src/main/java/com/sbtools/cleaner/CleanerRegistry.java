package com.sbtools.cleaner;

import com.sbtools.cleaner.impl.*;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class CleanerRegistry {

    private static final Map<CleanupCategory, CleanerExtension> REGISTRY;

    static {
        Map<CleanupCategory, CleanerExtension> map = new HashMap<>();
        register(new RegistryCleaner(), map);
        register(new RecycleBinCleaner(), map);
        register(new JunkFilesCleaner(), map);
        register(new PrivacyTracesCleaner(), map);
        register(new BrowserTracesCleaner(), map);
        register(new CacheCleaner(), map);
        register(new InstallerFilesCleaner(), map);
        register(new TempSystemFilesCleaner(), map);
        register(new MemoryDumpsCleaner(), map);
        register(new WindowsErrorReportingCleaner(), map);
        register(new WindowsUpdateCleanupCleaner(), map);
        register(new ThumbnailCacheCleaner(), map);
        register(new EmptyFoldersCleaner(), map);
        register(new NotificationHistoryCleaner(), map);
        register(new FontCacheCleaner(), map);
        register(new TaskbarJumpListsCleaner(), map);
        register(new OfficeDocumentCacheCleaner(), map);
        register(new WindowsDefenderCacheCleaner(), map);
        register(new WindowsLogFilesCleaner(), map);
        register(new WindowsStoreCacheCleaner(), map);
        register(new OtherProgramsCacheCleaner(), map);
        register(new ShaderCacheCleaner(), map);
        register(new SoftwareDistributionCacheCleaner(), map);
        register(new DiagnosticsCacheCleaner(), map);
        register(new OldWindowsInstallCleaner(), map);
        register(new DockerCacheCleaner(), map);
        register(new NpmCacheCleaner(), map);
        register(new YarnCacheCleaner(), map);
        register(new MavenCacheCleaner(), map);
        register(new GradleCacheCleaner(), map);
        register(new PipCacheCleaner(), map);
        register(new JetBrainsCacheCleaner(), map);
        register(new ItunesBackupsCleaner(), map);
        register(new WindowsSearchCacheCleaner(), map);
        REGISTRY = Collections.unmodifiableMap(map);
    }

    private static void register(CleanerExtension ext, Map<CleanupCategory, CleanerExtension> map) {
        map.put(ext.getCategory(), ext);
    }

    public static CleanerExtension get(CleanupCategory category) {
        return REGISTRY.get(category);
    }

    public static Collection<CleanerExtension> all() {
        return REGISTRY.values();
    }
}
