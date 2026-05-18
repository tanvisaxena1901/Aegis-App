package com.aegis.kubernetes;

import java.time.Instant;
import java.util.List;

public record KubernetesPodLogs(
        String namespace,
        String podName,
        String container,
        int tailLines,
        List<String> lines,
        Instant observedAt
) {
}
