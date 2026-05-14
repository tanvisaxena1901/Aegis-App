package com.aegis.kubernetes;

import java.time.Instant;
import java.util.List;

public record ClusterSnapshot(
        String context,
        int namespaces,
        int pods,
        List<String> warningEvents,
        Instant observedAt
) {
}
