package com.aegis.kubernetes;

import java.time.Instant;

public record WatcherResourceStatus(
        String resource,
        String scope,
        int observedObjects,
        String resourceVersion,
        String status,
        String lastError,
        Instant observedAt
) {
}
