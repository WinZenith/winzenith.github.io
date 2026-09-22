package com.sbtools.defrag;

/** Shared disk-op rules that must not depend on JavaFX. */
public final class DiskOpPolicy {

    private DiskOpPolicy() {}

    /** Full defrag is allowed only when Windows reports the media as HDD. */
    public static boolean confirmedHdd(String mediaType) {
        return mediaType != null && "HDD".equalsIgnoreCase(mediaType.trim());
    }

    public static String cancelledDefragMessage(int finished, int total) {
        return "Intelligent Defrag cancelled (" + finished + " of " + total + " finished).";
    }
}
