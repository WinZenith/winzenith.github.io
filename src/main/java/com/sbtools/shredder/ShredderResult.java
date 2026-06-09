package com.sbtools.shredder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ShredderResult {

    private boolean success;
    private boolean deleted;
    private boolean scheduledForReboot;
    private String message;

    public ShredderResult() {}

    public ShredderResult(String filePath, boolean success, boolean deleted, boolean scheduledForReboot, String message) {
        this.success = success;
        this.deleted = deleted;
        this.scheduledForReboot = scheduledForReboot;
        this.message = message;
    }

    @JsonProperty("success")
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean v) { success = v; }

    @JsonProperty("deleted")
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean v) { deleted = v; }

    @JsonProperty("scheduledForReboot")
    public boolean isScheduledForReboot() { return scheduledForReboot; }
    public void setScheduledForReboot(boolean v) { scheduledForReboot = v; }

    @JsonProperty("message")
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
}
