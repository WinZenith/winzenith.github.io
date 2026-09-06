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
    /** Additive 4K random-read result (0 = not measured by older scripts). */
    private double randomRead4KIOPS;
    /** Additive average 4K read latency in ms (0 = not measured). */
    private double avg4KLatencyMs;

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

    @JsonProperty("randomRead4KIOPS")
    public double getRandomRead4KIOPS() { return randomRead4KIOPS; }
    public void setRandomRead4KIOPS(double v) { randomRead4KIOPS = v; }

    @JsonProperty("avg4KLatencyMs")
    public double getAvg4KLatencyMs() { return avg4KLatencyMs; }
    public void setAvg4KLatencyMs(double v) { avg4KLatencyMs = v; }
}
