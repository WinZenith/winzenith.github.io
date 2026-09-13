package com.sbtools.uninstaller;

/** AppX listing failed (process exit, I/O, or invalid JSON). Distinct from an empty inventory. */
public class AppDiscoveryException extends RuntimeException {
    public AppDiscoveryException(String message) {
        super(message);
    }
}
