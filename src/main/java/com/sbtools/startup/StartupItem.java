package com.sbtools.startup;

import java.util.List;

public class StartupItem {
    private final String name;
    private final String publisher;
    private final String path;
    private boolean enabled;
    private String location;
    private final String registryValueName;
    private final String filePath;
    private final String taskPath;
    private final StartupItemType type;
    private String serviceStartType;
    private double estimatedBootImpactMs;
    private List<String> dependencies;

    public StartupItem(String name, String publisher, String path, boolean enabled, String location,
                       String registryValueName, String filePath, String taskPath,
                       StartupItemType type, String serviceStartType) {
        this.name = name;
        this.publisher = publisher;
        this.path = path;
        this.enabled = enabled;
        this.location = location;
        this.registryValueName = registryValueName;
        this.filePath = filePath;
        this.taskPath = taskPath;
        this.type = type;
        this.serviceStartType = serviceStartType;
    }

    public String getName() {
        return name;
    }

    public String getPublisher() {
        return publisher;
    }

    public String getPath() {
        return path;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getRegistryValueName() {
        return registryValueName;
    }

    public String getTaskPath() {
        return taskPath;
    }

    public StartupItemType getType() {
        return type;
    }

    public String getServiceStartType() {
        return serviceStartType;
    }

    public void setServiceStartType(String serviceStartType) {
        this.serviceStartType = serviceStartType;
    }

    public double getEstimatedBootImpactMs() {
        return estimatedBootImpactMs;
    }

    public void setEstimatedBootImpactMs(double ms) {
        this.estimatedBootImpactMs = ms;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies;
    }
}
