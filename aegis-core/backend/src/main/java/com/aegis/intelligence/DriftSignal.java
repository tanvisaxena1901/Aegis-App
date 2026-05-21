package com.aegis.intelligence;

public record DriftSignal(
        String kind,
        String namespace,
        String name,
        String field,
        String expectedValue,
        String actualValue,
        RiskLevel severity
) {
}
