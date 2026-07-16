package com.sbtools.shredder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FolderDeleteResult {

    private boolean success;
    private String message;
    private int filesDeleted;
    private int foldersDeleted;
    private List<String> scheduledForReboot = new ArrayList<>();

    public FolderDeleteResult() {}

    public FolderDeleteResult(boolean success, String message, int filesDeleted, int foldersDeleted, List<String> scheduledForReboot) {
        this.success = success;
        this.message = message;
        this.filesDeleted = filesDeleted;
        this.foldersDeleted = foldersDeleted;
        this.scheduledForReboot = scheduledForReboot != null ? scheduledForReboot : new ArrayList<>();
    }

    @JsonProperty("success")
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean v) { success = v; }

    @JsonProperty("message")
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }

    @JsonProperty("filesDeleted")
    public int getFilesDeleted() { return filesDeleted; }
    public void setFilesDeleted(int v) { filesDeleted = v; }

    @JsonProperty("foldersDeleted")
    public int getFoldersDeleted() { return foldersDeleted; }
    public void setFoldersDeleted(int v) { foldersDeleted = v; }

    @JsonProperty("scheduledForReboot")
    public List<String> getScheduledForReboot() { return scheduledForReboot; }
    public void setScheduledForReboot(List<String> v) { scheduledForReboot = v; }
}
