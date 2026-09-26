package com.sbtools.drivers.catalog;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Static checks for bundled catalog vendor/download URLs (no network I/O). */
public final class CatalogVendorUrlValidator {

    private CatalogVendorUrlValidator() {
    }

    public static List<String> validateEntries(List<CatalogEntry> entries) {
        List<String> errors = new ArrayList<>();
        if (entries == null) {
            return errors;
        }
        for (CatalogEntry e : entries) {
            if (e == null || e.testOnly()) {
                continue;
            }
            for (String field : new String[]{"vendorPageUrl", "sourceUrl"}) {
                String url = "vendorPageUrl".equals(field) ? e.vendorPageUrl() : e.sourceUrl();
                if (url == null || url.isBlank()) {
                    continue;
                }
                String id = e.id() != null ? e.id() : "?";
                if (AbstractOemCatalogProvider.isKnownDeadVendorUrl(url)) {
                    errors.add(id + ": " + field + " uses known-dead URL: " + url);
                    continue;
                }
                String sanitized = DriverCatalogDatabase.sanitizeSourceUrl(url);
                if (sanitized.isBlank()) {
                    errors.add(id + ": " + field + " is not a valid https URL: " + url);
                    continue;
                }
                try {
                    URI uri = URI.create(sanitized);
                    if (!"https".equalsIgnoreCase(uri.getScheme())) {
                        errors.add(id + ": " + field + " must use https: " + url);
                    }
                } catch (Exception ex) {
                    errors.add(id + ": " + field + " is malformed: " + url);
                }
            }
        }
        return errors;
    }
}
