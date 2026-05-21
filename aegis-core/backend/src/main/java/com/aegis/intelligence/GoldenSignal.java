package com.aegis.intelligence;

public record GoldenSignal(
        String service,
        double latencyMs,
        double trafficRpm,
        double errorRatePercent,
        double saturationPercent,
        String sloTarget,
        String incidentPriority,
        String impact
) {
}
