package com.aegis.intelligence;

public record DeploymentSpecChange(
        String field,
        String previousValue,
        String currentValue,
        String impact
) {
}
