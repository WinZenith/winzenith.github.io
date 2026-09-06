package com.sbtools.netoptimizer;

import java.util.Map;

/**
 * Read-only capture of TCP/global + registry tuning state before a mutating
 * network operation. Used for preview diff and guided restore.
 * No new writes are performed to create a snapshot.
 */
public record NetworkSnapshot(
        String id,
        String timestamp,
        String reason,
        Map<String, String> tcpSettings,
        String tcpAckFrequency,
        String tcpNoDelay
) {
    public NetworkSnapshot {
        if (id == null) id = "";
        if (timestamp == null) timestamp = "";
        if (reason == null) reason = "";
        if (tcpSettings == null) tcpSettings = Map.of();
    }

    public String summary() {
        int n = tcpSettings != null ? tcpSettings.size() : 0;
        return timestamp + " — " + reason + " (" + n + " TCP keys"
                + ", Ack=" + (tcpAckFrequency == null ? "default" : tcpAckFrequency)
                + ", NoDelay=" + (tcpNoDelay == null ? "default" : tcpNoDelay) + ")";
    }
}
