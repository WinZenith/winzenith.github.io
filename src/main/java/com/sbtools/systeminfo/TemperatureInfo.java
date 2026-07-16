package com.sbtools.systeminfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TemperatureInfo(
        @JsonProperty("zoneName") String zoneName,
        @JsonProperty("temperatureCelsius") double temperatureCelsius
) {
    public String formatTemperature() {
        if (temperatureCelsius <= 0) return "";
        return String.format("%.1f\u00B0C / %.1f\u00B0F", temperatureCelsius, temperatureCelsius * 9.0 / 5.0 + 32);
    }

    public String temperatureLevel() {
        if (temperatureCelsius >= 70) return "hot";
        if (temperatureCelsius >= 40) return "warm";
        return "cool";
    }
}
