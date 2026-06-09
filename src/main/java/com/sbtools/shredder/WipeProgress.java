package com.sbtools.shredder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class WipeProgress {

    private String drive;
    private int percent;
    private int pass;
    private int totalPasses;
    private boolean done;
    private String message;
    private String tempFile;

    public WipeProgress() {}

    @JsonProperty("drive")
    public String getDrive() { return drive; }
    public void setDrive(String v) { drive = v; }

    @JsonProperty("percent")
    public int getPercent() { return percent; }
    public void setPercent(int v) { percent = v; }

    @JsonProperty("pass")
    public int getPass() { return pass; }
    public void setPass(int v) { pass = v; }

    @JsonProperty("totalPasses")
    public int getTotalPasses() { return totalPasses; }
    public void setTotalPasses(int v) { totalPasses = v; }

    @JsonProperty("done")
    public boolean isDone() { return done; }
    public void setDone(boolean v) { done = v; }

    @JsonProperty("message")
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }

    @JsonProperty("tempFile")
    public String getTempFile() { return tempFile; }
    public void setTempFile(String v) { tempFile = v; }
}
