package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BatteryInfo(
        @JsonProperty("name") String name,
        @JsonProperty("chargeLevel") int chargeLevel,
        @JsonProperty("remainingCapacityMwh") int remainingCapacityMwh,
        @JsonProperty("chargeRate") int chargeRate,
        @JsonProperty("status") String status,
        @JsonProperty("chemistry") String chemistry
) {
    public String formatChargeLevel() {
        if (chargeLevel < 0) return "";
        return chargeLevel + "%";
    }

    public String formatRemainingCapacity() {
        if (remainingCapacityMwh < 0) return "";
        double wh = remainingCapacityMwh / 1000.0;
        return String.format("%.1f Wh", wh);
    }

    public String formatChargeRate() {
        if (chargeRate < 0) return "";
        double watts = chargeRate / 1000.0;
        return String.format("%.1f W", watts);
    }
}
