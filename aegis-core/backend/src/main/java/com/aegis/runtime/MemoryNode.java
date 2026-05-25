package com.aegis.runtime;

import java.time.Instant;
import java.util.Map;

public record MemoryNode(
        String id,
        String label,
        String type,
        Map<String, String> properties,
        Instant observedAt
) {
}
