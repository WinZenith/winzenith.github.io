package com.sbtools.drivers.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * A structured driver catalog entry that enables metadata-first matching
 * instead of brittle web scraping. Each entry maps a hardware device to a
 * known-good driver version with download URL, hash, and confidence score.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class CatalogEntry {

    private String id;
    private String provider;
    private String component;
    private List<String> hardwareIds;
    private MatchMethod matchMethod;
    private String matchValue;
    private String versionMin;
    private String versionMax;
    private String latestVersion;
    private String sourceUrl;
    private String vendorPageUrl;
    private String hashSha256;
    private boolean signed;
    private String certThumbprint;
    private double confidence;
    private Instant lastVerified;
    private List<String> tags;
    private String releaseDate;

    public CatalogEntry() {
    }

    public CatalogEntry(String id, String provider, String component,
                        List<String> hardwareIds, MatchMethod matchMethod, String matchValue,
                        String latestVersion, String sourceUrl) {
        this.id = id;
        this.provider = provider;
        this.component = component;
        this.hardwareIds = hardwareIds;
        this.matchMethod = matchMethod;
        this.matchValue = matchValue;
        this.latestVersion = latestVersion;
        this.sourceUrl = sourceUrl;
        this.confidence = 0.8;
        this.signed = true;
    }

    public enum MatchMethod {
        HARDWARE_ID,
        NAME_REGEX,
        SIGNATURE,
        INF_METADATA,
        PACKAGE_ID
    }

    public String id() { return id; }
    public void setId(String id) { this.id = id; }

    public String provider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String component() { return component; }
    public void setComponent(String component) { this.component = component; }

    public List<String> hardwareIds() { return hardwareIds; }
    public void setHardwareIds(List<String> hardwareIds) { this.hardwareIds = hardwareIds; }

    public MatchMethod matchMethod() { return matchMethod; }
    public void setMatchMethod(MatchMethod matchMethod) { this.matchMethod = matchMethod; }

    public String matchValue() { return matchValue; }
    public void setMatchValue(String matchValue) { this.matchValue = matchValue; }

    public String versionMin() { return versionMin; }
    public void setVersionMin(String versionMin) { this.versionMin = versionMin; }

    public String versionMax() { return versionMax; }
    public void setVersionMax(String versionMax) { this.versionMax = versionMax; }

    public String latestVersion() { return latestVersion; }
    public void setLatestVersion(String latestVersion) { this.latestVersion = latestVersion; }

    public String sourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public String vendorPageUrl() { return vendorPageUrl; }
    public void setVendorPageUrl(String vendorPageUrl) { this.vendorPageUrl = vendorPageUrl; }

    public String hashSha256() { return hashSha256; }
    public void setHashSha256(String hashSha256) { this.hashSha256 = hashSha256; }

    public boolean signed() { return signed; }
    public void setSigned(boolean signed) { this.signed = signed; }

    public String certThumbprint() { return certThumbprint; }
    public void setCertThumbprint(String certThumbprint) { this.certThumbprint = certThumbprint; }

    public double confidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public Instant lastVerified() { return lastVerified; }
    public void setLastVerified(Instant lastVerified) { this.lastVerified = lastVerified; }

    public List<String> tags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String releaseDate() { return releaseDate; }
    public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
}
