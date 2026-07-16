package com.sbtools.diskhealth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SmartAttribute {

    private int id;
    private String name;
    private String value;
    private String worst;
    private String threshold;
    private String rawValue;
    private String flags;

    public SmartAttribute() {}

    public SmartAttribute(int id, String name, String value, String worst, String threshold, String rawValue, String flags) {
        this.id = id;
        this.name = name;
        this.value = value;
        this.worst = worst;
        this.threshold = threshold;
        this.rawValue = rawValue;
        this.flags = flags;
    }

    @JsonProperty("id")
    public int getId() { return id; }
    public void setId(int v) { id = v; }

    @JsonProperty("name")
    public String getName() { return name; }
    public void setName(String v) { name = v; }

    @JsonProperty("value")
    public String getValue() { return value; }
    public void setValue(String v) { value = v; }

    @JsonProperty("worst")
    public String getWorst() { return worst; }
    public void setWorst(String v) { worst = v; }

    @JsonProperty("threshold")
    public String getThreshold() { return threshold; }
    public void setThreshold(String v) { threshold = v; }

    @JsonProperty("rawValue")
    public String getRawValue() { return rawValue; }
    public void setRawValue(String v) { rawValue = v; }

    @JsonProperty("flags")
    public String getFlags() { return flags; }
    public void setFlags(String v) { flags = v; }
}
