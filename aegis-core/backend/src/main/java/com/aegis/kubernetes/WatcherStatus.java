package com.aegis.kubernetes;

import java.time.Instant;
import java.util.List;

public record WatcherStatus(
        boolean enabled,
        List<String> watchedResources,
        List<WatcherResourceStatus> resources,
        Instant observedAt
) {
}
