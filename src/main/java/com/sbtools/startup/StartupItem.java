package com.sbtools.startup;

import java.util.UUID;

public class StartupItem {
    private String id;
    private String name;
    private String publisher;
    private String path;
    private boolean enabled;
    private String location;
    private String registryValueName;
    private String filePath;
    private String taskPath;
    private StartupItemType type;
    private String serviceStartType;

    public StartupItem() {
        this.id = UUID.randomUUID().toString();
    }

    public StartupItem(String name, String publisher, String path, boolean enabled, String location,
                       String registryValueName, String filePath, String taskPath) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.publisher = publisher;
        this.path = path;
        this.enabled = enabled;
        this.location = location;
        this.registryValueName = registryValueName;
        this.filePath = filePath;
        this.taskPath = taskPath;
    }

    public StartupItem(String name, String publisher, String path, boolean enabled, String location,
                       String registryValueName, String filePath, String taskPath,
                       StartupItemType type, String serviceStartType) {
        this.id = UUID.randomUUID().toString();
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

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
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

    public void setRegistryValueName(String registryValueName) {
        this.registryValueName = registryValueName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getTaskPath() {
        return taskPath;
    }

    public void setTaskPath(String taskPath) {
        this.taskPath = taskPath;
    }

    public StartupItemType getType() {
        return type;
    }

    public void setType(StartupItemType type) {
        this.type = type;
    }

    public String getServiceStartType() {
        return serviceStartType;
    }

    public void setServiceStartType(String serviceStartType) {
        this.serviceStartType = serviceStartType;
    }
}
