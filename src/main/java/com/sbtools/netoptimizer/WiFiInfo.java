package com.sbtools.netoptimizer;

public record WiFiInfo(
        String ssid,
        String state,
        int signalPercent,
        String radioType,
        String channel,
        String receiveRate,
        String transmitRate
) {}
