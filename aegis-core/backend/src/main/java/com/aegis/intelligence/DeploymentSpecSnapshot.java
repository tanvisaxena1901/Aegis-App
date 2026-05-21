package com.aegis.intelligence;

import java.time.Instant;
import java.util.Map;

public record DeploymentSpecSnapshot(
        String namespace,
        String name,
        int replicas,
        Map<String, String> images,
        Map<String, String> env,
        Map<String, String> memoryLimits,
        Map<String, String> cpuLimits,
        Map<String, String> probeTimeouts,
        Instant observedAt
) {
}
