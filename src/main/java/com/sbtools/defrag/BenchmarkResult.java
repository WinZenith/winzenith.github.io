package com.sbtools.defrag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BenchmarkResult {

    private boolean success;
    private String driveLetter;
    private int testSizeMB;
    private double seqWriteMBps;
    private double seqReadMBps;
    private double randomReadIOPS;
    private String message;

    public BenchmarkResult() {}

    @JsonProperty("success")
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean v) { success = v; }

    @JsonProperty("driveLetter")
    public String getDriveLetter() { return driveLetter; }
    public void setDriveLetter(String v) { driveLetter = v; }

    @JsonProperty("testSizeMB")
    public int getTestSizeMB() { return testSizeMB; }
    public void setTestSizeMB(int v) { testSizeMB = v; }

    @JsonProperty("seqWriteMBps")
    public double getSeqWriteMBps() { return seqWriteMBps; }
    public void setSeqWriteMBps(double v) { seqWriteMBps = v; }

    @JsonProperty("seqReadMBps")
    public double getSeqReadMBps() { return seqReadMBps; }
    public void setSeqReadMBps(double v) { seqReadMBps = v; }

    @JsonProperty("randomReadIOPS")
    public double getRandomReadIOPS() { return randomReadIOPS; }
    public void setRandomReadIOPS(double v) { randomReadIOPS = v; }

    @JsonProperty("message")
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
}
