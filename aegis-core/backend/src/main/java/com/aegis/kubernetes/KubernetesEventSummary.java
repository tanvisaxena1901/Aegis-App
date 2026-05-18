package com.aegis.kubernetes;

import java.time.Instant;

public record KubernetesEventSummary(
        String namespace,
        String type,
        String reason,
        String message,
        String involvedObject,
        int count,
        Instant lastSeen
) {
}
