package com.sbtools.diskhealth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DiskHealthInfo {

    private final StringProperty driveLetter = new SimpleStringProperty("");
    private final StringProperty model = new SimpleStringProperty("");
    private final StringProperty serialNumber = new SimpleStringProperty("");
    private final StringProperty interfaceType = new SimpleStringProperty("");
    private final StringProperty mediaType = new SimpleStringProperty("Unknown");
    private final LongProperty sizeBytes = new SimpleLongProperty(0);
    private final StringProperty healthStatus = new SimpleStringProperty("Unknown");
    private final StringProperty operationalStatus = new SimpleStringProperty("Unknown");
    private final IntegerProperty temperature = new SimpleIntegerProperty(-1);
    private final LongProperty powerOnHours = new SimpleLongProperty(-1);
    private final IntegerProperty wearLevel = new SimpleIntegerProperty(-1);
    private final LongProperty reallocatedSectors = new SimpleLongProperty(-1);
    private final LongProperty currentPendingSectorCount = new SimpleLongProperty(-1);
    private final LongProperty uncorrectableSectorCount = new SimpleLongProperty(-1);
    private final LongProperty loadCycleCount = new SimpleLongProperty(-1);
    private final LongProperty powerCycleCount = new SimpleLongProperty(-1);
    private final LongProperty totalHostReads = new SimpleLongProperty(-1);
    private final LongProperty totalHostWrites = new SimpleLongProperty(-1);
    private final StringProperty dataSource = new SimpleStringProperty("wmi");

    public DiskHealthInfo() {}

    @JsonProperty("driveLetter")
    public String getDriveLetter() { return driveLetter.get(); }
    public StringProperty driveLetterProperty() { return driveLetter; }
    public void setDriveLetter(String v) { driveLetter.set(v); }

    @JsonProperty("model")
    public String getModel() { return model.get(); }
    public StringProperty modelProperty() { return model; }
    public void setModel(String v) { model.set(v); }

    @JsonProperty("serialNumber")
    public String getSerialNumber() { return serialNumber.get(); }
    public StringProperty serialNumberProperty() { return serialNumber; }
    public void setSerialNumber(String v) { serialNumber.set(v); }

    @JsonProperty("interfaceType")
    public String getInterfaceType() { return interfaceType.get(); }
    public StringProperty interfaceTypeProperty() { return interfaceType; }
    public void setInterfaceType(String v) { interfaceType.set(v); }

    @JsonProperty("mediaType")
    public String getMediaType() { return mediaType.get(); }
    public StringProperty mediaTypeProperty() { return mediaType; }
    public void setMediaType(String v) { mediaType.set(v); }

    @JsonProperty("sizeBytes")
    public long getSizeBytes() { return sizeBytes.get(); }
    public LongProperty sizeBytesProperty() { return sizeBytes; }
    public void setSizeBytes(long v) { sizeBytes.set(v); }

    @JsonProperty("healthStatus")
    public String getHealthStatus() { return healthStatus.get(); }
    public StringProperty healthStatusProperty() { return healthStatus; }
    public void setHealthStatus(String v) { healthStatus.set(v); }

    @JsonProperty("operationalStatus")
    public String getOperationalStatus() { return operationalStatus.get(); }
    public StringProperty operationalStatusProperty() { return operationalStatus; }
    public void setOperationalStatus(String v) { operationalStatus.set(v); }

    @JsonProperty("temperature")
    public int getTemperature() { return temperature.get(); }
    public IntegerProperty temperatureProperty() { return temperature; }
    public void setTemperature(int v) { temperature.set(v); }

    @JsonProperty("powerOnHours")
    public long getPowerOnHours() { return powerOnHours.get(); }
    public LongProperty powerOnHoursProperty() { return powerOnHours; }
    public void setPowerOnHours(long v) { powerOnHours.set(v); }

    @JsonProperty("wearLevel")
    public int getWearLevel() { return wearLevel.get(); }
    public IntegerProperty wearLevelProperty() { return wearLevel; }
    public void setWearLevel(int v) { wearLevel.set(v); }

    @JsonProperty("reallocatedSectors")
    public long getReallocatedSectors() { return reallocatedSectors.get(); }
    public LongProperty reallocatedSectorsProperty() { return reallocatedSectors; }
    public void setReallocatedSectors(long v) { reallocatedSectors.set(v); }

    @JsonProperty("currentPendingSectorCount")
    public long getCurrentPendingSectorCount() { return currentPendingSectorCount.get(); }
    public LongProperty currentPendingSectorCountProperty() { return currentPendingSectorCount; }
    public void setCurrentPendingSectorCount(long v) { currentPendingSectorCount.set(v); }

    @JsonProperty("uncorrectableSectorCount")
    public long getUncorrectableSectorCount() { return uncorrectableSectorCount.get(); }
    public LongProperty uncorrectableSectorCountProperty() { return uncorrectableSectorCount; }
    public void setUncorrectableSectorCount(long v) { uncorrectableSectorCount.set(v); }

    @JsonProperty("loadCycleCount")
    public long getLoadCycleCount() { return loadCycleCount.get(); }
    public LongProperty loadCycleCountProperty() { return loadCycleCount; }
    public void setLoadCycleCount(long v) { loadCycleCount.set(v); }

    @JsonProperty("powerCycleCount")
    public long getPowerCycleCount() { return powerCycleCount.get(); }
    public LongProperty powerCycleCountProperty() { return powerCycleCount; }
    public void setPowerCycleCount(long v) { powerCycleCount.set(v); }

    @JsonProperty("totalHostReads")
    public long getTotalHostReads() { return totalHostReads.get(); }
    public LongProperty totalHostReadsProperty() { return totalHostReads; }
    public void setTotalHostReads(long v) { totalHostReads.set(v); }

    @JsonProperty("totalHostWrites")
    public long getTotalHostWrites() { return totalHostWrites.get(); }
    public LongProperty totalHostWritesProperty() { return totalHostWrites; }
    public void setTotalHostWrites(long v) { totalHostWrites.set(v); }

    @JsonProperty("dataSource")
    public String getDataSource() { return dataSource.get(); }
    public StringProperty dataSourceProperty() { return dataSource; }
    public void setDataSource(String v) { dataSource.set(v); }

    public boolean isSsd() { return "SSD".equalsIgnoreCase(mediaType.get()); }

    public String getSizeFormatted() {
        return formatBytes(sizeBytes.get());
    }

    public String getHealthStatusFormatted() {
        return healthStatus.get();
    }

    public boolean isHealthOk() {
        String s = healthStatus.get();
        return "Healthy".equalsIgnoreCase(s) || "OK".equalsIgnoreCase(s);
    }

    public boolean isHealthCaution() {
        String s = healthStatus.get();
        return "Caution".equalsIgnoreCase(s) || "Degraded".equalsIgnoreCase(s);
    }

    public boolean isHealthCritical() {
        String s = healthStatus.get();
        return "Critical".equalsIgnoreCase(s) || "Pred Fail".equalsIgnoreCase(s);
    }

    public boolean isSmartDataAvailable() {
        return temperature.get() >= 0 || powerOnHours.get() >= 0 || wearLevel.get() >= 0
                || reallocatedSectors.get() >= 0 || totalHostReads.get() >= 0;
    }

    public int getSmartFieldCount() {
        int count = 0;
        if (temperature.get() >= 0) count++;
        if (powerOnHours.get() >= 0) count++;
        if (wearLevel.get() >= 0) count++;
        if (reallocatedSectors.get() >= 0) count++;
        if (currentPendingSectorCount.get() >= 0) count++;
        if (uncorrectableSectorCount.get() >= 0) count++;
        if (totalHostReads.get() >= 0) count++;
        if (totalHostWrites.get() >= 0) count++;
        return count;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format("%.1f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }

    public static String formatDuration(long hours) {
        if (hours < 0) return "N/A";
        if (hours < 24) return hours + "h";
        long days = hours / 24;
        long hrs = hours % 24;
        if (days < 365) return days + "d " + hrs + "h";
        long years = days / 365;
        long remDays = days % 365;
        return years + "y " + remDays + "d";
    }
}
